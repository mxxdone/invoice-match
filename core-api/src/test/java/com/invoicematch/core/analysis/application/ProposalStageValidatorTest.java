package com.invoicematch.core.analysis.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.ProposalRun;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProposalStageValidatorTest {
    private final ObjectMapper json=new ObjectMapper();
    private final ProposalStageValidator validator=new ProposalStageValidator(new ProposalSourceCatalog(json));
    private final UUID documentId=UUID.randomUUID();
    private final String text="청구 😀 INV-1 60개 ₩2,500";
    private ProposalRun run() {
        var root=json.createObjectNode();
        var d=root.putArray("documents").addObject().put("documentId",documentId.toString()).put("checksum","0".repeat(64));
        var parsed=d.putObject("parsed").put("kind","pdf");parsed.putObject("source").put("sizeBytes",100);
        var pdf=parsed.putObject("pdf").put("pageCount",1);pdf.putArray("pages").addObject().put("page",1).put("text",text);
        String context=AnalysisCanonicalJson.canonicalize(root);
        return new ProposalRun(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
                AnalysisCanonicalJson.sha256Hex(context),context,"RUNNING",UUID.randomUUID(),Instant.now().plusSeconds(120),1,1,1000,0,true,true,null);
    }
    private ObjectNode field(String raw,String value) {
        var field=json.createObjectNode().put("value",value);int start=text.codePointCount(0,text.indexOf(raw));
        field.putObject("source").put("segmentId",documentId+":page:1").put("start",start).put("end",start+raw.codePointCount(0,raw.length()));
        return field;
    }
    private ObjectNode document() {
        var n=json.createObjectNode().put("schemaVersion","invoice-extraction-v1").put("promptVersion","invoice-advisory-1");
        n.putArray("calls").addObject().put("model","fixture-v1").put("inputTokens",100).put("outputTokens",100).put("latencyMs",1);
        var result=n.putObject("result");result.putArray("fields").add(field("INV-1","INV-1").put("name","invoiceNumber"));
        var line=result.putArray("lines").addObject().put("lineNumber",1);
        line.set("rawItemName",field("청구","청구"));line.set("quantity",field("60개","60"));line.set("unitPrice",field("₩2,500","2500"));
        result.putArray("warnings");return n;
    }
    @Test void pythonUnicodeOffsetsAndWholeNumericNormalizationAreVerified() {
        validator.validate("document",document(),run(),List.of());
        assertThat(ProposalStageValidator.normalize("2026.10.04","invoiceDate")).isEqualTo("2026-10-04");
        assertThatThrownBy(()->ProposalStageValidator.normalize("2,500.5","unitPrice")).isInstanceOf(AnalysisValidationException.class);
    }
    @Test void sourceValueForeignSegmentAndUtf16OffsetForgeryAreRejected() {
        var good=document();var value=good.deepCopy();
        ((ObjectNode)value.path("result").path("fields").get(0)).put("value","INV-forged");reject("document",value);
        value=good.deepCopy();((ObjectNode)value.path("result").path("fields").get(0).path("source")).put("segmentId","another-document");reject("document",value);
        value=good.deepCopy();((ObjectNode)value.path("result").path("fields").get(0).path("source")).put("start",text.indexOf("INV-1"));reject("document",value);
        value=good.deepCopy();value.put("approve",true);reject("document",value);
        value=good.deepCopy();((ObjectNode)value.path("result").path("lines").get(0).path("quantity")).put("value","100");reject("document",value);
    }
    @Test void unknownStagesAndUnreservedUsageCannotBeCheckpointed() {
        reject("approve",document());var n=document();((ObjectNode)n.path("calls").get(0)).put("inputTokens",1001);reject("document",n);
        n=document();((ObjectNode)n.path("result")).putArray("warnings").addObject();reject("document",n);
    }
    private ObjectNode ocr() {
        var n=json.createObjectNode().put("schemaVersion","invoice-ocr-v1").put("providerVersion","azure-prebuilt-invoice-2024-11-30")
                .put("documentId",documentId.toString()).put("checksum","0".repeat(64)).put("content",text);
        var page=n.putArray("pages").addObject().put("page",1).put("width",8.3).put("height",11.7).put("unit","inch");
        int length=text.codePointCount(0,text.length());page.putArray("spans").addObject().put("offset",0).put("length",length);
        var segment=page.putArray("segments").addObject().put("offset",0).put("length",length).put("text",text);
        segment.putArray("polygon").add(0).add(0).add(4).add(0).add(4).add(1).add(0).add(1);n.putArray("fields");return n;
    }
    @Test void ocrSourceIdentityPageSpanAndCoordinateBoundsAreIndependentOfWorker() {
        String stage="ocr:"+documentId;validator.validate(stage,ocr(),run(),List.of());
        var n=ocr();n.put("checksum","1".repeat(64));reject(stage,n);
        n=ocr();((ObjectNode)n.path("pages").get(0).path("segments").get(0)).put("text","fabricated");reject(stage,n);
        n=ocr();((ObjectNode)n.path("pages").get(0)).put("page",2);reject(stage,n);
        n=ocr();((ObjectNode)n.path("pages").get(0)).put("width",-1);reject(stage,n);
        n=ocr();((com.fasterxml.jackson.databind.node.ArrayNode)n.path("pages").get(0).path("segments").get(0).path("polygon")).set(0,json.getNodeFactory().numberNode(99));reject(stage,n);
        reject("ocr:"+UUID.randomUUID(),ocr());
    }
    private void reject(String stage,ObjectNode n) { assertThatThrownBy(()->validator.validate(stage,n,run(),List.of())).isInstanceOf(AnalysisValidationException.class); }
}
