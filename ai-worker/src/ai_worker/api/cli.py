"""Internal CLI for parser verification.

The deployable surface is the isolated ``parse`` command and ``version``. The
in-process library entrypoint (used by unit tests) is deliberately not exposed
as a CLI command. ``parse`` takes the server-confirmed size/checksum, validates
them before starting the isolated process, and fails closed on hosts that
cannot enforce the OS memory limit.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS
from ai_worker.application.service import ParseRequest
from ai_worker.domain import errors
from ai_worker.version import PARSER_VERSION


def _read_bounded(path: str, max_bytes: int) -> bytes:
    try:
        size = os.path.getsize(path)
        if size > max_bytes:
            raise errors.ParseFailure(errors.INPUT_TOO_LARGE)
        with open(path, "rb") as handle:
            return handle.read(max_bytes + 1)
    except errors.ParseFailure:
        raise
    except OSError as exc:
        # No path, errno or traceback is exposed.
        raise errors.ParseFailure(errors.INPUT_UNREADABLE) from exc


def _emit(payload: dict) -> None:
    sys.stdout.write(
        json.dumps(payload, ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    )
    sys.stdout.write("\n")


def _parse_command(args: argparse.Namespace) -> int:
    try:
        data = _read_bounded(args.input, DEFAULT_LIMITS.max_input_bytes)
        request = ParseRequest(
            document_id=args.document_id,
            media_type=args.media_type,
            size_bytes=args.size_bytes,
            sha256=args.sha256,
        )
        composition.build_service().verify_source(request, data)
        wire = composition.parse_isolated(request, data)
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

    parse = subparsers.add_parser("parse", help="parse via the isolated production process")
    parse.add_argument("input", help="path to a PDF or XLSX file")
    parse.add_argument("--document-id", required=True)
    parse.add_argument("--media-type", required=True)
    parse.add_argument("--size-bytes", required=True, type=int)
    parse.add_argument("--sha256", required=True)

    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    if args.command == "version":
        _emit({"parserVersion": PARSER_VERSION})
        return 0
    return _parse_command(args)


if __name__ == "__main__":
    raise SystemExit(main())
