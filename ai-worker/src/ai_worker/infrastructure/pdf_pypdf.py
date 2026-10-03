"""PDF text-layer extraction via pypdf, bounded by page count and text budget.

pypdf may allocate large decoded streams while extracting text; the input size
alone is not memory protection. This adapter runs only inside the isolated
child process whose address space is capped by the OS (see
:mod:`ai_worker.infrastructure.process_supervisor`).
"""

from __future__ import annotations

import io

from pypdf import PdfReader

from ai_worker.application.limits import ParseLimits, Utf8Budget
from ai_worker.domain import errors
from ai_worker.domain.result import PdfContent, PdfPage


class PypdfTextParser:
    def parse(self, data: bytes, limits: ParseLimits) -> PdfContent:
        try:
            reader = PdfReader(io.BytesIO(data))
            is_encrypted = bool(reader.is_encrypted)
        except Exception as exc:  # noqa: BLE001 - mapped to a stable code
            raise errors.ParseFailure(errors.PDF_CORRUPT) from exc

        if is_encrypted:
            raise errors.ParseFailure(errors.PDF_ENCRYPTED)

        try:
            page_count = len(reader.pages)
        except Exception as exc:  # noqa: BLE001
            raise errors.ParseFailure(errors.PDF_CORRUPT) from exc

        if page_count > limits.max_pdf_pages:
            raise errors.ParseFailure(errors.PDF_PAGE_LIMIT)

        budget = Utf8Budget(limits.max_text_value_bytes)
        pages: list[PdfPage] = []
        for page_number, page in enumerate(reader.pages, start=1):
            try:
                text = page.extract_text() or ""
            except errors.ParseFailure:
                raise
            except Exception as exc:  # noqa: BLE001
                raise errors.ParseFailure(errors.PDF_CORRUPT) from exc
            budget.add(text)
            pages.append(PdfPage(page=page_number, text=text))

        return PdfContent(page_count=page_count, pages=tuple(pages))
