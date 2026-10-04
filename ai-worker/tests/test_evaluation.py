import copy
import json
from pathlib import Path
import pytest
from ai_worker.application.evaluation import evaluate_cases, literal_baseline

CORPUS = json.loads((Path(__file__).parents[1] / 'evaluation/cases.json').read_text(encoding='utf-8'))


def perfect(case):
    gold = case['gold']
    return {'caseId': case['id'], 'fields': copy.deepcopy(gold['fields']), 'locations': copy.deepcopy(gold['locations']),
            'itemCandidates': copy.deepcopy(gold['items']), 'policies': [dict(zip(['documentId', 'version', 'chunkId'],
                [identity.split(':')[0], int(identity.split(':')[1]), identity.split(':')[2]])) for identity in gold['policyIds']],
            'recommendation': gold['recommendation'], 'calls': [], 'latencyMs': 2}


def test_fixed_corpus_has_distinct_categories_and_honest_synthetic_provenance():
    assert len(CORPUS['cases']) == len({case['id'] for case in CORPUS['cases']}) == 64
    assert len({case['category'] for case in CORPUS['cases']}) == 8
    assert CORPUS['provenance']['humanReviewed'] is False
    assert CORPUS['provenance']['liveOcrMeasured'] is False
    for case in CORPUS['cases']:
        sources = {source['segmentId']: source['text'] for source in case['sources']}
        for span in case['gold']['locations'].values():
            assert 0 <= span['start'] < span['end'] <= len(sources[span['segmentId']])


def test_metric_denominators_include_failed_and_missing_predictions_and_preserve_failed_usage():
    cases = CORPUS['cases'][:3]
    failed = {'caseId': cases[1]['id'], 'error': 'AI_TIMEOUT', 'calls': [{'kind': 'llm', 'inputTokens': 100, 'outputTokens': 0}],
              'latencyMs': 40, 'reservedCalls': 2, 'reservedTokens': 2000, 'attempts': 2}
    result = evaluate_cases(cases, [perfect(cases[0]), failed],
                            {'currency': 'USD', 'inputPerMillion': '1', 'outputPerMillion': '2', 'embeddingPerMillion': '0.1'})
    assert result['cases'] == 3 and result['predictions'] == 2 and result['failures'] == 2
    assert result['fieldAccuracy'] == result['numericAccuracy'] == result['locationAccuracy'] == pytest.approx(1 / 3)
    assert result['calls'] == 1 and result['knownInputTokens'] == 100
    assert result['cost']['estimatedKnownUsage'] == '0.0001'
    assert result['budget'] == dict(observedCases=1, reservedCalls=2, reservedTokens=2000,
                                    retries=1, reservedCallsWithoutReportedUsage=1)
    assert result['semanticUnsupportedClaims'] is result['humanReviewTimeMs'] is None


def test_wrong_policy_versions_and_foreign_predictions_cannot_raise_retrieval_scores():
    case = next(case for case in CORPUS['cases'] if case['gold']['policyIds'])
    prediction = perfect(case); prediction['policies'][0]['version'] = 1
    result = evaluate_cases([case], [prediction])
    assert result['retrieval']['recallAt1'] == result['returnedPolicyVersionAccuracy'] == 0
    with pytest.raises(ValueError): evaluate_cases([case], [prediction, prediction])
    with pytest.raises(ValueError): evaluate_cases([case], [{'caseId': 'foreign'}])


def test_literal_baseline_executes_without_provider_calls_and_does_not_copy_gold_labels():
    predictions = [literal_baseline(case) for case in CORPUS['cases']]
    result = evaluate_cases(CORPUS['cases'], predictions)
    assert result['calls'] == 0 and result['cost'] is None
    assert 0 < result['fieldAccuracy'] < 1
    assert result['retrieval']['recallAt1'] == 0
    altered = copy.deepcopy(CORPUS['cases'][0]); altered['gold']['recommendation'] = 'REJECTION_REVIEW'
    assert literal_baseline(altered)['recommendation'] == predictions[0]['recommendation']
    assert all(prediction['latencyMs'] >= 0 for prediction in predictions)


@pytest.mark.parametrize('invalid', [{'reservedCalls': 1}, {'reservedCalls': 6, 'reservedTokens': 1, 'attempts': 1},
                                    {'reservedCalls': 0, 'reservedTokens': 0, 'attempts': True}])
def test_partial_or_invalid_budgets_are_not_inferred_as_zero(invalid):
    case = CORPUS['cases'][0]
    with pytest.raises(ValueError): evaluate_cases([case], [dict(perfect(case), **invalid)])
