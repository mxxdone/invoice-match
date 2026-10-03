"""Ports implemented by infrastructure adapters.

The application layer talks to these protocols only; it never imports a parser
SDK directly.
"""

from __future__ import annotations

from typing import Protocol

from ai_worker.application.limits import ParseLimits
from ai_worker.domain.result import PdfContent, SpreadsheetContent


class PdfParser(Protocol):
    def parse(self, data: bytes, limits: ParseLimits) -> PdfContent: ...


class SpreadsheetParser(Protocol):
    def parse(self, data: bytes, limits: ParseLimits) -> SpreadsheetContent: ...


class FormatDetector(Protocol):
    """Returns ``"pdf"``, ``"xlsx"`` or ``None`` for unsupported bytes."""

    def detect(self, data: bytes) -> str | None: ...


class Sha256Hasher(Protocol):
    def digest(self, data: bytes) -> str: ...
