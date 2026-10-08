package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalToolStore;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Fixed case-scoped read-only tools, shared without coupling graph callers to v1 execution. */
@Component
class FrozenProposalTools {
    private static final Set<String> TOOLS=Set.of("get_purchase_order","get_receipts",
            "search_items","get_prior_invoice_cases","get_contract_metadata");
    private final ProposalToolStore history;
    private final ProposalSourceCatalog sources;
    private final ObjectMapper mapper;

    FrozenProposalTools(ProposalToolStore history, ProposalSourceCatalog sources, ObjectMapper mapper) {
        this.history = history;
        this.sources = sources;
        this.mapper = mapper;
    }

    static void validateRequest(ProposalToolService.Request request) {
        if(request==null || request.requestId()==null || !TOOLS.contains(request.tool()==null?"":request.tool())
            || request.query()==null || request.query().length()>100 || request.query().chars().anyMatch(Character::isISOControl)
            || request.limit()<1 || request.limit()>10)
            throw new AnalysisValidationException("TOOL_DENIED","Only bounded read-only tools are available");
    }

    /** Caller owns fencing, budget admission and immutable storage. */
    JsonNode readFrozen(ProposalRun run,ProposalToolService.Request request) {
        validateRequest(request);
        var arguments=mapper.valueToTree(request);
        JsonNode context=sources.context(run),match=context.path("matchResult");
        ObjectNode output=mapper.createObjectNode().put("schemaVersion","ai-tool-v1").put("contextHash",run.contextHash())
                .put("capturedAtMatching",true);
        output.set("request",arguments);
        switch(request.tool()) {
            case "get_purchase_order" -> {
                var po=output.putObject("result").put("purchaseOrderId",match.path("purchaseOrderId").asText())
                        .put("supplierId",match.path("supplierId").asText());
                po.set("items",context.path("items"));
                po.set("snapshot",match.path("purchasingSnapshot").deepCopy());
                ((ObjectNode)po.path("snapshot")).remove("receipts");
            }
            case "get_receipts" -> output.set("result",match.path("purchasingSnapshot").path("receipts"));
            case "search_items" -> {
                var result=output.putArray("result");String query=request.query().strip().toLowerCase(Locale.ROOT);
                for(var item:context.path("items")) {
                    if(query.isEmpty() || item.path("itemName").asText().toLowerCase(Locale.ROOT).contains(query)) result.add(item);
                    if(result.size()==request.limit()) break;
                }
            }
            case "get_contract_metadata" -> output.set("result",context.path("policyDocuments"));
            case "get_prior_invoice_cases" -> {
                var result=output.putArray("result");
                for(var prior:history.approvedMappings(run.caseId(),match.path("supplierId").asText())) {
                    if(request.query().isBlank() || prior.rawItemName().toLowerCase(Locale.ROOT).contains(request.query().strip().toLowerCase(Locale.ROOT)))
                        result.add(mapper.valueToTree(prior));
                    if(result.size()==request.limit()) break;
                }
                output.put("capturedAtMatching",false); // Historical read is frozen at this first tool call.
            }
            default -> throw new IllegalStateException("Unreachable tool");
        }
        String canonical=AnalysisCanonicalJson.canonicalize(output);
        if(canonical.getBytes(StandardCharsets.UTF_8).length>20000)
            throw new AnalysisValidationException("AI_INPUT_LIMIT","Tool result exceeds the limit");
        return output;
    }
}
