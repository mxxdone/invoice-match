"""Offline ranking metrics. Ground truth comes from the caller, never model output."""
from __future__ import annotations


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
