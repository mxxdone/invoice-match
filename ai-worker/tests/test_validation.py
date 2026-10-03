import hashlib
import os

import pytest
from fixtures import PDF_MEDIA_TYPE, XLSX_MEDIA_TYPE, build_pdf

from ai_worker import composition
from ai_worker.application.limits import DEFAULT_LIMITS
from ai_worker.application.service import ParseRequest
from ai_worker.domain import errors
from ai_worker.infrastructure.process_supervisor import ProcessSupervisor


def _valid_pdf():
    return build_pdf(["가나다"])


def _request(data, media_type=PDF_MEDIA_TYPE, size_bytes=None, sha256=None):
    return ParseRequest(
        document_id="doc",
        media_type=media_type,
        size_bytes=len(data) if size_bytes is None else size_bytes,
        sha256=hashlib.sha256(data).hexdigest() if sha256 is None else sha256,
    )


def test_size_mismatch_rejected():
    data = _valid_pdf()
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, size_bytes=len(data) + 1), data)
    assert excinfo.value.code == errors.SIZE_MISMATCH


def test_declared_oversize_rejected_before_parsing():
    data = _valid_pdf()
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(
            _request(data, size_bytes=DEFAULT_LIMITS.max_input_bytes + 1), data
        )
    assert excinfo.value.code == errors.INPUT_TOO_LARGE


def test_checksum_mismatch_rejected():
    data = _valid_pdf()
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, sha256="0" * 64), data)
    assert excinfo.value.code == errors.CHECKSUM_MISMATCH


def test_format_mismatch_rejected():
    data = _valid_pdf()
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, media_type=XLSX_MEDIA_TYPE), data)
    assert excinfo.value.code == errors.FORMAT_MISMATCH


def test_unsupported_format_rejected():
    data = b"just some bytes that are not a document at all"
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, media_type=PDF_MEDIA_TYPE), data)
    assert excinfo.value.code == errors.FORMAT_UNSUPPORTED


def test_unknown_declared_media_type_rejected():
    data = _valid_pdf()
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request(data, media_type="text/plain"), data)
    assert excinfo.value.code == errors.FORMAT_MISMATCH


def test_error_messages_do_not_leak_content_or_paths():
    data = _valid_pdf()
    failure = errors.ParseFailure(errors.PDF_CORRUPT)
    wire = failure.to_wire()
    serialized = str(wire)
    assert "C:" not in serialized and os.sep not in str(wire["code"])
    assert data[:8].decode("latin-1") not in serialized


@pytest.mark.skipif(os.name == "posix", reason="host guard is for non-Linux hosts")
def test_production_entry_fails_closed_without_os_memory_limit():
    header = {"sizeBytes": 0}
    with pytest.raises(errors.ParseFailure) as excinfo:
        ProcessSupervisor().run(header, b"", DEFAULT_LIMITS)
    assert excinfo.value.code == errors.UNSUPPORTED_HOST
