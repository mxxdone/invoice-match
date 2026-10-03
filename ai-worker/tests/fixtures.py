"""Minimal, dependency-light builders for valid and defective test documents.

All fixtures are generated into ``tmp_path`` at test time; nothing persistent
is written into the repository. PDFs embed a Type0/Identity-H font with a
ToUnicode CMap so Korean text extracts portably without any external font.
"""

from __future__ import annotations

import io
import zipfile
from dataclasses import dataclass, field

MAIN_NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
REL_NS = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
PKG_REL_NS = "http://schemas.openxmlformats.org/package/2006/relationships"
CT_NS = "http://schemas.openxmlformats.org/package/2006/content-types"
XLSX_MEDIA_TYPE = (
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
)
PDF_MEDIA_TYPE = "application/pdf"


# --------------------------------------------------------------------------- PDF


def _build_to_unicode(mapping: dict[int, int]) -> bytes:
    body = (
        b"/CIDInit /ProcSet findresource begin\n"
        b"12 dict begin\nbegincmap\n"
        b"/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n"
        b"/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n"
        b"1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n"
        + str(len(mapping)).encode()
        + b" beginbfchar\n"
    )
    for glyph, codepoint in mapping.items():
        body += ("<%04X> <%04X>\n" % (glyph, codepoint)).encode("ascii")
    body += b"endbfchar\nendcmap\nCMapName currentdict /CMap defineresource pop\nend\nend\n"
    return body


def build_pdf(pages: list[str | None]) -> bytes:
    """Build a PDF whose pages contain the given text (``None`` = empty page)."""
    characters: list[str] = []
    for page in pages:
        if not page:
            continue
        for char in page:
            if char not in characters:
                characters.append(char)
    code_by_char = {char: index + 1 for index, char in enumerate(characters)}
    to_unicode = _build_to_unicode(
        {code: ord(char) for char, code in code_by_char.items()}
    )

    objects: dict[int, bytes] = {}
    objects[5] = (
        b"<< /Type /Font /Subtype /Type0 /BaseFont /F0 /Encoding /Identity-H "
        b"/DescendantFonts [6 0 R] /ToUnicode 7 0 R >>"
    )
    objects[6] = (
        b"<< /Type /Font /Subtype /CIDFontType2 /BaseFont /F0 "
        b"/CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> "
        b"/FontDescriptor 8 0 R /DW 1000 /CIDToGIDMap /Identity >>"
    )
    objects[7] = (
        b"<< /Length %d >>\nstream\n" % len(to_unicode)
        + to_unicode
        + b"\nendstream"
    )
    objects[8] = (
        b"<< /Type /FontDescriptor /FontName /F0 /Flags 4 /FontBBox [0 0 1000 1000] "
        b"/ItalicAngle 0 /Ascent 800 /Descent -200 /CapHeight 700 /StemV 80 >>"
    )

    page_numbers: list[int] = []
    next_number = 9
    for page in pages:
        if page:
            encoded = b"".join(b"%04X" % code_by_char[char] for char in page)
            content = b"BT /F0 24 Tf 72 700 Td <" + encoded + b"> Tj ET"
        else:
            content = b""
        content_number = next_number
        next_number += 1
        page_number = next_number
        next_number += 1
        objects[content_number] = (
            b"<< /Length %d >>\nstream\n" % len(content) + content + b"\nendstream"
        )
        objects[page_number] = (
            b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            b"/Resources << /Font << /F0 5 0 R >> >> /Contents %d 0 R >>"
            % content_number
        )
        page_numbers.append(page_number)

    objects[1] = b"<< /Type /Catalog /Pages 2 0 R >>"
    objects[2] = (
        b"<< /Type /Pages /Kids ["
        + b" ".join(b"%d 0 R" % number for number in page_numbers)
        + b"] /Count %d >>" % len(page_numbers)
    )

    output = io.BytesIO()
    output.write(b"%PDF-1.7\n%\xe2\xe3\xcf\xd3\n")
    offsets: dict[int, int] = {}
    max_number = max(objects)
    for number in range(1, max_number + 1):
        if number not in objects:
            continue
        offsets[number] = output.tell()
        output.write(b"%d 0 obj\n" % number + objects[number] + b"\nendobj\n")
    xref_position = output.tell()
    output.write(b"xref\n0 %d\n" % (max_number + 1))
    output.write(b"0000000000 65535 f \n")
    for number in range(1, max_number + 1):
        if number in offsets:
            output.write(b"%010d 00000 n \n" % offsets[number])
        else:
            output.write(b"0000000000 65535 f \n")
    output.write(
        b"trailer\n<< /Size %d /Root 1 0 R >>\nstartxref\n%d\n%%%%EOF\n"
        % (max_number + 1, xref_position)
    )
    return output.getvalue()


def encrypt_pdf(data: bytes, password: str = "secret") -> bytes:
    from pypdf import PdfReader, PdfWriter

    reader = PdfReader(io.BytesIO(data))
    writer = PdfWriter()
    for page in reader.pages:
        writer.add_page(page)
    writer.encrypt(password)
    output = io.BytesIO()
    writer.write(output)
    return output.getvalue()


