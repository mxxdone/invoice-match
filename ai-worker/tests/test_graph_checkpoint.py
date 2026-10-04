"""Actual SDK contract; disk fixtures are not a production persistence backend."""

import json
import os
from pathlib import Path
import subprocess
import sys
from dataclasses import replace
from typing import TypedDict

import pytest
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.checkpoint.base import LATEST_VERSION
from langgraph.graph import END, START, StateGraph
from langgraph.types import Command, Interrupt, interrupt

from ai_worker.infrastructure.graph_serializer import GraphCheckpointSerializer
from ai_worker.application.graph_contract import GraphVersions


class State(TypedDict):
    graph_execution_id: str
    stage_ref: str
    review_ref: str


def _run(mode, directory):
    """Each call runs in a separate interpreter and reconstructs only JSON."""
    root = Path(directory)
    codec = GraphCheckpointSerializer()
    saver = InMemorySaver(serde=codec)
    disk = root / "checkpoint.json"
    if disk.exists():
        state = codec.loads_typed((codec.TYPE, disk.read_bytes()))
        for thread, ns, cid, checkpoint, metadata, parent in state["storage"]:
            saver.storage[thread][ns][cid] = (
                codec.dumps_typed(checkpoint), codec.dumps_typed(metadata), parent)
        for outer, inner, task, channel, value, task_path in state["writes"]:
            saver.writes[outer][inner] = (task, channel, codec.dumps_typed(value), task_path)
        for key, kind, value in state["blobs"]:
            saver.blobs[key] = ("empty", b"") if kind == "empty" else codec.dumps_typed(value)

    ledger_path = root / "ledger.json"
    ledger = json.loads(ledger_path.read_text()) if ledger_path.exists() else {
        "calls": 0, "reserved": 0, "visits": 0}

    def save_ledger():
        ledger_path.write_text(json.dumps(ledger))

    def extract(state):
        # Core's immutable stage/reservation is the source of truth on replay.
        if "stage_ref" not in ledger:
            ledger.update(calls=ledger["calls"] + 1, reserved=ledger["reserved"] + 1,
                          stage_ref="stage-1")
            save_ledger()
        return {"stage_ref": ledger["stage_ref"]}

    def human(state):
        ledger["visits"] += 1
        save_ledger()
        review = interrupt({"stageRef": state["stage_ref"]})
        return {"review_ref": review["reviewRef"]}

    graph = (StateGraph(State).add_node("extract", extract).add_node("human", human)
             .add_edge(START, "extract").add_edge("extract", "human")
             .add_edge("human", END).compile(checkpointer=saver))
    config = {"configurable": {"thread_id": "graph-1"}}
    if mode == "start":
        result = graph.invoke({"graph_execution_id": "graph-1"}, config,
                              durability="sync")
        assert len(result["__interrupt__"]) == 1
        ledger["interrupt_id"] = result["__interrupt__"][0].id
        save_ledger()
    else:
        snapshot = graph.get_state(config)
        assert snapshot.tasks[0].interrupts[0].id == ledger["interrupt_id"]
        result = graph.invoke(Command(resume={ledger["interrupt_id"]: {
            "reviewRef": "review-1"}}), config, durability="sync")
        assert result["review_ref"] == "review-1"
        assert graph.get_state(config).next == ()

    records = {
        "storage": [(thread, ns, cid, codec.loads_typed(c), codec.loads_typed(m), parent)
                    for thread, namespaces in saver.storage.items()
                    for ns, checkpoints in namespaces.items()
                    for cid, (c, m, parent) in checkpoints.items()],
        "writes": [(outer, inner, task, channel, codec.loads_typed(value), task_path)
                   for outer, writes in saver.writes.items()
                   for inner, (task, channel, value, task_path) in writes.items()],
        "blobs": [(key, value[0], None if value[0] == "empty" else codec.loads_typed(value))
                  for key, value in saver.blobs.items()],
    }
    disk.write_bytes(codec.dumps_typed(records)[1])


def test_interrupt_restores_in_new_process_without_recalling_successful_stage(tmp_path):
    env = dict(os.environ, PYTHONPATH=str(Path(__file__).parents[1] / "src"),
               LANGCHAIN_TRACING_V2="false", LANGSMITH_TRACING="false")
    for mode in ("start", "resume"):
        subprocess.run([sys.executable, __file__, mode, str(tmp_path)],
                       env=env, check=True, timeout=30)
    ledger = json.loads((tmp_path / "ledger.json").read_text())
    assert ledger["calls"] == ledger["reserved"] == 1
    assert ledger["visits"] == 2  # SDK restarts the interrupt node.
    payload = (tmp_path / "checkpoint.json").read_text()
    assert "interrupt" in payload and "channel_versions" in payload
    assert "versions_seen" in payload and "parents" in payload


@pytest.mark.parametrize("value", [object(), b"pickle", {1: "key"}, float("nan"),
                                  Interrupt("x", response_schema=str)])
def test_serializer_rejects_non_contract_types(value):
    with pytest.raises(ValueError, match="GRAPH_CHECKPOINT_TYPE"):
        GraphCheckpointSerializer().dumps_typed(value)


@pytest.mark.parametrize("tag", ["pickle", "json", "graph-checkpoint-json-v0"])
def test_unknown_or_old_serializer_is_fail_closed(tag):
    with pytest.raises(ValueError, match="GRAPH_CHECKPOINT_VERSION"):
        GraphCheckpointSerializer().loads_typed((tag, b"{}"))


def test_serializer_roundtrip_collision_size_and_depth():
    codec = GraphCheckpointSerializer()
    value = {"type": "interrupt", "tuple": (Interrupt({"candidateId": "item-1"}, id="i-1"),),
             "nested": [True, None, 12.5], "unicode": "확인"}
    encoded = codec.dumps_typed(value)
    assert codec.dumps_typed(codec.loads_typed(encoded)) == encoded
    with pytest.raises(ValueError, match="GRAPH_CHECKPOINT_SIZE"):
        codec.dumps_typed("x" * codec.MAX_BYTES)
    with pytest.raises(ValueError, match="GRAPH_CHECKPOINT_SIZE"):
        codec.loads_typed((codec.TYPE, b"x" * (codec.MAX_BYTES + 1)))
    deep = None
    for _ in range(codec.MAX_DEPTH + 2):
        deep = [deep]
    with pytest.raises(ValueError, match="GRAPH_CHECKPOINT_DEPTH"):
        codec.dumps_typed(deep)
    with pytest.raises(ValueError, match="GRAPH_CHECKPOINT_TYPE"):
        codec.loads_typed((codec.TYPE, b'["import", "os"]'))


@pytest.mark.parametrize("field,value", [
    ("workflow", "ai-review-v1"), ("workflow", "document-parser-v1"),
    ("graph", "invoice-review-graph-v0"), ("serializer", "graph-checkpoint-json-v0"),
    ("checkpoint_schema", 1), ("checkpoint_schema", 2.0),
])
def test_graph_version_gate_rejects_legacy_and_unknown_without_migration(field, value):
    versions = replace(GraphVersions(), **{field: value})
    with pytest.raises(ValueError, match="GRAPH_VERSION_UNSUPPORTED"):
        versions.require_supported()
    assert getattr(versions, field) == value


def test_pinned_sdk_checkpoint_schema_matches_version_gate():
    GraphVersions().require_supported()
    assert LATEST_VERSION == GraphVersions().checkpoint_schema


if __name__ == "__main__":
    _run(sys.argv[1], sys.argv[2])
