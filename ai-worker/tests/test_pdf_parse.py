import hashlib
import json

import pytest
from fixtures import PDF_MEDIA_TYPE, build_pdf

from ai_worker import composition
from ai_worker.application.service import ParseRequest
from ai_worker.application.wire import result_to_wire
from ai_worker.domain import errors


def _request(document_id, data, media_type=PDF_MEDIA_TYPE):
    return ParseRequest(
        document_id=document_id,
        media_type=media_type,
        size_bytes=len(data),
        sha256=hashlib.sha256(data).hexdigest(),
    )


def test_korean_and_mixed_text_is_exact():
    page = "Invoice 123 품목: 사과 12,000"
    data = build_pdf([page])
    result = composition.parse_in_process(_request("doc", data), data)
    assert result.pdf.page_count == 1
    assert result.pdf.pages[0].page == 1
    assert result.pdf.pages[0].text == page


def test_multipage_order_and_empty_warning():
    data = build_pdf(["첫 페이지", None, "셋째 페이지"])
    result = composition.parse_in_process(_request("doc", data), data)
    assert [page.page for page in result.pdf.pages] == [1, 2, 3]
    assert result.pdf.pages[0].text == "첫 페이지"
    assert result.pdf.pages[1].text == ""
    assert result.pdf.pages[2].text == "셋째 페이지"
    warnings = [warning for warning in result.warnings if warning.code == "EMPTY_TEXT_LAYER"]
    assert len(warnings) == 1
    assert warnings[0].page == 2


def test_empty_pdf_page_is_warning_not_ocr_claim():
    data = build_pdf([None])
    result = composition.parse_in_process(_request("doc", data), data)
    assert result.pdf.pages[0].text == ""
    message = result.warnings[0].message.lower()
    assert "not ocred" in message or "not ocr" in message
    assert "scan" not in message.replace("image", "")


def test_result_is_deterministic_and_has_no_timestamps():
    data = build_pdf(["가나다", None])
    request = _request("doc-42", data)
    first = result_to_wire(composition.parse_in_process(request, data))
    second = result_to_wire(composition.parse_in_process(request, data))
    assert json.dumps(first, sort_keys=True) == json.dumps(second, sort_keys=True)
    serialized = json.dumps(first)
    for forbidden in ("timestamp", "createdAt", "parsedAt", "time"):
        assert forbidden not in serialized
    assert first["parserVersion"].startswith("document-parse-v1")
    assert first["source"]["sha256"] == hashlib.sha256(data).hexdigest()


def test_original_bytes_not_modified():
    data = build_pdf(["가나다"])
    before = bytes(data)
    composition.parse_in_process(_request("doc", data), data)
    assert data == before


def test_encrypted_pdf_rejected():
    from fixtures import encrypt_pdf

    data = encrypt_pdf(build_pdf(["secret"]), "pw")
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request("doc", data), data)
    assert excinfo.value.code == errors.PDF_ENCRYPTED


def test_corrupt_pdf_rejected():
    data = build_pdf(["가나다"])[:60] + b"\nnot a pdf anymore"
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request("doc", data), data)
    assert excinfo.value.code in (errors.PDF_CORRUPT, errors.FORMAT_UNSUPPORTED)


def test_page_limit_rejected():
    data = build_pdf([f"page {index}" for index in range(101)])
    with pytest.raises(errors.ParseFailure) as excinfo:
        composition.parse_in_process(_request("doc", data), data)
    assert excinfo.value.code == errors.PDF_PAGE_LIMIT
