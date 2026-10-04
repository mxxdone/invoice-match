"""Offline ranking metrics. Ground truth comes from the caller, never model output."""
from __future__ import annotations
import math
import re
from decimal import Decimal
from statistics import median


def ranking_metrics(gold: list[set[str]], predicted: list[list[str]]) -> dict:
    if len(gold) != len(predicted):
        raise ValueError("Every gold row requires a prediction, including failed/empty predictions")
    if any(not isinstance(wanted, set) or any(not isinstance(x, str) or not x for x in wanted) for wanted in gold) \
            or any(not isinstance(rank, list) or any(not isinstance(x, str) or not x for x in rank) for rank in predicted):
        raise ValueError("Invalid ranking or gold IDs")
    labeled = [(wanted, rank) for wanted, rank in zip(gold, predicted) if wanted]
    empty = [rank for wanted, rank in zip(gold, predicted) if not wanted]
    result = {"rows": len(gold), "labeledRows": len(labeled), "noCandidateRows": len(empty)}
    for k in (1, 3):
        result[f"recallAt{k}"] = sum(len(wanted.intersection(rank[:k]))/len(wanted) for wanted, rank in labeled)/len(labeled) if labeled else None
    result["noCandidateAccuracy"] = sum(not rank for rank in empty)/len(empty) if empty else None
    return result


def evaluate_cases(cases: list[dict], predictions: list[dict], pricing: dict | None = None) -> dict:
    """Score normalized observations; absent/failed predictions remain in every gold denominator.

    Numeric strings and source spans are exact. Semantic unsupported claims and human
    review time require separate human measurements and are never inferred here.
    """
    ids = [case['id'] for case in cases]
    if not 1 <= len(ids) <= 100 or len(set(ids)) != len(ids):
        raise ValueError('Unique fixed evaluation cases required')
    observations = {}
    for prediction in predictions:
        identity = prediction.get('caseId')
        if identity not in ids or identity in observations:
            raise ValueError('Duplicate or foreign prediction')
        observations[identity] = prediction
    totals = dict(fields=0, correctFields=0, numeric=0, correctNumeric=0, locations=0,
                  correctLocations=0, extraFields=0, branches=0, failures=0)
    mapping_gold, mapping_pred, retrieval_gold, retrieval_pred = [], [], [], []
    latencies, calls = [], []
    budget = dict(observedCases=0, reservedCalls=0, reservedTokens=0, retries=0,
                  reservedCallsWithoutReportedUsage=0)
    correct_versions = returned_policies = 0
    for case in cases:
        gold = case['gold']; prediction = observations.get(case['id'], {}); observed = prediction
        if not prediction or prediction.get('error'):
            totals['failures'] += 1
            prediction = {}
        fields = prediction.get('fields', {})
        if not isinstance(fields, dict):
            raise ValueError('Invalid field observations')
        totals['fields'] += len(gold['fields'])
        totals['correctFields'] += sum(fields.get(key) == value for key, value in gold['fields'].items())
        totals['extraFields'] += len(set(fields) - set(gold['fields']))
        for key in gold['numericKeys']:
            totals['numeric'] += 1
            totals['correctNumeric'] += fields.get(key) == gold['fields'][key]
        locations = prediction.get('locations', {})
        if not isinstance(locations, dict):
            raise ValueError('Invalid location observations')
        for key, span in gold['locations'].items():
            totals['locations'] += 1
            totals['correctLocations'] += locations.get(key) == span
        candidates = prediction.get('itemCandidates', [])
        if not isinstance(candidates, list) or len(candidates) > len(gold['items']):
            raise ValueError('Unexpected mapping rows')
        for index, wanted in enumerate(gold['items']):
            mapping_gold.append(set(wanted)); mapping_pred.append(candidates[index] if index < len(candidates) else [])
        policies = prediction.get('policies', [])
        if not isinstance(policies, list):
            raise ValueError('Invalid policy observations')
        ranked = []
        for policy in policies:
            if not isinstance(policy, dict) or type(policy.get('version')) is not int or policy['version'] < 1:
                raise ValueError('Invalid policy version')
            ranked.append(f"{policy['documentId']}:{policy['version']}:{policy['chunkId']}")
            returned_policies += 1
            correct_versions += gold['policyVersions'].get(policy['documentId']) == policy['version']
        retrieval_gold.append(set(gold['policyIds'])); retrieval_pred.append(ranked)
        totals['branches'] += prediction.get('recommendation') == gold['recommendation']
        latency = observed.get('latencyMs')
        if latency is not None:
            if type(latency) not in {int, float} or not math.isfinite(latency) or latency < 0:
                raise ValueError('Invalid observed latency')
            latencies.append(latency)
        observed_calls = observed.get('calls', [])
        if not isinstance(observed_calls, list) or len(observed_calls) > 5:
            raise ValueError('Invalid call observations')
        for call in observed_calls:
            if not isinstance(call, dict):
                raise ValueError('Invalid call observations')
            if any(type(call.get(key)) is not int or not 0 <= call[key] <= 40000 for key in ['inputTokens', 'outputTokens']):
                raise ValueError('Invalid observed token usage')
            if call.get('kind') not in {'llm', 'embedding'}:
                raise ValueError('Invalid call kind')
            calls.append(call)
        budget_keys = {'reservedCalls': 5, 'reservedTokens': 40000, 'attempts': 3}
        if any(key in observed for key in budget_keys):
            if any(type(observed.get(key)) is not int or not 0 <= observed[key] <= maximum
                   for key, maximum in budget_keys.items()):
                raise ValueError('Complete cumulative budget observations required')
            if len(observed_calls) > observed['reservedCalls']:
                raise ValueError('Reported calls exceed reservations')
            budget['observedCases'] += 1
            budget['reservedCalls'] += observed['reservedCalls']
            budget['reservedTokens'] += observed['reservedTokens']
            budget['retries'] += max(0, observed['attempts'] - 1)
            budget['reservedCallsWithoutReportedUsage'] += observed['reservedCalls'] - len(observed_calls)
    fraction = lambda correct, total: correct / total if total else None
    retrieval = ranking_metrics(retrieval_gold, retrieval_pred)
    labeled = [(wanted, rank) for wanted, rank in zip(retrieval_gold, retrieval_pred) if wanted]
    retrieval['recallAt5'] = sum(len(wanted.intersection(rank[:5])) / len(wanted) for wanted, rank in labeled) / len(labeled) if labeled else None
    cost = None
    if pricing is not None:
        if pricing.get('currency') not in {'USD', 'KRW'}:
            raise ValueError('Explicit pricing currency required')
        rates = {key: Decimal(str(pricing[key])) for key in ['inputPerMillion', 'outputPerMillion', 'embeddingPerMillion']}
        if any(not rate.is_finite() or rate < 0 for rate in rates.values()):
            raise ValueError('Invalid configured prices')
        estimate = sum((Decimal(call['inputTokens']) * rates['embeddingPerMillion'] if call['kind'] == 'embedding'
                        else Decimal(call['inputTokens']) * rates['inputPerMillion'] + Decimal(call['outputTokens']) * rates['outputPerMillion'])
                       for call in calls) / Decimal(1000000)
        cost = {'currency': pricing['currency'], 'estimatedKnownUsage': str(estimate), 'basis': 'configured token rates; not an invoice'}
    return {'cases': len(cases), 'predictions': len(observations), 'failures': totals['failures'],
            'fieldAccuracy': fraction(totals['correctFields'], totals['fields']), 'fieldDenominator': totals['fields'],
            'numericAccuracy': fraction(totals['correctNumeric'], totals['numeric']), 'numericDenominator': totals['numeric'],
            'locationAccuracy': fraction(totals['correctLocations'], totals['locations']), 'locationDenominator': totals['locations'],
            'extraFields': totals['extraFields'], 'mapping': ranking_metrics(mapping_gold, mapping_pred), 'retrieval': retrieval,
            'returnedPolicyVersionAccuracy': fraction(correct_versions, returned_policies), 'returnedPolicies': returned_policies,
            'branchAccuracy': totals['branches'] / len(cases), 'calls': len(calls),
            'knownInputTokens': sum(call['inputTokens'] for call in calls), 'knownOutputTokens': sum(call['outputTokens'] for call in calls),
            'latencyObservedCases': len(latencies), 'medianLatencyMs': median(latencies) if latencies else None,
            'budget': budget, 'cost': cost, 'semanticUnsupportedClaims': None, 'humanReviewTimeMs': None}


