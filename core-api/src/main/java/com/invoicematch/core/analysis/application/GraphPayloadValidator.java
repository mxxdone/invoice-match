package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.GraphRun;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Validates the closed SDK JSON wire without deserializing any Python object. */
@Component
public class GraphPayloadValidator {
    private static final Set<String> STATE = Set.of("graphExecutionId","contextHash","documentStageRef",
            "mappingStageRef","evidenceStageRef","resolutionStageRef","executionStageRef","reviewRef");
    private final ObjectMapper mapper;
    public GraphPayloadValidator(ObjectMapper mapper) { this.mapper=mapper; }

    public void checkpoint(GraphRun run, GraphCommands.Checkpoint command) {
        if (command==null || !run.id().equals(command.threadId()) || !GraphRun.GRAPH.equals(command.graphVersion())
                || !GraphRun.SERIALIZER.equals(command.serializerVersion()) || command.checkpointSchema()!=GraphRun.SCHEMA
                || command.checkpointId()==null || command.checkpointId().equals(command.parentId())) throw invalid();
        var body=decode(command.body(),0,false);
        keys(body,"v","id","ts","channel_values","channel_versions","versions_seen","updated_channels");
        if (!body.path("v").isInt() || body.path("v").asInt()!=GraphRun.SCHEMA
                || !command.checkpointId().toString().equals(body.path("id").asText())) throw invalid();
        try { Instant.parse(text(body,"ts")); } catch (RuntimeException e) { throw invalid(); }
        var values=body.path("channel_values");
        if (!values.isObject() || values.size()>32) throw invalid();
        values.properties().forEach(e-> {
            if (e.getKey().equals("__start__")) state(run,e.getValue(),true);
            else if (STATE.contains(e.getKey())) stateValue(run,e.getKey(),e.getValue());
            else if (!e.getKey().matches("branch:to:[a-z_]{1,48}") || !e.getValue().isNull()) throw invalid();
        });
        versions(body.path("channel_versions"));
        var seen=body.path("versions_seen");
        if (!seen.isObject() || seen.size()>32) throw invalid();
        seen.properties().forEach(e->{ name(e.getKey());versions(e.getValue()); });
        if (!body.path("updated_channels").isNull()) {
            if (!body.path("updated_channels").isArray() || body.path("updated_channels").size()>32) throw invalid();
            body.path("updated_channels").forEach(v->{if(!v.isTextual())throw invalid();name(v.asText());});
        }
        keys(command.metadata(),"source","step","parents");
        if (!Set.of("input","loop").contains(text(command.metadata(),"source"))
                || !command.metadata().path("step").isInt()
                || !command.metadata().path("parents").isObject() || !command.metadata().path("parents").isEmpty()) throw invalid();
        versions(command.newVersions());
        command.newVersions().properties().forEach(e->{if(!e.getValue().equals(body.path("channel_versions").path(e.getKey())))throw invalid();});
    }

