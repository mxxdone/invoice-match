package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.persistence.AnalysisExecutionStore;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Freezes verified parser output and bounded business facts, never storage keys. */
@Component
public class ProposalContextFactory {
    private final ProposalStore store;
    private final AnalysisExecutionStore parserRuns;
    private final AnalysisManifestVerifier manifest;
    private final AnalysisResultValidator validator;
    private final ObjectMapper mapper;
    public ProposalContextFactory(ProposalStore store, AnalysisExecutionStore parserRuns,
            AnalysisManifestVerifier manifest, AnalysisResultValidator validator, ObjectMapper mapper) {
        this.store=store; this.parserRuns=parserRuns; this.manifest=manifest; this.validator=validator; this.mapper=mapper;
    }
    public ObjectNode create(UUID caseId, ProposalStore.Source source) {
        var run=parserRuns.lockByRunId(source.parserRunId()).orElseThrow(()->conflict());
        var documents=manifest.verify(run);
        var parsed=store.parsed(source.parserRunId());
        if(parsed.size()!=documents.size()) throw conflict();
        var root=mapper.createObjectNode().put("schemaVersion","ai-context-v1").put("caseId",caseId.toString())
                .put("parserRunId",source.parserRunId().toString()).put("matchResultId",source.matchId().toString())
                .put("evidencePayloadHash",source.bundleHash()).put("matchResultHash",source.matchHash())
                .put("companyId","company-demo").put("applicableDate",source.submittedAt().atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate().toString());
        var bundle=parse(source.bundlePayload()); var match=parse(source.matchPayload());
        if(!source.purchasingHash().equals(match.path("purchasingSnapshot").path("payloadHash").asText())
                || !source.bundleId().toString().equals(match.path("evidenceBundle").path("id").asText())
                || !source.bundleHash().equals(match.path("evidenceBundle").path("payloadHash").asText())) throw conflict();
        root.set("evidenceBundle",bundle); root.set("matchResult",match);
        var results=root.putArray("documents");
        for(var result:parsed) {
            var document=documents.stream().filter(d->d.documentId().equals(result.documentId())).findFirst().orElseThrow(()->conflict());
            var payload=parse(result.payload());
            var verified=validator.validate(new AnalysisDocumentResultCommand(null,run.inputVersion(),run.evidencePayloadHash(),
                    document.documentId(),"SUCCESS",payload,null),document);
            if(!document.checksum().equals(result.checksum()) || !verified.payloadHash().equals(result.payloadHash())) throw conflict();
            var entry=results.addObject().put("documentId",result.documentId().toString()).put("checksum",result.checksum())
                    .put("parserPayloadHash",result.payloadHash());
            entry.set("parsed",payload);
        }
        var items=store.items(caseId);
        if(items.size()>100) throw new AnalysisValidationException("AI_INPUT_LIMIT","Too many purchase order candidates");
        root.set("items",mapper.valueToTree(items));
        if(AnalysisCanonicalJson.canonicalize(root).getBytes(StandardCharsets.UTF_8).length>200000)
            throw new AnalysisValidationException("AI_INPUT_LIMIT","Frozen analysis context exceeds the limit");
        return root;
    }
    private com.fasterxml.jackson.databind.JsonNode parse(String value) {
        try { return mapper.readTree(value); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw conflict(); }
    }
    private static AnalysisConflictException conflict() {
        return new AnalysisConflictException("AI_INPUT_CONFLICT","Current verified parser and match inputs are required");
    }
}
