import copy
from uuid import uuid4
import pytest
from ai_worker.application.advisory_contract import normalize, validate_extraction
from ai_worker.application.document_agent import DocumentAgent, parser_segments
from ai_worker.domain.advisory import AdvisoryFailure, ModelReply, SourceSegment


TEXT = "청구 INV-1 2026.10.04 A4 용지 60개 ₩2,500"
SEGMENT = SourceSegment("source-1", str(uuid4()), TEXT, "parser", page=1)


def field(raw, value=None):
    start = TEXT.index(raw)
    return {"value": value or raw, "source": {"segmentId": "source-1", "start": start, "end": start+len(raw)}}


def extraction():
    return {"fields": [{"name": "invoiceNumber", **field("INV-1")},
                       {"name": "invoiceDate", **field("2026.10.04", "2026-10-04")}],
            "lines": [{"lineNumber": 1, "rawItemName": field("A4 용지"), "quantity": field("60개", "60"),
                       "unitPrice": field("₩2,500", "2500")}], "warnings": []}


def test_unicode_numeric_and_source_validation():
    value = extraction()
    assert validate_extraction(value, (SEGMENT,)) == value
    assert normalize("1,000 EA", "quantity") == "1000"
    assert normalize("KRW 0", "unitPrice") == "0"


@pytest.mark.parametrize("raw,kind", [("1,00", "quantity"), ("60 BOX", "quantity"), ("1.5", "quantity"),
    ("2,500.50", "unitPrice"), ("-1", "quantity"), ("NaN", "unitPrice"), ("2026-02-30", "invoiceDate"),
    ("10/04/2026", "invoiceDate"), ("$", "currency"), ("999999999999999999999", "unitPrice")])
def test_normalization_does_not_guess_units_currency_or_round(raw, kind):
    with pytest.raises(AdvisoryFailure, match="AI_SCHEMA_INVALID"):
        normalize(raw, kind)


@pytest.mark.parametrize("change", [
    lambda x: x.update(approve=True),
    lambda x: x["fields"].append(copy.deepcopy(x["fields"][0])),
    lambda x: x["fields"][0].update(value="forged"),
    lambda x: x["fields"][0]["source"].update(segmentId="other-document"),
    lambda x: x["fields"][0]["source"].update(start=-1),
    lambda x: x["fields"][0]["source"].update(end=999),
    lambda x: x["lines"].append(copy.deepcopy(x["lines"][0])),
    lambda x: x["lines"][0].update(lineNumber=True),
    lambda x: x["lines"][0]["quantity"].update(value="100"),
    lambda x: x.update(warnings=[{}]),
])
def test_strict_output_never_accepts_forged_values_or_write_fields(change):
    value = extraction(); change(value)
    with pytest.raises(AdvisoryFailure, match="AI_SCHEMA_INVALID"):
        validate_extraction(value, (SEGMENT,))


def test_one_repair_spends_another_reservation_and_preserves_usage():
    class Model:
        calls = 0
        def generate(self, name, system, payload, schema, max_tokens):
            self.calls += 1
            assert schema["additionalProperties"] is False
            assert "untrusted data" in system
            output = extraction()
            if self.calls == 1:
                output["fields"][0]["value"] = "forged"
            else:
                assert "repair" in payload
            return ModelReply(output, "fixture-model-v1", 50, 100, 2)
    model = Model(); reservations = []
    output = DocumentAgent(model, reservations.append).execute((SEGMENT,))
    assert model.calls == len(reservations) == len(output["calls"]) == 2
    assert all(n > 1000 for n in reservations)
    assert output["result"] == extraction()


def test_invalid_wire_repairs_once_and_rate_limit_is_not_locally_retried():
    class Model:
        calls = 0
        code = "AI_SCHEMA_INVALID"
        def generate(self, *args):
            self.calls += 1
            raise AdvisoryFailure(self.code)
    model = Model(); reservations = []
    with pytest.raises(AdvisoryFailure, match="AI_SCHEMA_INVALID"):
        DocumentAgent(model, reservations.append).execute((SEGMENT,))
    assert model.calls == 2
    model.calls = 0; model.code = "AI_RATE_LIMIT"
    with pytest.raises(AdvisoryFailure, match="AI_RATE_LIMIT"):
        DocumentAgent(model, reservations.append).execute((SEGMENT,))
    assert model.calls == 1


def test_budget_denial_prevents_call_and_oversize_does_not_truncate():
    class Model:
        def generate(self, *args):
            pytest.fail("no external call may occur")
    def deny(_):
        raise AdvisoryFailure("AI_BUDGET_EXHAUSTED")
    with pytest.raises(AdvisoryFailure, match="AI_BUDGET_EXHAUSTED"):
        DocumentAgent(Model(), deny).execute((SEGMENT,))
    with pytest.raises(AdvisoryFailure, match="AI_INPUT_LIMIT"):
        DocumentAgent(Model(), deny).execute((SourceSegment("large", SEGMENT.document_id, "x"*20001, "parser"),))


def test_parser_preserves_locations_and_never_uses_formula_or_hidden_cells():
    context = {"documents": [{"documentId": SEGMENT.document_id, "parsed": {"kind": "xlsx", "xlsx": {"sheets": [
        {"index": 1, "state": "visible", "rows": [{"cells": [
            {"coordinate": "A1", "type": "string", "value": "A4"},
            {"coordinate": "B1", "type": "formula", "value": "HYPERLINK(secret)"}]}]},
        {"index": 2, "state": "hidden", "rows": [{"cells": [{"coordinate": "A1", "type": "string", "value": "hidden"}]}]}]}}}]}
    sources = parser_segments(context)
    assert len(sources) == 1
    assert sources[0].text == "A4" and sources[0].sheet == 1 and sources[0].cell == "A1"


def test_boolean_cell_uses_json_spelling_for_cross_runtime_citations():
    context = {"documents": [{"documentId": SEGMENT.document_id, "parsed": {"kind": "xlsx", "xlsx": {"sheets": [
        {"index": 1, "state": "visible", "rows": [{"cells": [
            {"coordinate": "A1", "type": "boolean", "value": True},
            {"coordinate": "B1", "type": "boolean", "value": False}]}]}]}}}]}
    assert [source.text for source in parser_segments(context)] == ["true", "false"]
