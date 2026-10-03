import hashlib
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
from fixtures import PDF_MEDIA_TYPE, build_pdf

_SRC = Path(__file__).resolve().parents[1] / "src"


def _env():
    env = os.environ.copy()
    env["PYTHONPATH"] = str(_SRC) + os.pathsep + env.get("PYTHONPATH", "")
    return env


def _run(*args):
    return subprocess.run(
        [sys.executable, "-m", "ai_worker.api.cli", *args],
        capture_output=True,
        text=True,
        env=_env(),
        timeout=60,
    )


def _parse_args(path, data, size_bytes=None, sha256=None):
    return (
        "parse",
        str(path),
        "--document-id",
        "doc",
        "--media-type",
        PDF_MEDIA_TYPE,
        "--size-bytes",
        str(len(data) if size_bytes is None else size_bytes),
        "--sha256",
        sha256 if sha256 is not None else hashlib.sha256(data).hexdigest(),
    )


def test_cli_version():
    result = _run("version")
    assert result.returncode == 0
    assert json.loads(result.stdout)["parserVersion"].startswith("document-parse-v1")


def test_cli_does_not_expose_in_process_command():
    result = _run("parse-in-process", "whatever")
    assert result.returncode == 2
    assert "invalid choice" in result.stderr


def test_cli_missing_file_is_redacted(tmp_path):
    missing = tmp_path / "does-not-exist.pdf"
    result = _run(*_parse_args(missing, b"x"))
    assert result.returncode == 2
    payload = json.loads(result.stderr)
    assert payload["error"]["code"] == "INPUT_UNREADABLE"
    assert "Traceback" not in result.stderr
    assert str(missing) not in result.stderr
    assert "does-not-exist" not in result.stderr


def test_cli_metadata_size_mismatch(tmp_path):
    data = build_pdf(["가나다"])
    path = tmp_path / "doc.pdf"
    path.write_bytes(data)
    result = _run(*_parse_args(path, data, size_bytes=len(data) + 5))
    assert result.returncode == 2
    assert json.loads(result.stderr)["error"]["code"] == "SIZE_MISMATCH"


def test_cli_metadata_checksum_mismatch(tmp_path):
    data = build_pdf(["가나다"])
    path = tmp_path / "doc.pdf"
    path.write_bytes(data)
    result = _run(*_parse_args(path, data, sha256="0" * 64))
    assert result.returncode == 2
    assert json.loads(result.stderr)["error"]["code"] == "CHECKSUM_MISMATCH"


@pytest.mark.skipif(sys.platform.startswith("linux"), reason="host guard is for non-Linux hosts")
def test_cli_valid_metadata_fails_closed_on_windows(tmp_path):
    data = build_pdf(["가나다"])
    path = tmp_path / "doc.pdf"
    path.write_bytes(data)
    result = _run(*_parse_args(path, data))
    assert result.returncode == 2
    assert json.loads(result.stderr)["error"]["code"] == "UNSUPPORTED_HOST"


@pytest.mark.skipif(not sys.platform.startswith("linux"), reason="isolated production path is Linux-only")
def test_cli_isolated_parse_success(tmp_path):
    data = build_pdf(["격리", None])
    path = tmp_path / "doc.pdf"
    path.write_bytes(data)
    result = _run(*_parse_args(path, data))
    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["pdf"]["pages"][0]["text"] == "격리"
