from __future__ import annotations

import io
import zipfile

_PDF_MAGIC = b"%PDF-"
_OLE_MAGIC = b"\xd0\xcf\x11\xe0\xa1\xb1\x1a\xe1"
_ZIP_MAGICS = (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08")


class MagicFormatDetector:
    """Signature-based format detection.

    An OLE compound file is reported as ``xlsx`` so the spreadsheet adapter can
    return a typed ``XLSX_ENCRYPTED`` failure instead of a generic unsupported
    format; the declared media type is checked separately by the application.
    """

    def detect(self, data: bytes) -> str | None:
        if data.startswith(_PDF_MAGIC):
            return "pdf"
        if data.startswith(_OLE_MAGIC):
            return "xlsx"
        if data[:4] in _ZIP_MAGICS and self._is_ooxml_package(data):
            return "xlsx"
        return None

    @staticmethod
    def _is_ooxml_package(data: bytes) -> bool:
        try:
            with zipfile.ZipFile(io.BytesIO(data)) as archive:
                names = set(archive.namelist())
        except (zipfile.BadZipFile, OSError, ValueError):
            return False
        return "xl/workbook.xml" in names and "[Content_Types].xml" in names
