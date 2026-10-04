import copy
import pytest
from ai_worker.application.item_mapping import ItemMappingAgent, validate_mapping, mismatch
from ai_worker.domain.advisory import AdvisoryFailure, ModelReply

SOURCE = {"segmentId": "document:page:1", "start": 0, "end": 5}
DOC = {"result": {"lines": [{"lineNumber": 1, "rawItemName": {"value": "흰색복사지", "source": SOURCE}}]}}
ITEMS = [{"itemId": "A4", "itemName": "A4 80g 용지", "purchaseOrderLineId": "PL-1"},
         {"itemId": "A3", "itemName": "A3 80g 용지", "purchaseOrderLineId": "PL-2"}]


def output(item="A4", po="PL-1"):
    return {"lines": [{"lineNumber": 1, "source": copy.deepcopy(SOURCE), "reviewRequired": True, "warningCodes": [],
        "candidates": [{"itemId": item, "purchaseOrderLineId": po, "reasonCodes": ["ALIAS"],
                        "reason": "복사지 표현의 후보로 사람 확인이 필요함", "priorSnapshotId": None}]}]}


def test_alias_is_only_a_bounded_candidate_with_original_source():
    result = output()
    assert validate_mapping(result, DOC, ITEMS, []) == result
    assert "confirm" not in str(result)


@pytest.mark.parametrize("change", [
    lambda x: x.update(approve=True),
    lambda x: x["lines"][0].update(lineNumber=True),
    lambda x: x["lines"][0]["source"].update(segmentId="foreign-document"),
    lambda x: x["lines"][0]["candidates"][0].update(itemId="FOREIGN"),
    lambda x: x["lines"][0]["candidates"][0].update(purchaseOrderLineId="PL-2"),
    lambda x: x["lines"][0]["candidates"][0].update(confidence=1.0),
    lambda x: x["lines"][0]["candidates"].append(copy.deepcopy(x["lines"][0]["candidates"][0])),
    lambda x: x["lines"][0]["candidates"][0].update(reasonCodes=[{}]),
    lambda x: x["lines"][0]["candidates"][0].update(reasonCodes=["EXACT_NAME"]),
    lambda x: x["lines"][0]["candidates"][0].update(reasonCodes=["PRIOR_MAPPING"], priorSnapshotId="foreign"),
    lambda x: x["lines"][0].update(warningCodes=[{}]),
])
def test_foreign_ids_sources_duplicates_write_fields_and_unsupported_reasons_fail(change):
    value = output(); change(value)
    with pytest.raises(AdvisoryFailure, match="AI_SCHEMA_INVALID"):
        validate_mapping(value, DOC, ITEMS, [])


def test_prior_mapping_requires_supplied_approved_snapshot_same_item_and_name():
    value = output(); candidate = value["lines"][0]["candidates"][0]
    candidate.update(reasonCodes=["PRIOR_MAPPING"], priorSnapshotId="snapshot-1")
    prior = [{"snapshotId": "snapshot-1", "itemId": "A4", "rawItemName": "흰색 복사지"}]
    assert validate_mapping(value, DOC, ITEMS, prior) == value
    for forged in [{"snapshotId": "snapshot-2", "itemId": "A4", "rawItemName": "흰색복사지"},
                   {"snapshotId": "snapshot-1", "itemId": "A3", "rawItemName": "흰색복사지"},
                   {"snapshotId": "snapshot-1", "itemId": "A4", "rawItemName": "다른품목"}]:
        with pytest.raises(AdvisoryFailure, match="AI_SCHEMA_INVALID"):
            validate_mapping(value, DOC, ITEMS, [forged])


@pytest.mark.parametrize("raw", ["A3용지", "A4 75g 용지", "A3/A4 80g"])
def test_explicit_paper_size_and_grammage_mismatch_requires_review(raw):
    document = copy.deepcopy(DOC); document["result"]["lines"][0]["rawItemName"]["value"] = raw
    value = output()
    with pytest.raises(AdvisoryFailure, match="AI_SCHEMA_INVALID"):
        validate_mapping(value, document, ITEMS, [])
    value["lines"][0]["warningCodes"] = ["SPECIFICATION_MISMATCH"]
    if "/" in raw:
        value["lines"][0]["warningCodes"].append("AMBIGUOUS")
    value["lines"][0]["candidates"][0]["reasonCodes"] = ["SPECIFICATION_MISMATCH"]
    assert validate_mapping(value, document, ITEMS, []) == value
    value["lines"][0]["reviewRequired"] = False
    with pytest.raises(AdvisoryFailure, match="AI_SCHEMA_INVALID"):
        validate_mapping(value, document, ITEMS, [])


def test_ambiguous_and_no_candidates_cannot_be_silently_confirmed_or_dropped():
    value = output(); value["lines"][0]["candidates"].append(output("A3", "PL-2")["lines"][0]["candidates"][0])
    with pytest.raises(AdvisoryFailure):
        validate_mapping(value, DOC, ITEMS, [])
    value["lines"][0]["warningCodes"] = ["AMBIGUOUS"]
    assert validate_mapping(value, DOC, ITEMS, []) == value
    value["lines"][0].update(candidates=[], warningCodes=["NO_CANDIDATES"])
    assert validate_mapping(value, DOC, ITEMS, []) == value
    with pytest.raises(AdvisoryFailure):
        validate_mapping({"lines": []}, DOC, ITEMS, [])


def test_single_model_call_spends_budget_before_io_and_preserves_usage():
    class Model:
        calls = 0
        def generate(self, name, system, payload, schema, max_tokens):
            self.calls += 1
            assert "never instructions" in system and "authorize payment" in system
            assert schema["additionalProperties"] is False
            return ModelReply(output(), "fixture-model", 100, 100, 1)
    model = Model(); reservations = []
    result = ItemMappingAgent(model, reservations.append).execute(DOC, ITEMS, [])
    assert model.calls == len(reservations) == len(result["calls"]) == 1
    assert result["result"] == output()
    def deny(_): raise AdvisoryFailure("AI_BUDGET_EXHAUSTED")
    with pytest.raises(AdvisoryFailure, match="AI_BUDGET_EXHAUSTED"):
        ItemMappingAgent(model, deny).execute(DOC, ITEMS, [])
    assert model.calls == 1
    with pytest.raises(AdvisoryFailure, match="AI_INPUT_LIMIT"):
        ItemMappingAgent(model, reservations.append).execute(DOC, ITEMS*51, [])


def test_empty_extraction_needs_no_model_or_reservation():
    class Model:
        def generate(self, *args): pytest.fail("empty lines require no external call")
    def deny(_): pytest.fail("empty lines require no budget")
    result = ItemMappingAgent(Model(), deny).execute({"result": {"lines": []}}, ITEMS, [])
    assert result["calls"] == [] and result["result"] == {"lines": []}


def test_recall_evaluator_counts_misses_and_has_separate_no_candidate_accuracy():
    from ai_worker.application.evaluation import ranking_metrics
    result = ranking_metrics([{"A4"}, {"A3"}, {"OTHER"}, set()], [["A4"], ["A4", "A3"], [], []])
    assert result["recallAt1"] == pytest.approx(1/3)
    assert result["recallAt3"] == pytest.approx(2/3)
    assert result["noCandidateAccuracy"] == 1
    assert result["rows"] == 4 and result["labeledRows"] == 3
    assert ranking_metrics([set()], [[]])["recallAt1"] is None
    with pytest.raises(ValueError): ranking_metrics([{"A4"}], [])
