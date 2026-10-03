"""Bounded, non-evaluating OOXML (.xlsx) structure reader.

A small purpose-built reader is used instead of openpyxl so that:

* numeric values keep their **original lexeme** (no float/int conversion),
* formulas stay as expressions and their cached result is reported separately,
* no formula, external link or automation is ever evaluated, and
* ZIP/decompression, cell and sheet bounds are enforced on the actual streamed
  bytes rather than on declared dimensions or ZIP metadata.

XML is parsed with defusedxml, which rejects DTDs and entity expansion.
"""

from __future__ import annotations

import io
import posixpath
import re
import zipfile
import zlib
from typing import Iterator

from defusedxml import ElementTree as DefusedET
from defusedxml.common import DefusedXmlException
from xml.etree.ElementTree import ParseError

from ai_worker.application.limits import ParseLimits, Utf8Budget
from ai_worker.domain import errors
from ai_worker.domain.result import (
    Cell,
    CellType,
    Sheet,
    SheetRow,
    SpreadsheetContent,
)

_NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
_NS_REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
_NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

_OLE_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"

_WORKBOOK_PART = "xl/workbook.xml"
_RELS_PART = "xl/_rels/workbook.xml.rels"
_SHARED_STRINGS_PART = "xl/sharedStrings.xml"
_STYLES_PART = "xl/styles.xml"

# Built-in number format ids that represent dates/times (ECMA-376 18.8.30).
_BUILTIN_DATE_FORMAT_IDS = frozenset(
    {14, 15, 16, 17, 18, 19, 20, 21, 22, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36}
    | {45, 46, 47}
    | {50, 51, 52, 53, 54, 55, 56, 57, 58}
)

_COLUMN_RE = re.compile(r"([A-Za-z]{1,3})([0-9]{1,9})")


def _tag(namespace: str, local: str) -> str:
    return f"{{{namespace}}}{local}"


def _column_index(letters: str) -> int:
    index = 0
    for char in letters.upper():
        if not ("A" <= char <= "Z"):
            return -1
        index = index * 26 + (ord(char) - ord("A") + 1)
    return index


def _column_letters(index: int) -> str:
    letters = ""
    while index > 0:
        index, remainder = divmod(index - 1, 26)
        letters = chr(ord("A") + remainder) + letters
    return letters


def _is_safe_entry_name(name: str) -> bool:
    if not name or name.startswith("/") or name.startswith("\\"):
        return False
    if "\\" in name or "\x00" in name:
        return False
    if len(name) >= 2 and name[1] == ":":
        return False
    parts = name.split("/")
    return ".." not in parts


def _looks_like_date_format(code: str) -> bool:
    stripped = re.sub(r'"[^"]*"', "", code)
    stripped = re.sub(r"\\.", "", stripped)
    stripped = re.sub(r"\[[^\]]*\]", "", stripped)
    lowered = stripped.lower()
    if re.search(r"[ydhs]", lowered):
        return True
    if "m" in lowered and not re.search(r"[0#?]", stripped):
        return True
    return False


class _Counter:
    __slots__ = ("value",)

    def __init__(self) -> None:
        self.value = 0


