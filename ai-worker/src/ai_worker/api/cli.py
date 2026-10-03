"""Internal CLI for parser verification.

``parse`` runs the production isolated path and fails closed on hosts that
cannot enforce the OS memory limit. ``parse-in-process`` exercises the parser
library directly (used by Windows library smoke tests).
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS
from ai_worker.application.service import ParseRequest
from ai_worker.application.wire import result_to_wire
from ai_worker.domain import errors
from ai_worker.version import PARSER_VERSION


def _read_bounded(path: str, max_bytes: int) -> bytes:
    size = os.path.getsize(path)
    if size > max_bytes:
        raise errors.ParseFailure(errors.INPUT_TOO_LARGE)
    with open(path, "rb") as handle:
        return handle.read(max_bytes + 1)


def _emit(payload: dict) -> None:
    sys.stdout.write(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    )
    sys.stdout.write("\n")


def _parse_command(args: argparse.Namespace, isolated: bool) -> int:
    try:
        data = _read_bounded(args.input, DEFAULT_LIMITS.max_input_bytes)
        request = ParseRequest(
            document_id=args.document_id,
            media_type=args.media_type,
            size_bytes=len(data),
            sha256=hashlib.sha256(data).hexdigest(),
        )
        if isolated:
            wire = composition.parse_isolated(request, data)
        else:
            wire = result_to_wire(composition.parse_in_process(request, data))
    except errors.ParseFailure as exc:
        sys.stderr.write(
            json.dumps(
                {"error": exc.to_wire()},
                ensure_ascii=False,
                separators=(",", ":"),
                sort_keys=True,
            )
            + "\n"
        )
        return 2
    _emit(wire)
    return 0


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="ai-worker", description="P2-04 parser CLI")
    subparsers = parser.add_subparsers(dest="command", required=True)

    subparsers.add_parser("version", help="print the fixed parser version")

    for name, help_text in (
        ("parse", "parse via the isolated production process"),
        ("parse-in-process", "parse via the in-process library"),
    ):
        sub = subparsers.add_parser(name, help=help_text)
        sub.add_argument("input", help="path to a PDF or XLSX file")
        sub.add_argument("--document-id", required=True)
        sub.add_argument("--media-type", required=True)
        sub.set_defaults(_isolated=name == "parse")

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "version":
        _emit({"parserVersion": PARSER_VERSION})
        return 0
    return _parse_command(args, args._isolated)


if __name__ == "__main__":
    raise SystemExit(main())
