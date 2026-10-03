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


def test_cli_version():
    result = _run("version")
    assert result.returncode == 0
    assert json.loads(result.stdout)["parserVersion"].startswith("document-parse-v1")


def test_cli_parse_in_process(tmp_path):
    data = build_pdf(["가나다"])
    path = tmp_path / "doc.pdf"
    path.write_bytes(data)
    result = _run(
        "parse-in-process",
        str(path),
        "--document-id",
        "doc-1",
        "--media-type",
        PDF_MEDIA_TYPE,
    )
    assert result.returncode == 0, result.stderr
    payload = json.loads(result.stdout)
    assert payload["kind"] == "pdf"
    assert payload["pdf"]["pages"][0]["text"] == "가나다"
    assert payload["source"]["sha256"]


def test_cli_error_is_typed_and_nonzero(tmp_path):
    path = tmp_path / "broken.pdf"
    path.write_bytes(b"not a document")
    result = _run(
        "parse-in-process",
        str(path),
        "--document-id",
        "doc",
        "--media-type",
        PDF_MEDIA_TYPE,
    )
    assert result.returncode == 2
    code = json.loads(result.stderr)["error"]["code"]
    assert code == "FORMAT_UNSUPPORTED"


@pytest.mark.skipif(os.name != "posix", reason="isolated production path is Linux-only")
def test_cli_isolated_parse(tmp_path):
    data = build_pdf(["격리"])
    path = tmp_path / "doc.pdf"
    path.write_bytes(data)
    result = _run("parse", str(path), "--document-id", "d", "--media-type", PDF_MEDIA_TYPE)
    assert result.returncode == 0, result.stderr
    assert json.loads(result.stdout)["pdf"]["pages"][0]["text"] == "격리"