class SafeOoxmlSpreadsheetParser:
    def parse(self, data: bytes, limits: ParseLimits) -> SpreadsheetContent:
        if data.startswith(_OLE_MAGIC):
            raise errors.ParseFailure(errors.XLSX_ENCRYPTED)
        try:
            archive = zipfile.ZipFile(io.BytesIO(data))
        except (zipfile.BadZipFile, OSError, ValueError) as exc:
            raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc

        with archive:
            try:
                self._validate_archive(archive, limits)
                sheet_defs = self._read_sheet_definitions(archive)
                if len(sheet_defs) > limits.max_sheets:
                    raise errors.ParseFailure(errors.XLSX_SHEET_LIMIT)
                names = set(archive.namelist())
                relationships = self._read_relationships(archive)
                shared_strings = self._read_shared_strings(archive)
                date_styles = self._read_styles(archive)

                budget = Utf8Budget(limits.max_text_value_bytes)
                counter = _Counter()
                sheets: list[Sheet] = []
                for index, sheet_def in enumerate(sheet_defs, start=1):
                    target = relationships.get(sheet_def["rid"])
                    if target is None:
                        raise errors.ParseFailure(errors.XLSX_CORRUPT)
                    part = self._resolve_related_part(target)
                    if part not in names:
                        raise errors.ParseFailure(errors.XLSX_CORRUPT)
                    rows = self._parse_sheet(
                        archive,
                        part,
                        limits,
                        budget,
                        counter,
                        shared_strings,
                        date_styles,
                    )
                    sheets.append(
                        Sheet(
                            index=index,
                            name=sheet_def["name"],
                            state=sheet_def["state"],
                            rows=tuple(rows),
                        )
                    )
            except errors.ParseFailure:
                raise
            except DefusedXmlException as exc:
                raise errors.ParseFailure(errors.XML_UNSAFE) from exc
            except ParseError as exc:
                raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc
            except (zipfile.BadZipFile, zlib.error, EOFError, RuntimeError) as exc:
                raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc

        return SpreadsheetContent(sheet_count=len(sheets), sheets=tuple(sheets))

    # -- ZIP container -------------------------------------------------------

    def _validate_archive(self, archive: zipfile.ZipFile, limits: ParseLimits) -> None:
        infos = archive.infolist()
        if len(infos) > limits.max_zip_entries:
            raise errors.ParseFailure(errors.ZIP_ENTRY_LIMIT)

        seen: set[str] = set()
        total = 0
        for info in infos:
            name = info.filename
            if not _is_safe_entry_name(name):
                raise errors.ParseFailure(errors.ZIP_UNSAFE_PATH)
            key = name.lower()
            if key in seen:
                raise errors.ParseFailure(errors.ZIP_DUPLICATE_ENTRY)
            seen.add(key)
            if info.flag_bits & 0x1:
                raise errors.ParseFailure(errors.ZIP_ENCRYPTED_ENTRY)

            entry_bytes = 0
            try:
                with archive.open(info, "r") as stream:
                    while True:
                        chunk = stream.read(64 * 1024)
                        if not chunk:
                            break
                        entry_bytes += len(chunk)
                        total += len(chunk)
                        if entry_bytes > limits.max_zip_entry_uncompressed_bytes:
                            raise errors.ParseFailure(errors.ZIP_ENTRY_TOO_LARGE)
                        if total > limits.max_zip_total_uncompressed_bytes:
                            raise errors.ParseFailure(errors.ZIP_TOTAL_TOO_LARGE)
            except errors.ParseFailure:
                raise
            except NotImplementedError as exc:
                raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc
            except (zipfile.BadZipFile, zlib.error, EOFError, RuntimeError) as exc:
                raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc

            compress_size = info.compress_size
            if (
                entry_bytes >= limits.ratio_check_min_bytes
                and compress_size > 0
                and entry_bytes > compress_size * limits.max_compression_ratio
            ):
                raise errors.ParseFailure(errors.ZIP_COMPRESSION_RATIO)

    # -- XML parts -----------------------------------------------------------

    @staticmethod
    def _parse_xml(data: bytes):
        try:
            return DefusedET.fromstring(data)
        except DefusedXmlException as exc:
            raise errors.ParseFailure(errors.XML_UNSAFE) from exc
        except ParseError as exc:
            raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc

    def _iter_end(self, stream) -> Iterator:
        try:
            for _event, element in DefusedET.iterparse(stream, events=("end",)):
                yield element
        except DefusedXmlException as exc:
            raise errors.ParseFailure(errors.XML_UNSAFE) from exc
        except ParseError as exc:
            raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc

    def _read_sheet_definitions(self, archive: zipfile.ZipFile) -> list[dict[str, str]]:
        if _WORKBOOK_PART not in set(archive.namelist()):
            raise errors.ParseFailure(errors.XLSX_CORRUPT)
        root = self._parse_xml(archive.read(_WORKBOOK_PART))
        sheets_element = root.find(_tag(_NS_MAIN, "sheets"))
        definitions: list[dict[str, str]] = []
        if sheets_element is None:
            return definitions
        for sheet in sheets_element.findall(_tag(_NS_MAIN, "sheet")):
            definitions.append(
                {
                    "name": sheet.get("name") or "",
                    "rid": sheet.get(_tag(_NS_REL, "id")) or "",
                    "state": sheet.get("state") or "visible",
                }
            )
        return definitions

    def _read_relationships(self, archive: zipfile.ZipFile) -> dict[str, str]:
        if _RELS_PART not in set(archive.namelist()):
            raise errors.ParseFailure(errors.XLSX_CORRUPT)
        root = self._parse_xml(archive.read(_RELS_PART))
        relationships: dict[str, str] = {}
        for relationship in root.findall(_tag(_NS_PKG_REL, "Relationship")):
            rel_id = relationship.get("Id")
            target = relationship.get("Target")
            if rel_id and target:
                relationships[rel_id] = target
        return relationships

    @staticmethod
    def _resolve_related_part(target: str) -> str:
        if "://" in target or ".." in target.split("/"):
            raise errors.ParseFailure(errors.XLSX_CORRUPT)
        if target.startswith("/"):
            return target.lstrip("/")
        return posixpath.normpath(posixpath.join("xl", target))

    def _read_shared_strings(self, archive: zipfile.ZipFile) -> list[str]:
        if _SHARED_STRINGS_PART not in set(archive.namelist()):
            return []
        strings: list[str] = []
        with archive.open(_SHARED_STRINGS_PART) as stream:
            for element in self._iter_end(stream):
                if element.tag == _tag(_NS_MAIN, "si"):
                    parts = [node.text or "" for node in element.iter(_tag(_NS_MAIN, "t"))]
                    strings.append("".join(parts))
                    element.clear()
        return strings

    def _read_styles(self, archive: zipfile.ZipFile) -> dict[int, str | None]:
        if _STYLES_PART not in set(archive.namelist()):
            return {}
        root = self._parse_xml(archive.read(_STYLES_PART))

        custom: dict[int, str] = {}
        numfmts = root.find(_tag(_NS_MAIN, "numFmts"))
        if numfmts is not None:
            for definition in numfmts.findall(_tag(_NS_MAIN, "numFmt")):
                try:
                    format_id = int(definition.get("numFmtId") or "")
                except ValueError:
                    continue
                custom[format_id] = definition.get("formatCode") or ""

        styles: dict[int, str | None] = {}
        cell_xfs = root.find(_tag(_NS_MAIN, "cellXfs"))
        if cell_xfs is not None:
            for index, xf in enumerate(cell_xfs.findall(_tag(_NS_MAIN, "xf"))):
                try:
                    format_id = int(xf.get("numFmtId") or "0")
                except ValueError:
                    format_id = 0
                styles[index] = self._date_format(format_id, custom)
        return styles

    @staticmethod
    def _date_format(format_id: int, custom: dict[int, str]) -> str | None:
        if format_id in _BUILTIN_DATE_FORMAT_IDS:
            return str(format_id)
        code = custom.get(format_id)
        if code and _looks_like_date_format(code):
            return code
        return None

    # -- Worksheets ----------------------------------------------------------

    def _parse_sheet(
        self,
        archive: zipfile.ZipFile,
        part: str,
        limits: ParseLimits,
        budget: Utf8Budget,
        counter: _Counter,
        shared_strings: list[str],
        date_styles: dict[int, str | None],
    ) -> list[SheetRow]:
        rows: list[SheetRow] = []
        sequential_row = 0
        with archive.open(part) as stream:
            for element in self._iter_end(stream):
                if element.tag != _tag(_NS_MAIN, "row"):
                    continue
                sequential_row += 1
                row_number = self._int_or(element.get("r"), sequential_row)
                cells: list[Cell] = []
                column_sequence = 0
                for cell_element in element.findall(_tag(_NS_MAIN, "c")):
                    column_sequence += 1
                    parsed = self._parse_cell(
                        cell_element,
                        row_number,
                        column_sequence,
                        limits,
                        budget,
                        counter,
                        shared_strings,
                        date_styles,
                    )
                    if parsed is not None:
                        cells.append(parsed)
                if cells:
                    rows.append(SheetRow(row=row_number, cells=tuple(cells)))
                element.clear()
        return rows

    def _parse_cell(
        self,
        cell_element,
        row_number: int,
        column_sequence: int,
        limits: ParseLimits,
        budget: Utf8Budget,
        counter: _Counter,
        shared_strings: list[str],
        date_styles: dict[int, str | None],
    ) -> Cell | None:
        reference = cell_element.get("r")
        if reference:
            match = _COLUMN_RE.fullmatch(reference)
            if match is None:
                raise errors.ParseFailure(errors.XLSX_CORRUPT)
            column_number = _column_index(match.group(1))
            row_number = int(match.group(2))
        else:
            column_number = column_sequence

        if (
            row_number < 1
            or row_number > limits.max_rows_per_sheet
            or column_number < 1
            or column_number > limits.max_columns_per_sheet
        ):
            raise errors.ParseFailure(errors.XLSX_DIMENSION_LIMIT)

        formula_element = cell_element.find(_tag(_NS_MAIN, "f"))
        value_element = cell_element.find(_tag(_NS_MAIN, "v"))
        inline_element = cell_element.find(_tag(_NS_MAIN, "is"))
        cell_kind = cell_element.get("t") or "n"

        if formula_element is None and value_element is None and inline_element is None:
            return None

        counter.value += 1
        if counter.value > limits.max_nonempty_cells:
            raise errors.ParseFailure(errors.XLSX_CELL_LIMIT)

        coordinate = reference or f"{_column_letters(column_number)}{row_number}"
        style_index = self._int_or(cell_element.get("s"), 0)
        date_format = date_styles.get(style_index)

        if formula_element is not None:
            expression = "=" + (formula_element.text or "")
            cached_value = value_element.text if value_element is not None else None
            cached_type = self._cached_type(cell_kind)
            budget.add(expression)
            if cached_value:
                budget.add(cached_value)
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.FORMULA.value,
                value=expression,
                cached_value=cached_value,
                cached_type=cached_type,
            )

        if inline_element is not None:
            text = "".join(
                node.text or "" for node in inline_element.iter(_tag(_NS_MAIN, "t"))
            )
            budget.add(text)
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.STRING.value,
                value=text,
            )

        raw_value = value_element.text if value_element is not None else ""
        raw_value = raw_value or ""

        if cell_kind == "s":
            try:
                text = shared_strings[int(raw_value)]
            except (ValueError, IndexError) as exc:
                raise errors.ParseFailure(errors.XLSX_CORRUPT) from exc
            budget.add(text)
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.STRING.value,
                value=text,
            )

        if cell_kind == "inlineStr":
            budget.add(raw_value)
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.STRING.value,
                value=raw_value,
            )

        if cell_kind == "str":
            budget.add(raw_value)
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.STRING.value,
                value=raw_value,
            )

        if cell_kind == "b":
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.BOOLEAN.value,
                value=raw_value == "1",
            )

        if cell_kind == "e":
            budget.add(raw_value)
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.ERROR.value,
                value=raw_value,
            )

        # Numeric cell: preserve the exact original lexeme, never a float.
        budget.add(raw_value)
        if date_format is not None:
            return Cell(
                row=row_number,
                column=column_number,
                coordinate=coordinate,
                cell_type=CellType.DATE.value,
                value=raw_value,
                number_format=date_format,
            )
        return Cell(
            row=row_number,
            column=column_number,
            coordinate=coordinate,
            cell_type=CellType.NUMBER.value,
            value=raw_value,
        )

    @staticmethod
    def _cached_type(cell_kind: str) -> str:
        if cell_kind == "b":
            return CellType.BOOLEAN.value
        if cell_kind in ("s", "str", "inlineStr"):
            return CellType.STRING.value
        if cell_kind == "e":
            return CellType.ERROR.value
        return CellType.NUMBER.value

    @staticmethod
    def _int_or(value: str | None, default: int) -> int:
        if value is None:
            return default
        try:
            return int(value)
        except ValueError:
            return default