# -------------------------------------------------------------------------- XLSX


@dataclass
class CellSpec:
    ref: str
    kind: str | None = None  # XML "t" attribute; None = numeric
    value: str | None = None
    formula: str | None = None
    inline: str | None = None
    style: int | None = None

    def to_xml(self) -> str:
        attributes = f' r="{self.ref}"'
        if self.kind:
            attributes += f' t="{self.kind}"'
        if self.style is not None:
            attributes += f' s="{self.style}"'
        inner = ""
        if self.formula is not None:
            inner += f"<f>{_xml_escape(self.formula)}</f>"
        if self.inline is not None:
            inner += f"<is><t>{_xml_escape(self.inline)}</t></is>"
        if self.value is not None:
            inner += f"<v>{_xml_escape(self.value)}</v>"
        return f"<c{attributes}>{inner}</c>"


@dataclass
class SheetSpec:
    name: str
    cells: list[CellSpec] = field(default_factory=list)
    state: str | None = None
    dimension: str | None = None


def _xml_escape(text: str) -> str:
    return (
        text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
    )


def _sheet_xml(sheet: SheetSpec, doctype: str | None = None) -> bytes:
    if not sheet.cells:
        rows = ""
    else:
        by_row: dict[int, list[CellSpec]] = {}
        for cell in sheet.cells:
            row_number = _ref_row(cell.ref)
            by_row.setdefault(row_number, []).append(cell)
        row_parts = []
        for row_number in sorted(by_row):
            cells = "".join(cell.to_xml() for cell in by_row[row_number])
            row_parts.append(f'<row r="{row_number}">{cells}</row>')
        rows = "".join(row_parts)

    dimension = sheet.dimension
    if dimension is None and sheet.cells:
        columns = [_ref_column(cell.ref) for cell in sheet.cells]
        rows_numbers = [_ref_row(cell.ref) for cell in sheet.cells]
        min_col = min(_column_index(c) for c in columns)
        max_col = max(_column_index(c) for c in columns)
        dimension = (
            f"{_column_letters(min_col)}{min(rows_numbers)}:"
            f"{_column_letters(max_col)}{max(rows_numbers)}"
        )
    dimension_xml = f'<dimension ref="{dimension}"/>' if dimension else ""

    doctype_xml = f"{doctype}\n" if doctype else ""
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f"{doctype_xml}"
        f'<worksheet xmlns="{MAIN_NS}">{dimension_xml}'
        f"<sheetData>{rows}</sheetData></worksheet>"
    ).encode("utf-8")


def _styles_xml(num_format_ids: list[int], custom: dict[int, str] | None = None) -> bytes:
    custom = custom or {}
    if custom:
        num_fmts = (
            "<numFmts count=\"%d\">" % len(custom)
            + "".join(
                f'<numFmt numFmtId="{fid}" formatCode="{_xml_escape(code)}"/>'
                for fid, code in sorted(custom.items())
            )
            + "</numFmts>"
        )
    else:
        num_fmts = ""
    xfs = "".join(f'<xf numFmtId="{fid}" fontId="0" fillId="0" borderId="0"/>' for fid in num_format_ids)
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<styleSheet xmlns="{MAIN_NS}">{num_fmts}'
        f'<cellXfs count="{len(num_format_ids)}">{xfs}</cellXfs></styleSheet>'
    ).encode("utf-8")


def _shared_strings_xml(strings: list[str]) -> bytes:
    items = "".join(f"<si><t>{_xml_escape(item)}</t></si>" for item in strings)
    return (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<sst xmlns="{MAIN_NS}" count="{len(strings)}" uniqueCount="{len(strings)}">'
        f"{items}</sst>"
    ).encode("utf-8")