    public void write(GraphRun run, GraphCommands.Write command) {
        if (command==null || command.checkpointId()==null || command.taskId()==null || command.version()<1
                || command.version()>512 || command.index() < -4 || command.index()>511 || !"".equals(command.taskPath())
                || (command.version()==1 && command.previousHash()!=null)
                || (command.version()>1 && (command.index()>=0 || !hash(command.previousHash())))) throw invalid();
        var value=decode(command.payload(),0,true);
        String channel=command.channel();
        if (channel==null) throw invalid();
        switch(channel) {
            case "__interrupt__" -> {
                if(command.index()!=-3 || !command.payload().path(0).asText().equals("tuple")
                        || !value.isArray() || value.size()!=1
                        || !command.payload().path(1).path(0).path(0).asText().equals("interrupt")) throw invalid();
                var interruption=value.get(0);keys(interruption,"id","value");
                if(!text(interruption,"id").matches("[0-9a-f]{32,64}"))throw invalid();
                var request=interruption.path("value");
                if(!request.isObject() || !request.has("graphExecutionId") || !request.has("reasonCodes"))throw invalid();
                request.properties().forEach(e->{
                    if(e.getKey().equals("reasonCodes")) {
                        if(!e.getValue().isArray() || e.getValue().isEmpty() || e.getValue().size()>3)throw invalid();
                        e.getValue().forEach(v->{if(!v.isTextual() || !Set.of("AMBIGUOUS_ITEM","NO_ITEM_CANDIDATE","DOCUMENT_REVIEW_REQUIRED").contains(v.asText()))throw invalid();});
                    } else if(Set.of("graphExecutionId","documentStageRef","mappingStageRef").contains(e.getKey()))
                        stateValue(run,e.getKey(),e.getValue());
                    else throw invalid();
                });
            }
            case "__resume__" -> {
                if(command.index()!=-4 || !value.isArray() || value.size()!=1 || !value.get(0).has("reviewRef"))throw invalid();
                state(run,value.get(0),false);
            }
            case "__error__" -> { if(command.index()!=-1 || !value.isTextual() || !value.asText().matches("[A-Z_]{1,64}"))throw invalid(); }
            case "__scheduled__" -> { if(command.index()!=-2 || !value.isBoolean())throw invalid(); }
            default -> {
                if(command.index()<0)throw invalid();
                if(STATE.contains(channel))stateValue(run,channel,value);
                else if(!channel.matches("branch:to:[a-z_]{1,48}") || !value.isNull())throw invalid();
            }
        }
    }
    public String interruptId(JsonNode payload) { return decode(payload,0,true).path(0).path("id").asText(); }
    public JsonNode decode(JsonNode encoded, int depth, boolean allowInterrupt) {
        if(depth>64 || encoded==null || !encoded.isArray() || encoded.size()!=2 || !encoded.get(0).isTextual())throw invalid();
        String tag=encoded.get(0).asText();var body=encoded.get(1);
        switch(tag) {
            case "scalar":
                if(body.isNull() || body.isTextual() || body.isBoolean() || body.isIntegralNumber()
                        || (body.isFloatingPointNumber() && Double.isFinite(body.doubleValue())))return body;
                break;
            case "list", "tuple":
                if(!body.isArray())break;
                var array=mapper.createArrayNode();body.forEach(v->array.add(decode(v,depth+1,allowInterrupt)));return array;
            case "dict":
                if(!body.isObject())break;
                var object=mapper.createObjectNode();body.properties().forEach(e->object.set(e.getKey(),decode(e.getValue(),depth+1,allowInterrupt)));return object;
            case "interrupt":
                if(!allowInterrupt)break;
                keys(body,"id","value");
                var interrupt=mapper.createObjectNode().put("id",text(body,"id"));
                interrupt.set("value",decode(body.get("value"),depth+1,false));return interrupt;
            default: break;
        }
        throw invalid();
    }
    private void state(GraphRun run, JsonNode state, boolean requireIdentity) {
        if(!state.isObject() || state.isEmpty() || (requireIdentity && !state.has("graphExecutionId")))throw invalid();
        state.properties().forEach(e->{if(!STATE.contains(e.getKey()))throw invalid();stateValue(run,e.getKey(),e.getValue());});
    }
    private void stateValue(GraphRun run, String key, JsonNode value) {
        if(!value.isTextual())throw invalid();
        if(key.equals("graphExecutionId")) {if(!value.asText().equals(run.id().toString()))throw invalid();}
        else if(key.equals("contextHash")) {if(!value.asText().equals(run.contextHash()))throw invalid();}
        else uuid(value.asText());
    }
    private void versions(JsonNode versions) {
        if(versions==null || !versions.isObject() || versions.size()>32)throw invalid();
        versions.properties().forEach(e->{
            name(e.getKey());var v=e.getValue();
            if(!v.isIntegralNumber() && !(v.isTextual() && v.asText().matches("[0-9.]{1,100}")))throw invalid();
        });
    }
    private static void name(String name) { if(!name.matches("[a-zA-Z0-9_:.-]{1,80}"))throw invalid(); }
    public static void keys(JsonNode n, String... expected) {
        if(n==null || !n.isObject())throw invalid();Set<String> found=new HashSet<>();n.fieldNames().forEachRemaining(found::add);
        if(!found.equals(Set.of(expected)))throw invalid();
    }
    public static String text(JsonNode n, String field) {if(!n.path(field).isTextual() || n.path(field).asText().isBlank())throw invalid();return n.path(field).asText();}
    public static boolean hash(String value) {return value!=null && value.matches("[0-9a-f]{64}");}
    public static UUID uuid(String value) {try {var id=UUID.fromString(value);if(!id.toString().equals(value))throw invalid();return id;}catch(IllegalArgumentException e){throw invalid();}}
    static AnalysisValidationException invalid() {return new AnalysisValidationException("GRAPH_SCHEMA_INVALID","Invalid graph checkpoint request");}
}
