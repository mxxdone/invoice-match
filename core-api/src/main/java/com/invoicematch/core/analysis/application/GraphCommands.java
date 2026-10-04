package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/** SDK-independent machine DTOs. JSON values use the pinned closed serializer. */
public final class GraphCommands {
    private GraphCommands() {}
    public record Checkpoint(UUID threadId, String graphVersion, String serializerVersion, int checkpointSchema,
            UUID checkpointId, UUID parentId, JsonNode body, JsonNode metadata, JsonNode newVersions) {}
    public record Write(UUID checkpointId, UUID taskId, int index, int version, String previousHash,
            String channel, String taskPath, JsonNode payload) {}
    public record Wait(UUID checkpointId, String checkpointHash, UUID taskId, int writeVersion,
            String writeHash, String interruptId) {}
}
