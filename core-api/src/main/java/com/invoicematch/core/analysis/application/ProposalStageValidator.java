package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.invoicematch.core.analysis.domain.ProposalRun;
import com.invoicematch.core.analysis.persistence.ProposalStore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Independent Core validation; a worker's local schema validation is not authority. */
@Component
public class ProposalStageValidator {
    private final ProposalSourceCatalog catalog;
    public ProposalStageValidator(ProposalSourceCatalog catalog) { this.catalog=catalog; }
    public void validate(String stage,JsonNode value,ProposalRun run,List<ProposalStore.Step> steps) {
        bounded(value,0,new int[]{0});
        if("execution".equals(stage)) validateExecution(value);
        else if("embedding".equals(stage)) validateEmbedding(value,run,steps);
        else if(stage!=null && stage.startsWith("ocr:")) validateOcr(stage,value,run);
        else if("document".equals(stage)) validateDocument(value,run,steps);
        else if("mapping".equals(stage)) ProposalMappingValidator.validate(value,run,steps,catalog);
        else if("evidence".equals(stage) || "resolution".equals(stage)) ProposalResolutionValidator.validate(stage,value,run,steps,catalog);
        else throw ProposalSourceCatalog.invalid();
    }
    private void validateExecution(JsonNode n) {
        keys(n,"schemaVersion","model","providerFingerprint","inputPricePerMillion","outputPricePerMillion","currency","costCeiling","embedding","tokenParameter","promptVersion");
        if(!n.path("schemaVersion").asText().equals("ai-execution-plan-v1") || !n.path("promptVersion").asText().equals("invoice-advisory-1")
            || !text(n.get("providerFingerprint"),64).matches("[0-9a-f]{64}")
            || !Set.of("USD","KRW").contains(text(n.get("currency"),3))
            || !Set.of("max_tokens","max_completion_tokens").contains(text(n.get("tokenParameter"),30))) throw ProposalSourceCatalog.invalid();
        text(n.get("model"),100);
        for(String key:List.of("inputPricePerMillion","outputPricePerMillion","costCeiling")) {
            var value=n.get(key);
            if(value==null || !value.isNumber() || !Double.isFinite(value.doubleValue()) || value.decimalValue().signum()<0
                || value.decimalValue().compareTo(BigDecimal.valueOf(1000000))>0 || value.decimalValue().scale()>8) throw ProposalSourceCatalog.invalid();
        }
        if(n.path("costCeiling").decimalValue().signum()<=0) throw ProposalSourceCatalog.invalid();
        var e=n.path("embedding");
        if(!e.isNull()) {
            keys(e,"model","version","dimension","providerFingerprint","pricePerMillion");
            text(e.get("model"),100);text(e.get("version"),100);integer(e.get("dimension"),1,3072);
            if(!text(e.get("providerFingerprint"),64).matches("[0-9a-f]{64}") || !e.path("pricePerMillion").isNumber()
                || !Double.isFinite(e.path("pricePerMillion").doubleValue()) || e.path("pricePerMillion").decimalValue().signum()<0
                || e.path("pricePerMillion").decimalValue().compareTo(BigDecimal.valueOf(1000000))>0 || e.path("pricePerMillion").decimalValue().scale()>8) throw ProposalSourceCatalog.invalid();
        }
    }
    private void validateEmbedding(JsonNode n,ProposalRun run,List<ProposalStore.Step> steps) {
        keys(n,"schemaVersion","query","model","version","dimension","embedding","calls");
        if(!n.path("schemaVersion").asText().equals("ai-query-embedding-v1")) throw ProposalSourceCatalog.invalid();
        var context=catalog.context(run);boolean price=false;
        for(var exception:context.path("matchResult").path("exceptions")) if(exception.path("type").asText().contains("PRICE")) price=true;
        if(context.path("matchResult").path("normal").asBoolean() || !n.path("query").asText().equals(price?"단가":"분할")) throw ProposalSourceCatalog.invalid();
        var config=ProposalResolutionValidator.step("execution",steps,catalog).path("embedding");
        if(config.isNull() || !config.path("model").equals(n.path("model")) || !config.path("version").equals(n.path("version"))
            || integer(n.get("dimension"),1,3072)!=config.path("dimension").asInt()) throw ProposalSourceCatalog.invalid();
        int dimension=n.path("dimension").asInt();array(n.path("embedding"),dimension,dimension);float[] vector=new float[dimension];
        for(int i=0;i<dimension;i++) {var v=n.path("embedding").get(i);if(!v.isNumber() || !Double.isFinite(v.doubleValue())) throw ProposalSourceCatalog.invalid();vector[i]=v.floatValue();}
        PolicySearchService.validateEmbedding(text(n.get("model"),100),vector);
        array(context.path("policyDocuments"),1,20);
        for(var d:context.path("policyDocuments")) if(!d.path("embeddingModel").equals(n.path("model")) || !d.path("embeddingVersion").equals(n.path("version"))
            || d.path("embeddingDimension").asInt()!=dimension) throw ProposalSourceCatalog.invalid();
        calls(n.path("calls"),run,1);
        if(!n.path("calls").get(0).path("model").equals(n.path("model")) || n.path("calls").get(0).path("outputTokens").asInt()!=0) throw ProposalSourceCatalog.invalid();
    }
    private void bounded(JsonNode n,int depth,int[] count) {
        if(n==null || depth>12 || ++count[0]>15000) throw ProposalSourceCatalog.invalid();
        if(n.isContainerNode()) for(var child:n) bounded(child,depth+1,count);
        if(n.isTextual() && n.textValue().length()>100000) throw ProposalSourceCatalog.invalid();
    }
    static void keys(JsonNode node,String... names) {
        if(node==null || !node.isObject()) throw ProposalSourceCatalog.invalid();
        Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(Set.of(names))) throw ProposalSourceCatalog.invalid();
    }
    static String text(JsonNode n,int limit) {
        if(n==null || !n.isTextual() || n.asText().isBlank() || n.asText().length()>limit || n.asText().indexOf('\0')>=0)
            throw ProposalSourceCatalog.invalid();
        return n.asText();
    }
    static int integer(JsonNode n,int low,int high) {
        if(n==null || !n.isIntegralNumber() || !n.canConvertToInt() || n.asInt()<low || n.asInt()>high) throw ProposalSourceCatalog.invalid();
        return n.asInt();
    }
    static void array(JsonNode n,int low,int high) {
        if(n==null || !n.isArray() || n.size()<low || n.size()>high) throw ProposalSourceCatalog.invalid();
    }
    private void validateDocument(JsonNode n,ProposalRun run,List<ProposalStore.Step> steps) {
        keys(n,"schemaVersion","promptVersion","calls","result");
        if(!"invoice-extraction-v1".equals(n.path("schemaVersion").asText()) || !"invoice-advisory-1".equals(n.path("promptVersion").asText()))
            throw ProposalSourceCatalog.invalid();
        calls(n.path("calls"),run,2);
        var sources=catalog.sources(run,steps);var result=n.path("result");
        keys(result,"fields","lines","warnings");array(result.path("fields"),0,4);array(result.path("lines"),0,100);array(result.path("warnings"),0,10);
        Set<String> names=new HashSet<>();
        for(var field:result.path("fields")) {
            keys(field,"name","value","source");String name=text(field.get("name"),30);
            if(!Set.of("invoiceNumber","supplierName","invoiceDate","currency").contains(name) || !names.add(name)) throw ProposalSourceCatalog.invalid();
            field(field,name,sources);
        }
        Set<Integer> numbers=new HashSet<>();
        for(var line:result.path("lines")) {
            keys(line,"lineNumber","rawItemName","quantity","unitPrice");
            if(!numbers.add(integer(line.get("lineNumber"),1,100))) throw ProposalSourceCatalog.invalid();
            for(String kind:List.of("rawItemName","quantity","unitPrice")) {
                keys(line.path(kind),"value","source");field(line.path(kind),kind,sources);
            }
        }
        for(var warning:result.path("warnings")) if(!Set.of("MISSING_FIELDS","AMBIGUOUS_LAYOUT","EMPTY_DOCUMENT","DOCUMENT_CONFLICT").contains(text(warning,40)))
            throw ProposalSourceCatalog.invalid();
        if(result.path("fields").isEmpty() && result.path("lines").isEmpty()) {
            boolean empty=false;for(var w:result.path("warnings")) if(w.asText().equals("EMPTY_DOCUMENT")) empty=true;
            if(!empty) throw ProposalSourceCatalog.invalid();
        }
    }
    private void field(JsonNode field,String kind,Map<String,ProposalSourceCatalog.Segment> sources) {
        String quote=quote(field.path("source"),sources);
        if(!text(field.get("value"),500).equals(normalize(quote,kind))) throw ProposalSourceCatalog.invalid();
    }
    static String quote(JsonNode source,Map<String,ProposalSourceCatalog.Segment> sources) {
        keys(source,"segmentId","start","end");var segment=sources.get(text(source.get("segmentId"),150));
        if(segment==null) throw ProposalSourceCatalog.invalid();
        String value=segment.text();int length=value.codePointCount(0,value.length());
        int start=integer(source.get("start"),0,length),end=integer(source.get("end"),1,length);
        if(start>=end || end-start>500) throw ProposalSourceCatalog.invalid();
        return strip(value.substring(value.offsetByCodePoints(0,start),value.offsetByCodePoints(0,end)));
    }
    static String strip(String value) { return value.replaceAll("^[\\p{javaWhitespace}\\p{Zs}]+|[\\p{javaWhitespace}\\p{Zs}]+$",""); }
    static String normalize(String raw,String kind) {
        raw=strip(raw);
        if(Set.of("quantity","unitPrice").contains(kind)) {
            String numeric=kind.equals("quantity")?raw.replaceFirst("(?i)\\s*(개|EA|PCS)$","")
                    :raw.replaceAll("(?i)^(₩|KRW)\\s*|\\s*원$","");
            if(!numeric.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)(?:\\.[0-9]+)?")) throw ProposalSourceCatalog.invalid();
            try {
                var number=new BigDecimal(numeric.replace(",",""));long value=number.longValueExact();
                if(value<(kind.equals("quantity")?1:0) || (kind.equals("quantity") && value>Integer.MAX_VALUE)) throw ProposalSourceCatalog.invalid();
                return Long.toString(value);
            } catch(ArithmeticException|NumberFormatException e) { throw ProposalSourceCatalog.invalid(); }
        }
        if(kind.equals("invoiceDate")) {
            if(!raw.matches("[0-9]{4}[./-][0-9]{1,2}[./-][0-9]{1,2}")) throw ProposalSourceCatalog.invalid();
            String[] pieces=raw.split("[./-]");
            try { return LocalDate.of(Integer.parseInt(pieces[0]),Integer.parseInt(pieces[1]),Integer.parseInt(pieces[2])).toString(); }
            catch(java.time.DateTimeException e) { throw ProposalSourceCatalog.invalid(); }
        }
        if(kind.equals("currency")) {
            return switch(raw) { case "₩","원","KRW"->"KRW";case "USD","EUR"->raw;default->throw ProposalSourceCatalog.invalid(); };
        }
        return raw;
    }
    static void calls(JsonNode calls,ProposalRun run,int max) {
        array(calls,1,max);int tokens=0;
        if(calls.size()>run.reservedCalls()) throw ProposalSourceCatalog.invalid();
        for(var call:calls) {
            keys(call,"model","inputTokens","outputTokens","latencyMs");text(call.get("model"),100);
            tokens+=integer(call.get("inputTokens"),0,40000)+integer(call.get("outputTokens"),0,2000);
            integer(call.get("latencyMs"),0,41000);
        }
        if(tokens>run.reservedTokens()) throw ProposalSourceCatalog.invalid();
    }
    private void validateOcr(String stage,JsonNode n,ProposalRun run) {
        keys(n,"schemaVersion","providerVersion","documentId","checksum","content","pages","fields");
        String documentId=stage.substring(4);
        var document=java.util.stream.StreamSupport.stream(catalog.context(run).path("documents").spliterator(),false)
                .filter(d->d.path("documentId").asText().equals(documentId)).findFirst().orElseThrow(ProposalSourceCatalog::invalid);
        int pageCount=document.path("parsed").path("pdf").path("pageCount").asInt();
        if(!n.path("schemaVersion").asText().equals("invoice-ocr-v1")
                || !n.path("providerVersion").asText().equals("azure-prebuilt-invoice-2024-11-30")
                || !n.path("documentId").asText().equals(documentId) || !n.path("checksum").asText().equals(document.path("checksum").asText())
                || !document.path("parsed").path("kind").asText().equals("pdf") || pageCount<1 || pageCount>2
                || document.path("parsed").path("source").path("sizeBytes").asLong()>4000000 || !n.path("content").isTextual())
            throw ProposalSourceCatalog.invalid();
        String content=n.path("content").asText();int contentLength=content.codePointCount(0,content.length());
        array(n.path("pages"),pageCount,pageCount);array(n.path("fields"),0,500);
        int number=0;
        for(var page:n.path("pages")) {
            keys(page,"page","width","height","unit","spans","segments");
            if(integer(page.get("page"),1,pageCount)!=++number || !Set.of("inch","pixel").contains(text(page.get("unit"),10))) throw ProposalSourceCatalog.invalid();
            double width=dimension(page.get("width")),height=dimension(page.get("height"));
            spans(page.path("spans"),contentLength);array(page.path("segments"),0,1000);Set<Integer> starts=new HashSet<>();
            for(var segment:page.path("segments")) {
                keys(segment,"offset","length","text","polygon");
                int start=integer(segment.get("offset"),0,contentLength),length=integer(segment.get("length"),1,contentLength);
                if(start+length>contentLength || !starts.add(start) || !within(page.path("spans"),start,length)
                        || !substring(content,start,length).equals(text(segment.get("text"),100000))) throw ProposalSourceCatalog.invalid();
                array(segment.path("polygon"),8,8);int axis=0;
                for(var coordinate:segment.path("polygon")) {
                    if(!coordinate.isNumber() || !Double.isFinite(coordinate.doubleValue()) || coordinate.doubleValue()<0
                            || coordinate.doubleValue()>(axis++%2==0?width:height)) throw ProposalSourceCatalog.invalid();
                }
            }
        }
        for(var field:n.path("fields")) {
            keys(field,"name","content","spans","confidence","pages");
            String name=text(field.get("name"),50);
            if(!Set.of("InvoiceId","VendorName","InvoiceDate","InvoiceTotal","CurrencyCode").contains(name)
                    && !name.matches("Items\\.(?:[1-9][0-9]?|100)\\.(Description|Quantity|UnitPrice|Amount)")) throw ProposalSourceCatalog.invalid();
            var confidence=field.get("confidence");
            if(!confidence.isNumber() || !Double.isFinite(confidence.doubleValue()) || confidence.doubleValue()<0 || confidence.doubleValue()>1) throw ProposalSourceCatalog.invalid();
            spans(field.path("spans"),contentLength);array(field.path("pages"),1,pageCount);
            Set<Integer> pages=new HashSet<>();for(var p:field.path("pages")) if(!pages.add(integer(p,1,pageCount))) throw ProposalSourceCatalog.invalid();
            var quote=new java.util.ArrayList<String>();
            for(var span:field.path("spans")) {
                int start=span.path("offset").asInt(),length=span.path("length").asInt();
                if(length<1 || pages.stream().noneMatch(p->within(n.path("pages").get(p-1).path("spans"),start,length))) throw ProposalSourceCatalog.invalid();
                quote.add(substring(content,start,length));
            }
            if(!strip(String.join(" ",quote)).replaceAll("\\s+"," ").equals(strip(text(field.get("content"),100000)).replaceAll("\\s+"," ")))
                throw ProposalSourceCatalog.invalid();
        }
    }
    private static String substring(String text,int start,int length) { return text.substring(text.offsetByCodePoints(0,start),text.offsetByCodePoints(0,start+length)); }
    private static double dimension(JsonNode n) {
        if(n==null || !n.isNumber() || !Double.isFinite(n.doubleValue()) || n.doubleValue()<=0 || n.doubleValue()>100000) throw ProposalSourceCatalog.invalid();
        return n.doubleValue();
    }
    private static void spans(JsonNode spans,int length) {
        array(spans,1,10);
        for(var span:spans) {
            keys(span,"offset","length");int start=integer(span.get("offset"),0,length),count=integer(span.get("length"),0,length);
            if((long)start+count>length) throw ProposalSourceCatalog.invalid();
        }
    }
    private static boolean within(JsonNode spans,int start,int length) {
        for(var span:spans) if(span.path("offset").asInt()<=start && (long)start+length<=(long)span.path("offset").asInt()+span.path("length").asInt()) return true;
        return false;
    }
}
