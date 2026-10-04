package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Source IDs are derived from frozen parser output and validated OCR checkpoints. */
@Component
public class ProposalSourceCatalog {
    private final ObjectMapper mapper;
    public ProposalSourceCatalog(ObjectMapper mapper) { this.mapper=mapper; }
    public record Segment(String id,String documentId,String text,String origin,Integer page,Integer sheet,String cell) {}
    public JsonNode context(ProposalRun run) {
        JsonNode node=parse(run.context());
        if(!AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(node)).equals(run.contextHash()))
            throw invalid();
        return node;
    }
    public Map<String,Segment> sources(ProposalRun run,List<ProposalStore.Step> steps) {
        Map<String,Segment> sources=new LinkedHashMap<>();
        for(var doc:context(run).path("documents")) {
            String id=doc.path("documentId").asText();var parsed=doc.path("parsed");
            if(parsed.path("kind").asText().equals("pdf")) {
                for(var page:parsed.path("pdf").path("pages")) {
                    String text=page.path("text").asText();int number=page.path("page").asInt();
                    if(!text.isBlank()) add(sources,new Segment(id+":page:"+number,id,text,"parser",number,null,null));
                }
                for(var step:steps) if(step.stage().equals("ocr:"+id)) {
                    for(var page:parse(step.payload()).path("pages")) for(var segment:page.path("segments")) {
                        int number=page.path("page").asInt();
                        add(sources,new Segment(id+":ocr:"+number+":"+segment.path("offset").asInt(),id,
                                segment.path("text").asText(),"ocr",number,null,null));
                    }
                }
            } else {
                for(var sheet:parsed.path("xlsx").path("sheets")) {
                    if(!sheet.path("state").asText().equals("visible")) continue;
                    for(var row:sheet.path("rows")) for(var cell:row.path("cells")) {
                        if(java.util.Set.of("formula","error").contains(cell.path("type").asText())) continue;
                        String text=cell.path("value").asText();int index=sheet.path("index").asInt();
                        if(!text.isBlank()) add(sources,new Segment(id+":cell:"+index+":"+cell.path("coordinate").asText(),id,
                                text,"parser",null,index,cell.path("coordinate").asText()));
                    }
                }
            }
        }
        return sources;
    }
    private static void add(Map<String,Segment> map,Segment segment) {
        if(map.putIfAbsent(segment.id(),segment)!=null) throw invalid();
    }
    public JsonNode parse(String payload) {
        try { return mapper.readTree(payload); }
        catch(com.fasterxml.jackson.core.JsonProcessingException e) { throw invalid(); }
    }
    static AnalysisValidationException invalid() { return new AnalysisValidationException("AI_SCHEMA_INVALID","Invalid advisory source or output"); }
}
