"""Pinned SDK builder/checkpointer. Application owns stages, budgets and branch policy."""
from __future__ import annotations
import json
import threading
import re
from typing import TypedDict

from langgraph.checkpoint.base import BaseCheckpointSaver, CheckpointTuple, WRITES_IDX_MAP
from langgraph.errors import GraphRecursionError
from langgraph.graph import StateGraph, START, END
from langgraph.types import interrupt, Command

from ai_worker.application.execution import WorkerFailure, keys, uuid, digest
from ai_worker.application.graph_contract import GraphVersions
from ai_worker.infrastructure.graph_serializer import GraphCheckpointSerializer


class State(TypedDict, total=False):
    graphExecutionId:str
    contextHash:str
    executionStageRef:str
    documentStageRef:str
    mappingStageRef:str
    evidenceStageRef:str
    resolutionStageRef:str
    reviewRef:str


class CoreCheckpointer(BaseCheckpointSaver):
    def __init__(self, session):
        super().__init__(serde=GraphCheckpointSerializer())
        self.session=session
        self.lock=threading.RLock()

    def _config(self, config):
        configurable=config.get("configurable",{})
        if configurable.get("thread_id") != self.session.request.run_id or configurable.get("checkpoint_ns","") != "":
            raise WorkerFailure("INVALID_PROTOCOL")
        checkpoint=configurable.get("checkpoint_id")
        if checkpoint is not None:uuid(checkpoint)
        return checkpoint

    def _encoded(self, value):return json.loads(self.serde.dumps_typed(value)[1])
    def _decoded(self, value):return self.serde.loads_typed((self.serde.TYPE,json.dumps(value,ensure_ascii=False,separators=(",", ":")).encode()))
    def _config_id(self, checkpoint):
        return {"configurable":{"thread_id":self.session.request.run_id,"checkpoint_ns":"","checkpoint_id":checkpoint}}
    def _read(self, checkpoint):return self.session.core.read(self.session.request,self.session.token,checkpoint)

    def get_tuple(self, config):
        with self.lock:
            saved=self._read(self._config(config))
            if saved is None:return None
            keys(saved,{"id","parentId","hash","envelope","writes"});uuid(saved["id"]);digest(saved["hash"])
            envelope=saved["envelope"];versions=GraphVersions()
            if envelope["threadId"] != self.session.request.run_id or envelope["checkpointId"] != saved["id"] \
                    or envelope["graphVersion"] != versions.graph or envelope["serializerVersion"] != versions.serializer \
                    or envelope["checkpointSchema"] != versions.checkpoint_schema or envelope["parentId"] != saved["parentId"]:
                raise WorkerFailure("INVALID_PROTOCOL")
            writes=[]
            for w in saved["writes"]:
                uuid(w["taskId"]);digest(w["hash"])
                writes.append((w["taskId"],w["channel"],self._decoded(w["payload"])))
            return CheckpointTuple(config=self._config_id(saved["id"]),checkpoint=self._decoded(envelope["body"]),
                metadata=envelope["metadata"],parent_config=self._config_id(saved["parentId"]) if saved["parentId"] else None,pending_writes=writes)

    def put(self, config, checkpoint, metadata, new_versions):
        with self.lock:
            parent=self._config(config);self.session.active();versions=GraphVersions()
            if checkpoint.get("v") != versions.checkpoint_schema:raise WorkerFailure("INVALID_PROTOCOL")
            # SDK execution metadata includes runtime-only values. Persist the pinned closed fields only.
            if set(metadata)-{"source","step","parents"}:raise WorkerFailure("INVALID_PROTOCOL")
            envelope={"threadId":self.session.request.run_id,"graphVersion":versions.graph,"serializerVersion":versions.serializer,
                "checkpointSchema":versions.checkpoint_schema,"checkpointId":uuid(checkpoint["id"]),"parentId":parent,
                "body":self._encoded(checkpoint),"metadata":dict(metadata),"newVersions":dict(new_versions)}
            reply=keys(self.session.core.checkpoint(self.session.request,self.session.token,envelope),{"disposition","hash"});digest(reply["hash"])
            if reply["disposition"] not in {"ACCEPTED","REPLAYED"}:raise WorkerFailure("INVALID_PROTOCOL")
            return self._config_id(checkpoint["id"])

    def put_writes(self, config, writes, task_id, task_path=""):
        with self.lock:
            checkpoint=self._config(config);uuid(task_id);self.session.active()
            # Only root graph tasks exist. Their path is SDK execution metadata, not a subgraph namespace.
            if task_path and not re.fullmatch(r"~__pregel_pull, (__start__|execution|document|mapping|human|evidence|resolution)",task_path):raise WorkerFailure("INVALID_PROTOCOL")
            saved=self._read(checkpoint)
            if saved is None:raise WorkerFailure("INVALID_PROTOCOL")
            latest={(w["taskId"],w["index"]):w for w in saved["writes"]}
            commands=[]
            for idx,(channel,value) in enumerate(writes):
                index=WRITES_IDX_MAP.get(channel,idx)
                if channel=="__error__":value="GRAPH_SDK_FAILED"
                payload=self._encoded(value);old=latest.get((task_id,index))
                if old and index>=0:
                    if old["channel"] != channel or old["payload"] != payload:raise WorkerFailure("INVALID_PROTOCOL")
                    continue
                same=old is not None and old["channel"]==channel and old["payload"]==payload
                commands.append({"checkpointId":checkpoint,"taskId":task_id,"index":index,
                    "version":old["version"] if same else old["version"]+1 if old else 1,
                    "previousHash":old.get("previousHash") if same else old["hash"] if old else None,
                    "channel":channel,"taskPath":task_path,"payload":payload})
            if commands:
                replies=self.session.core.writes(self.session.request,self.session.token,commands)
                if len(replies)!=len(commands):raise WorkerFailure("INVALID_PROTOCOL")
                for reply in replies:
                    keys(reply,{"disposition","hash"});digest(reply["hash"])
                    if reply["disposition"] not in {"ACCEPTED","REPLAYED"}:raise WorkerFailure("INVALID_PROTOCOL")

    def waiting(self, interrupt_id):
        with self.lock:
            saved=self._read(None)
            matches=[w for w in saved["writes"] if w["channel"]=="__interrupt__" and w["index"]==-3
                     and self._decoded(w["payload"])[0].id==interrupt_id]
            if len(matches)!=1:raise WorkerFailure("INVALID_PROTOCOL")
            w=matches[0]
            proof={"interruptId":interrupt_id,"checkpointId":saved["id"],"checkpointHash":saved["hash"],
                "taskId":w["taskId"],"writeVersion":w["version"],"writeHash":w["hash"]}
            self.session.active();reply=self.session.core.waiting(self.session.request,self.session.token,proof)
            if reply != {**proof,"reviewVersion":1}:raise WorkerFailure("INVALID_PROTOCOL")
            return reply


