"""Bounded PDF/XLSX parser package (P2-04).

Domain result types live in :mod:`ai_worker.domain`, use-case orchestration in
:mod:`ai_worker.application`, SDK/OS/process adapters in
:mod:`ai_worker.infrastructure`, and the input/output surface in
:mod:`ai_worker.api`. The composition root :mod:`ai_worker.composition` is the
only place that wires concrete adapters together.
"""

from ai_worker.version import PARSER_VERSION, SCHEMA_VERSION

__all__ = ["PARSER_VERSION", "SCHEMA_VERSION"]
