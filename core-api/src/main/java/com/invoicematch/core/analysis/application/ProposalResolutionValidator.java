package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static com.invoicematch.core.analysis.application.ProposalStageValidator.*;

/** Core facts and evidence identity are authoritative; a model supplies only a draft. */
final class ProposalResolutionValidator {
    private static final Set<String> ACTIONS=Set.of("APPROVAL_REVIEW","SUPPLEMENT_REQUEST","REJECTION_REVIEW","REVIEW_REQUIRED","INSUFFICIENT_EVIDENCE");
    private static final Set<String> WARNINGS=Set.of("POLICY_CONFLICT","MAPPING_REVIEW","DOCUMENT_REVIEW","INSUFFICIENT_EVIDENCE");
    private ProposalResolutionValidator() {}
    static JsonNode step(String stage,List<ProposalStore.Step> steps,ProposalSourceCatalog catalog) {
        var stored=steps.stream().filter(s->s.stage().equals(stage)).findFirst().orElseThrow(ProposalSourceCatalog::invalid);
        var payload=catalog.parse(stored.payload());
        if(!AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(payload)).equals(stored.hash())) throw ProposalSourceCatalog.invalid();
        return payload;
    }
    static ObjectNode facts(JsonNode context) {
        var facts=new ObjectMapper().createObjectNode();long total=0;Set<Integer> numbers=new HashSet<>();
        array(context.path("matchResult").path("lineOutcomes"),0,100);
        try {
            for(var line:context.path("matchResult").path("lineOutcomes")) {
                int number=integer(line.get("lineNumber"),1,100);
                if(!numbers.add(number)) throw ProposalSourceCatalog.invalid();
                for(String name:List.of("invoiceQuantity","invoiceUnitPrice","availableConfirmedQuantity","plannedQuantity")) {
                    long value=nonNegative(line.get(name));
                    facts.putObject("line:"+number+":"+name).put("value",value).put("unit",name.equals("invoiceUnitPrice")?"KRW":"EA");
                }
                long amount=Math.multiplyExact(nonNegative(line.get("invoiceQuantity")),nonNegative(line.get("invoiceUnitPrice")));
                facts.putObject("line:"+number+":invoiceAmount").put("value",amount).put("unit","KRW");total=Math.addExact(total,amount);
            }
        } catch(ArithmeticException e) { throw ProposalSourceCatalog.invalid(); }
        facts.putObject("invoiceTotal").put("value",total).put("unit","KRW");return facts;
    }
    private static long nonNegative(JsonNode n) {
        if(n==null || !n.isIntegralNumber() || !n.canConvertToLong() || n.asLong()<0) throw ProposalSourceCatalog.invalid();return n.asLong();
    }
    static JsonNode evidence(JsonNode payload,ProposalRun run,List<ProposalStore.Step> steps,ProposalSourceCatalog catalog) {
        keys(payload,"schemaVersion","calls","result");array(payload.path("calls"),0,0);
        if(!payload.path("schemaVersion").asText().equals("ai-evidence-stage-v1")) throw ProposalSourceCatalog.invalid();
        var result=payload.path("result");keys(result,"status","toolRequestId");
        boolean normal=catalog.context(run).path("matchResult").path("normal").asBoolean();
        if(normal) {
            if(!result.path("status").asText().equals("NOT_REQUIRED") || !result.path("toolRequestId").isNull()) throw ProposalSourceCatalog.invalid();
            var empty=new ObjectMapper().createObjectNode().put("status","NOT_REQUIRED");empty.putArray("result");return empty;
        }
        String request=text(result.get("toolRequestId"),36);
        try { if(!UUID.fromString(request).toString().equals(request)) throw ProposalSourceCatalog.invalid(); }
        catch(IllegalArgumentException e) { throw ProposalSourceCatalog.invalid(); }
        var retrieved=step("tool:"+request,steps,catalog);
        if(!retrieved.path("schemaVersion").asText().equals("ai-evidence-v1")
                || !retrieved.path("contextHash").asText().equals(run.contextHash())
                || !retrieved.path("request").path("requestId").asText().equals(request)
                || !Set.of("FOUND","CONFLICT","INSUFFICIENT_EVIDENCE").contains(retrieved.path("status").asText())
                || !retrieved.path("status").equals(result.path("status"))) throw ProposalSourceCatalog.invalid();
        return retrieved;
    }
    static Set<String> gates(JsonNode document,JsonNode mapping,JsonNode evidence) {
        Set<String> gates=new HashSet<>();var d=document.path("result");
        if(d.path("fields").isEmpty() && d.path("lines").isEmpty()) gates.add("DOCUMENT_REVIEW");
        for(var warning:d.path("warnings")) if(Set.of("DOCUMENT_CONFLICT","AMBIGUOUS_LAYOUT").contains(warning.asText())) gates.add("DOCUMENT_REVIEW");
        for(var line:mapping.path("result").path("lines")) {
            if(line.path("reviewRequired").asBoolean()) gates.add("MAPPING_REVIEW");
            for(var c:line.path("candidates")) for(var reason:c.path("reasonCodes")) if(reason.asText().equals("ALIAS")) gates.add("MAPPING_REVIEW");
        }
        if(evidence.path("status").asText().equals("CONFLICT")) gates.add("POLICY_CONFLICT");
        if(evidence.path("status").asText().equals("INSUFFICIENT_EVIDENCE")) gates.add("INSUFFICIENT_EVIDENCE");return gates;
    }
    static void validate(String stage,JsonNode n,ProposalRun run,List<ProposalStore.Step> steps,ProposalSourceCatalog catalog) {
        if(stage.equals("evidence")) { evidence(n,run,steps,catalog);return; }
        keys(n,"schemaVersion","promptVersion","calls","result");
        if(!n.path("schemaVersion").asText().equals("ai-resolution-v1") || !n.path("promptVersion").asText().equals("invoice-advisory-1")) throw ProposalSourceCatalog.invalid();
        var document=step("document",steps,catalog);var mapping=step("mapping",steps,catalog);
        var proof=evidence(step("evidence",steps,catalog),run,steps,catalog);var gates=gates(document,mapping,proof);
        if(gates.isEmpty()) calls(n.path("calls"),run,1);else array(n.path("calls"),0,0);
        var value=n.path("result");keys(value,"recommendation","summary","factIds","citations","warnings");
        String action=text(value.get("recommendation"),40),summary=text(value.get("summary"),500);
        if(!ACTIONS.contains(action) || summary.codePoints().anyMatch(c->Character.isDigit(c) || Character.getType(c)==Character.OTHER_NUMBER || Character.getType(c)==Character.LETTER_NUMBER)) throw ProposalSourceCatalog.invalid();
        Set<String> warnings=new HashSet<>();array(value.path("warnings"),0,4);
        for(var w:value.path("warnings")) if(!WARNINGS.contains(text(w,40)) || !warnings.add(w.asText())) throw ProposalSourceCatalog.invalid();
        if(!warnings.containsAll(gates) || (!warnings.isEmpty() && !Set.of("REVIEW_REQUIRED","INSUFFICIENT_EVIDENCE").contains(action))) throw ProposalSourceCatalog.invalid();
        boolean normal=catalog.context(run).path("matchResult").path("normal").asBoolean();
        if(action.equals("APPROVAL_REVIEW") && (!normal || !warnings.isEmpty())) throw ProposalSourceCatalog.invalid();
        if(action.equals("INSUFFICIENT_EVIDENCE") && !warnings.contains("INSUFFICIENT_EVIDENCE")) throw ProposalSourceCatalog.invalid();
        var facts=facts(catalog.context(run));Set<String> seenFacts=new HashSet<>();array(value.path("factIds"),0,20);
        for(var id:value.path("factIds")) if(!facts.has(text(id,100)) || !seenFacts.add(id.asText())) throw ProposalSourceCatalog.invalid();
        Set<String> citations=new HashSet<>();array(value.path("citations"),0,10);
        for(var citation:value.path("citations")) {
            keys(citation,"chunkId","documentId","documentVersion","page","paragraph","start","end","quote");
            String chunkId=text(citation.get("chunkId"),36);
            var chunk=java.util.stream.StreamSupport.stream(proof.path("result").spliterator(),false)
                    .filter(c->c.path("chunkId").asText().equals(chunkId)).findFirst().orElseThrow(ProposalSourceCatalog::invalid);
            if(!citations.add(chunkId) || !text(citation.get("documentId"),36).equals(chunk.path("documentId").asText())) throw ProposalSourceCatalog.invalid();
            for(String key:List.of("documentVersion","page","paragraph")) if(integer(citation.get(key),1,Integer.MAX_VALUE)!=chunk.path(key).asInt()) throw ProposalSourceCatalog.invalid();
            String source=chunk.path("text").asText();int length=source.codePointCount(0,source.length());
            int start=integer(citation.get("start"),0,length),end=integer(citation.get("end"),1,length);
            if(start>=end || end-start>500 || !text(citation.get("quote"),1000).equals(source.substring(source.offsetByCodePoints(0,start),source.offsetByCodePoints(0,end)))) throw ProposalSourceCatalog.invalid();
        }
        if(!normal && Set.of("SUPPLEMENT_REQUEST","REJECTION_REVIEW").contains(action) && citations.isEmpty()) throw ProposalSourceCatalog.invalid();
    }
}