class LangGraphRuntime:
    def execute(self, session):
        saver=CoreCheckpointer(session)
        builder=StateGraph(State)
        for name,key in (("execution","executionStageRef"),("document","documentStageRef"),("mapping","mappingStageRef"),
                         ("evidence","evidenceStageRef"),("resolution","resolutionStageRef")):
            def node(state, operation=name, ref=key):
                session.active();return {ref:getattr(session,operation)()}
            builder.add_node(name,node)
        def human(state):
            # On resume the SDK starts this node again. No I/O, model calls or reservations here.
            result=interrupt(session.interruption())
            if not isinstance(result,dict) or set(result)!={"reviewRef"}:raise WorkerFailure("INVALID_PROTOCOL")
            return {"reviewRef":uuid(result["reviewRef"])}
        builder.add_node("human",human)
        builder.add_edge(START,"execution").add_edge("execution","document").add_edge("document","mapping")
        builder.add_conditional_edges("mapping",lambda state:"human" if session.interruption()["reasonCodes"] else "evidence")
        builder.add_edge("human","evidence").add_edge("evidence","resolution").add_edge("resolution",END)
        graph=builder.compile(checkpointer=saver)
        config={"configurable":{"thread_id":session.request.run_id,"checkpoint_ns":""},"recursion_limit":32}
        try:
            existing=saver.get_tuple(config)
            incoming=None if existing else {"graphExecutionId":session.request.run_id,"contextHash":session.request.context_hash}
            if session.resume is not None:
                if existing is None:raise WorkerFailure("INVALID_PROTOCOL")
                proof=session.resume
                original=saver._read(proof["checkpointId"])
                if original["hash"]!=proof["checkpointHash"]:raise WorkerFailure("INVALID_PROTOCOL")
                if existing.config["configurable"]["checkpoint_id"]==proof["checkpointId"]:
                    # A saved __resume__ slot alone does not prove the human task completed.
                    # Reapply the same immutable command until the exact review output is durable.
                    if not any(channel=="reviewRef" and value==proof["reviewRef"] for _,channel,value in existing.pending_writes):
                        incoming=Command(resume={proof["interruptId"]:{"reviewRef":proof["reviewRef"]}})
                        config=saver._config_id(proof["checkpointId"])|{"recursion_limit":32}
                    # None + explicit checkpoint_id means SDK time travel. Once the exact
                    # human output is durable, continue the already-validated current head.
            result=graph.invoke(incoming,config,durability="sync")
            interruptions=result.get("__interrupt__",())
            if interruptions:
                if session.resume is not None:raise WorkerFailure("GRAPH_REPEATED_INTERRUPT")
                if len(interruptions)!=1:raise WorkerFailure("GRAPH_LIMIT")
                return {"disposition":"WAITING_HUMAN","waiting":saver.waiting(interruptions[0].id)}
            return {"disposition":"FINISHED"}
        except GraphRecursionError as exc:raise WorkerFailure("GRAPH_LIMIT") from exc
        except (WorkerFailure,):raise
        except Exception as exc:
            from ai_worker.domain.advisory import AdvisoryFailure
            if isinstance(exc,AdvisoryFailure):raise
            raise WorkerFailure("GRAPH_SDK_FAILED") from exc