def literal_baseline(case: dict) -> dict:
    """Non-AI extraction baseline: explicit labels, literal item names; no semantic RAG claim."""
    from time import perf_counter
    start = perf_counter()
    fields, locations = {}, {}
    patterns = {'invoiceNumber': r'청구번호\s*:\s*([^\n|]+)', 'supplierName': r'공급사\s*:\s*([^\n|]+)',
                'invoiceDate': r'청구일\s*:\s*([0-9./-]+)', 'currency': r'통화\s*:\s*(KRW|USD|EUR)',
                'line:1:rawItemName': r'품목\s*:\s*([^\n|]+)', 'line:1:quantity': r'수량\s*:\s*([0-9,]+)',
                'line:1:unitPrice': r'단가\s*:\s*(?:₩\s*)?([0-9,]+)'}
    for segment in case['sources']:
        for key, pattern in patterns.items():
            match = re.search(pattern, segment['text'])
            if match and key not in fields:
                raw = match.group(1); value = raw.strip()
                if key in {'line:1:quantity', 'line:1:unitPrice'}:
                    value = str(int(value.replace(',', '')))
                elif key == 'invoiceDate':
                    parts = re.split(r'[./-]', value)
                    value = f'{int(parts[0]):04d}-{int(parts[1]):02d}-{int(parts[2]):02d}'
                fields[key] = value
                left = match.start(1) + len(raw) - len(raw.lstrip()); right = match.end(1) - len(raw) + len(raw.rstrip())
                locations[key] = {'segmentId': segment['segmentId'], 'start': left, 'end': right}
    candidates = [item['id'] for item in case['items'] if item['name'] == fields.get('line:1:rawItemName')]
    return {'caseId': case['id'], 'fields': fields, 'locations': locations, 'itemCandidates': [candidates], 'policies': [],
            'recommendation': 'APPROVAL_REVIEW' if case['normal'] and len(candidates) == 1 else 'REVIEW_REQUIRED',
            'calls': [], 'reservedCalls': 0, 'reservedTokens': 0, 'attempts': 0,
            'latencyMs': (perf_counter() - start) * 1000}
