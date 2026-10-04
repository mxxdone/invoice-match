"""Offline comparison only; never connects to a provider or writes business data."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'ai-worker' / 'src'))
from ai_worker.application.evaluation import evaluate_cases, literal_baseline


def read_json(path: Path):
    with path.open('rb') as handle:
        raw = handle.read(5 * 1024 * 1024 + 1)
    if len(raw) > 5 * 1024 * 1024:
        raise ValueError('Evaluation input exceeds limit')
    return raw, json.loads(raw)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--corpus', type=Path, default=ROOT / 'ai-worker/evaluation/cases.json')
    parser.add_argument('--ai-predictions', type=Path, help='Normalized measured predictions; failures must be retained')
    parser.add_argument('--output', type=Path, default=ROOT / 'output/p3/evaluation/offline.json')
    args = parser.parse_args()
    try:
        raw, corpus = read_json(args.corpus)
        if corpus.get('schemaVersion') != 'advisory-evaluation-v1' or not 60 <= len(corpus['cases']) <= 100:
            raise ValueError('Fixed 60-100 case corpus required')
        baseline = [literal_baseline(case) for case in corpus['cases']]
        report = {'schemaVersion': 'advisory-evaluation-report-v1', 'corpusSha256': hashlib.sha256(raw).hexdigest(),
                  'provenance': corpus['provenance'], 'baseline': evaluate_cases(corpus['cases'], baseline),
                  'ai': None, 'aiMeasurement': 'not supplied; no live API calls performed',
                  'baselineMethod': 'literal labels/item names; no semantic retrieval or OCR'}
        if args.ai_predictions is not None:
            prediction_raw, predictions = read_json(args.ai_predictions)
            report['predictionSha256'] = hashlib.sha256(prediction_raw).hexdigest()
            price_names = ['AI_INPUT_PRICE_PER_MILLION', 'AI_OUTPUT_PRICE_PER_MILLION', 'AI_EMBEDDING_PRICE_PER_MILLION', 'AI_COST_CURRENCY']
            pricing = None
            if any(os.environ.get(name) for name in price_names):
                if not all(os.environ.get(name) for name in price_names):
                    raise ValueError('Complete explicit evaluation pricing required')
                pricing = dict(zip(['inputPerMillion', 'outputPerMillion', 'embeddingPerMillion', 'currency'],
                                   (os.environ[name] for name in price_names)))
            report['ai'] = evaluate_cases(corpus['cases'], predictions, pricing)
            report['aiMeasurement'] = 'provided prediction observations; verify provenance before a live-quality claim'
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=False, separators=(',', ':')))
        return 0
    except (ValueError, OSError, KeyError, TypeError) as error:
        print('Evaluation failed: ' + type(error).__name__, file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
