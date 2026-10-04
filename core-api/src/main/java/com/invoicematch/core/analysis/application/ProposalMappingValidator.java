package com.invoicematch.core.analysis.application;

import static com.invoicematch.core.analysis.application.ProposalStageValidator.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Independent source and server-ID checks; suggestions carry no mapping authority. */
final class ProposalMappingValidator {
    private ProposalMappingValidator() {}
    static void validate(JsonNode n,ProposalRun run,List<ProposalStore.Step> steps,ProposalSourceCatalog catalog) {
        keys(n,"schemaVersion","promptVersion","calls","result");
        if(!"item-mapping-v1".equals(n.path("schemaVersion").asText()) || !"invoice-advisory-1".equals(n.path("promptVersion").asText()))
            throw ProposalSourceCatalog.invalid();
        keys(n.path("result"),"lines");array(n.path("result").path("lines"),0,100);
        if(n.path("result").path("lines").isEmpty()) array(n.path("calls"),0,0);else calls(n.path("calls"),run,1);
        var document=steps.stream().filter(s->s.stage().equals("document")).findFirst().orElseThrow(ProposalSourceCatalog::invalid);
        JsonNode extraction=catalog.parse(document.payload());
        if(!AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(extraction)).equals(document.hash())) throw ProposalSourceCatalog.invalid();
        var expected=new HashMap<Integer,JsonNode>();for(var line:extraction.path("result").path("lines")) expected.put(line.path("lineNumber").asInt(),line);
        var candidates=new HashMap<String,JsonNode>();
        for(var item:catalog.context(run).path("items")) candidates.put(item.path("itemId").asText()+"\n"+item.path("purchaseOrderLineId").asText(),item);
        var prior=new java.util.ArrayList<JsonNode>();
        for(var step:steps) if(step.stage().startsWith("tool:")) {
            var tool=catalog.parse(step.payload());
            if("get_prior_invoice_cases".equals(tool.path("request").path("tool").asText())) tool.path("result").forEach(prior::add);
        }
        var lines=n.path("result").path("lines");if(lines.size()!=expected.size()) throw ProposalSourceCatalog.invalid();
        Set<Integer> seen=new HashSet<>();
        for(var line:lines) {
            keys(line,"lineNumber","source","candidates","reviewRequired","warningCodes");int number=integer(line.get("lineNumber"),1,100);
            if(!expected.containsKey(number) || !seen.add(number) || !line.path("reviewRequired").isBoolean()) throw ProposalSourceCatalog.invalid();
            var original=expected.get(number).path("rawItemName");String raw=text(original.get("value"),500);
            if(!line.path("source").equals(original.path("source"))) throw ProposalSourceCatalog.invalid();
            array(line.path("candidates"),0,3);array(line.path("warningCodes"),0,3);Set<String> warnings=new HashSet<>();
            for(var warning:line.path("warningCodes")) {
                String code=text(warning,40);if(!Set.of("NO_CANDIDATES","AMBIGUOUS","SPECIFICATION_MISMATCH").contains(code) || !warnings.add(code)) throw ProposalSourceCatalog.invalid();
            }
            Set<String> required=new HashSet<>(),pairs=new HashSet<>();
            if(line.path("candidates").isEmpty()) required.add("NO_CANDIDATES");
            if(line.path("candidates").size()>1 || specifications(raw).stream().anyMatch(s->s.size()>1)) required.add("AMBIGUOUS");
            for(var candidate:line.path("candidates")) {
                keys(candidate,"itemId","purchaseOrderLineId","reasonCodes","reason","priorSnapshotId");
                String itemId=text(candidate.get("itemId"),64),pair=itemId+"\n"+text(candidate.get("purchaseOrderLineId"),64);
                if(!candidates.containsKey(pair) || !pairs.add(pair)) throw ProposalSourceCatalog.invalid();text(candidate.get("reason"),300);
                array(candidate.path("reasonCodes"),1,4);Set<String> codes=new HashSet<>();
                for(var code: candidate.path("reasonCodes")) {
                    String value=text(code,40);if(!Set.of("EXACT_NAME","ALIAS","PRIOR_MAPPING","SPECIFICATION_MISMATCH").contains(value) || !codes.add(value)) throw ProposalSourceCatalog.invalid();
                }
                String itemName=candidates.get(pair).path("itemName").asText();
                if(codes.contains("EXACT_NAME") && !name(raw).equals(name(itemName))) throw ProposalSourceCatalog.invalid();
                if(codes.contains("PRIOR_MAPPING")) {
                    String snapshot=text(candidate.get("priorSnapshotId"),64);
                    if(prior.stream().noneMatch(p->p.path("snapshotId").asText().equals(snapshot) && p.path("itemId").asText().equals(itemId)
                            && name(p.path("rawItemName").asText()).equals(name(raw)))) throw ProposalSourceCatalog.invalid();
                } else if(!candidate.path("priorSnapshotId").isNull()) throw ProposalSourceCatalog.invalid();
                if(mismatch(raw,itemName)) {
                    required.add("SPECIFICATION_MISMATCH");if(!codes.contains("SPECIFICATION_MISMATCH")) throw ProposalSourceCatalog.invalid();
                }
            }
            if(!warnings.containsAll(required) || (!warnings.isEmpty() && !line.path("reviewRequired").asBoolean())) throw ProposalSourceCatalog.invalid();
        }
    }
    static String name(String raw) {
        return raw.replaceAll("(?U)\\s+","").toLowerCase(Locale.ROOT);
    }
    static List<Set<String>> specifications(String raw) {
        var text=raw.toUpperCase(Locale.ROOT);var sizes=new HashSet<String>();var grams=new HashSet<String>();
        var size=Pattern.compile("(?<![A-Z0-9])A[0-9](?![0-9])").matcher(text);while(size.find()) sizes.add(size.group());
        var gram=Pattern.compile("([0-9]{1,3})\\s*(?:G/M2|G/M²|G/㎡|GSM|G)(?![A-Z0-9])").matcher(text);while(gram.find()) grams.add(gram.group(1));
        return List.of(sizes,grams);
    }
    static boolean mismatch(String raw,String item) {
        var a=specifications(raw);var b=specifications(item);
        for(int i=0;i<a.size();i++) if(!a.get(i).isEmpty() && !b.get(i).isEmpty() && !a.get(i).equals(b.get(i))) return true;
        return false;
    }
}