def build_xlsx(
    sheets: list[SheetSpec],
    *,
    shared_strings: list[str] | None = None,
    style_num_format_ids: list[int] | None = None,
    style_custom_formats: dict[int, str] | None = None,
    extra_entries: list[tuple[str, bytes]] | None = None,
    entries_override: dict[str, bytes] | None = None,
    compression: int = zipfile.ZIP_DEFLATED,
) -> bytes:
    shared_strings = shared_strings or []
    style_num_format_ids = style_num_format_ids or [0]
    extra_entries = extra_entries or []
    entries_override = entries_override or {}

    content_types = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        f'<Types xmlns="{CT_NS}">',
        '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>',
        '<Default Extension="xml" ContentType="application/xml"/>',
        '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>',
    ]
    for index in range(len(sheets)):
        content_types.append(
            f'<Override PartName="/xl/worksheets/sheet{index + 1}.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
        )
    if shared_strings:
        content_types.append(
            '<Override PartName="/xl/sharedStrings.xml" '
            'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>'
        )
    content_types.append(
        '<Override PartName="/xl/styles.xml" '
        'ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
    )
    content_types.append("</Types>")

    sheet_elements = []
    rel_elements = [
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>',
        f'<Relationships xmlns="{PKG_REL_NS}">',
    ]
    for index, sheet in enumerate(sheets, start=1):
        state = f' state="{sheet.state}"' if sheet.state else ""
        sheet_elements.append(
            f'<sheet name="{_xml_escape(sheet.name)}" sheetId="{index}" '
            f'r:id="rIdSheet{index}"{state}/>'
        )
        rel_elements.append(
            f'<Relationship Id="rIdSheet{index}" '
            f'Type="{REL_NS}/worksheet" Target="worksheets/sheet{index}.xml"/>'
        )
    rel_elements.append("</Relationships>")

    workbook = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<workbook xmlns="{MAIN_NS}" xmlns:r="{REL_NS}">'
        f'<sheets>{"".join(sheet_elements)}</sheets></workbook>'
    ).encode("utf-8")

    root_rels = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        f'<Relationships xmlns="{PKG_REL_NS}">'
        '<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>'
        "</Relationships>"
    ).encode("utf-8")

    entries: dict[str, bytes] = {
        "[Content_Types].xml": "\n".join(content_types).encode("utf-8"),
        "_rels/.rels": root_rels,
        "xl/workbook.xml": workbook,
        "xl/_rels/workbook.xml.rels": "\n".join(rel_elements).encode("utf-8"),
        "xl/styles.xml": _styles_xml(style_num_format_ids, style_custom_formats),
    }
    for index, sheet in enumerate(sheets, start=1):
        entries[f"xl/worksheets/sheet{index}.xml"] = _sheet_xml(sheet)
    if shared_strings:
        entries["xl/sharedStrings.xml"] = _shared_strings_xml(shared_strings)
    for name, content in extra_entries:
        entries[name] = content
    entries.update(entries_override)

    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=compression) as archive:
        for name, content in entries.items():
            archive.writestr(name, content)
    return buffer.getvalue()


def raw_zip(
    entries: list[tuple[str, bytes]],
    compression: int = zipfile.ZIP_DEFLATED,
) -> bytes:
    """Write a ZIP preserving duplicate names exactly as given."""
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=compression) as archive:
        for name, content in entries:
            info = zipfile.ZipInfo(name)
            info.compress_type = compression
            with _suppress_duplicate_warning():
                archive.writestr(info, content)
    return buffer.getvalue()


class _suppress_duplicate_warning:
    def __enter__(self):
        import warnings

        self._context = warnings.catch_warnings()
        self._context.__enter__()
        warnings.simplefilter("ignore", UserWarning)
        return self

    def __exit__(self, *exc_info):
        return self._context.__exit__(*exc_info)


def set_zip_entry_flag(data: bytes, name: str, bits: int) -> bytes:
    """Set the general-purpose bit flag for a named entry (e.g. encryption)."""
    buffer = bytearray(data)
    target = name.encode("utf-8")
    for signature, flag_offset, name_length_offset, name_offset in (
        (b"PK\x03\x04", 6, 26, 30),
        (b"PK\x01\x02", 8, 28, 46),
    ):
        index = 0
        while True:
            index = bytes(buffer).find(signature, index)
            if index < 0:
                break
            name_length = int.from_bytes(
                buffer[index + name_length_offset : index + name_length_offset + 2],
                "little",
            )
            entry_name = bytes(
                buffer[index + name_offset : index + name_offset + name_length]
            )
            if entry_name == target:
                buffer[index + flag_offset : index + flag_offset + 2] = bits.to_bytes(
                    2, "little"
                )
            index += 4
    return bytes(buffer)


def set_zip_entry_compress_size_zero(data: bytes, name: str) -> bytes:
    """Declare zero compressed bytes for a non-empty entry in both headers."""
    buffer = bytearray(data)
    target = name.encode("utf-8")
    for signature, compressed_size_offset, name_length_offset, name_offset in (
        (b"PK\x03\x04", 18, 26, 30),
        (b"PK\x01\x02", 20, 28, 46),
    ):
        index = 0
        while True:
            index = bytes(buffer).find(signature, index)
            if index < 0:
                break
            name_length = int.from_bytes(
                buffer[index + name_length_offset : index + name_length_offset + 2],
                "little",
            )
            entry_name = bytes(
                buffer[index + name_offset : index + name_offset + name_length]
            )
            if entry_name == target:
                buffer[
                    index + compressed_size_offset : index + compressed_size_offset + 4
                ] = (0).to_bytes(4, "little")
            index += 4
    return bytes(buffer)


def _ref_column(ref: str) -> str:
    letters = ""
    for char in ref:
        if char.isalpha():
            letters += char
        else:
            break
    return letters


def _ref_row(ref: str) -> int:
    digits = "".join(char for char in ref if char.isdigit())
    return int(digits) if digits else 1


def _column_index(letters: str) -> int:
    index = 0
    for char in letters.upper():
        index = index * 26 + (ord(char) - ord("A") + 1)
    return index


def _column_letters(index: int) -> str:
    letters = ""
    while index > 0:
        index, remainder = divmod(index - 1, 26)
        letters = chr(ord("A") + remainder) + letters
    return letters
