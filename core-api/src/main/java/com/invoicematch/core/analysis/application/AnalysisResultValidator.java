package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.AnalysisParserContract;
import com.invoicematch.core.document.domain.DocumentEvidence;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Validates one machine result against the frozen manifest document and the
 * P2-04 {@code document-parse-v1} wire contract, then canonicalizes it. Java
 * never re-parses PDF/XLSX and never evaluates a formula or date; it only walks
 * the bounded JSON tree, enforces the wire limits and hashes the canonical form.
 *
 * <p>Status policy: a structural, schema or bound violation is a
 * {@link AnalysisValidationException} (400); a document identity that does not
 * match the frozen manifest is an {@link AnalysisConflictException} (409).
 */
@Component
public class AnalysisResultValidator {

    private static final String SUCCESS = "SUCCESS";
    private static final String FAILURE = "FAILURE";
    private static final String PDF = "pdf";
    private static final String XLSX = "xlsx";
    private static final String PDF_MEDIA = "application/pdf";
    private static final String XLSX_MEDIA =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ObjectMapper mapper = new ObjectMapper();

    public ValidatedDocumentResult validate(AnalysisDocumentResultCommand command, DocumentEvidence document) {
        String outcome = command.outcome();
        if (SUCCESS.equals(outcome)) {
            return validateSuccess(command, document);
        }
        if (FAILURE.equals(outcome)) {
            return validateFailure(command, document);
        }
        throw invalid("INVALID_OUTCOME", "outcome must be SUCCESS or FAILURE");
    }

    private ValidatedDocumentResult validateSuccess(
            AnalysisDocumentResultCommand command, DocumentEvidence document) {
        if (command.errorCode() != null && !command.errorCode().isBlank()) {
            throw invalid("INVALID_RESULT", "a success result must not carry an error code");
        }
        JsonNode result = command.result();
        if (result == null || !result.isObject()) {
            throw invalid("INVALID_RESULT", "a success result must be a JSON object");
        }
        validateStructure(result, document);

        String canonical = AnalysisCanonicalJson.canonicalize(result);
        if (canonical.getBytes(StandardCharsets.UTF_8).length > AnalysisParserContract.MAX_RESULT_JSON_BYTES) {
            throw invalid("RESULT_TOO_LARGE", "the parse result exceeds the JSON size limit");
        }
        return new ValidatedDocumentResult(
                SUCCESS,
                AnalysisParserContract.PARSER_VERSION,
                AnalysisParserContract.SCHEMA_VERSION,
                canonical,
                null,
                envelopeHash(result, document.checksum(), null));
    }

    private ValidatedDocumentResult validateFailure(
            AnalysisDocumentResultCommand command, DocumentEvidence document) {
        if (command.result() != null && !command.result().isNull()) {
            throw invalid("INVALID_RESULT", "a failure result must not carry parse content");
        }
        String errorCode = command.errorCode();
        if (errorCode == null || !AnalysisParserContract.ERROR_CODES.contains(errorCode)) {
            throw invalid("INVALID_ERROR_CODE", "the failure code is not a known parser code");
        }
        return new ValidatedDocumentResult(
                FAILURE,
                AnalysisParserContract.PARSER_VERSION,
                AnalysisParserContract.SCHEMA_VERSION,
                null,
                errorCode,
                envelopeHash(null, document.checksum(), errorCode));
    }

    private String envelopeHash(JsonNode result, String sourceChecksum, String errorCode) {
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("outcome", result == null ? FAILURE : SUCCESS);
        envelope.put("sourceChecksum", sourceChecksum);
        envelope.put("resultSchemaVersion", AnalysisParserContract.SCHEMA_VERSION);
        envelope.put("parserVersion", AnalysisParserContract.PARSER_VERSION);
        envelope.set("result", result == null ? NullNode.getInstance() : result);
        envelope.set("errorCode", errorCode == null ? NullNode.getInstance() : mapper.getNodeFactory().textNode(errorCode));
        return AnalysisCanonicalJson.sha256Hex(AnalysisCanonicalJson.canonicalize(envelope));
    }

    private void validateStructure(JsonNode result, DocumentEvidence document) {
        if (!AnalysisParserContract.SCHEMA_VERSION.equals(text(result, "schemaVersion"))) {
            throw invalid("UNKNOWN_SCHEMA", "unsupported result schema version");
        }
        if (!AnalysisParserContract.PARSER_VERSION.equals(text(result, "parserVersion"))) {
            throw invalid("UNKNOWN_PARSER", "unsupported parser version");
        }
        if (!document.documentId().toString().equals(text(result, "documentId"))) {
            throw conflict("MANIFEST_MISMATCH", "the result document does not belong to the frozen manifest");
        }

        JsonNode source = object(result, "source");
        if (!document.mediaType().equals(text(source, "mediaType"))
                || document.sizeBytes() != longValue(source, "sizeBytes")
                || !document.checksum().equals(text(source, "sha256"))) {
            throw conflict("MANIFEST_MISMATCH", "the result source does not match the frozen document metadata");
        }

        String kind = text(result, "kind");
        if (PDF.equals(kind)) {
            requireKindMedia(document, PDF_MEDIA);
        } else if (XLSX.equals(kind)) {
            requireKindMedia(document, XLSX_MEDIA);
        } else {
            throw invalid("INVALID_STRUCTURE", "kind must be pdf or xlsx");
        }

        JsonNode pdf = result.get("pdf");
        JsonNode xlsx = result.get("xlsx");
        if (PDF.equals(kind)) {
            if (pdf == null || !pdf.isObject() || (xlsx != null && !xlsx.isNull())) {
                throw invalid("INVALID_STRUCTURE", "a pdf result must carry only the pdf structure");
            }
        } else if (xlsx == null || !xlsx.isObject() || (pdf != null && !pdf.isNull())) {
            throw invalid("INVALID_STRUCTURE", "an xlsx result must carry only the xlsx structure");
        }

        validateWarnings(result.get("warnings"));

        if (PDF.equals(kind)) {
            validatePdf(pdf);
        } else {
            validateXlsx(xlsx);
        }
    }

    private static void requireKindMedia(DocumentEvidence document, String expectedMediaType) {
        if (!expectedMediaType.equals(document.mediaType())) {
            throw new AnalysisValidationException("INVALID_STRUCTURE",
                    "the result kind does not match the frozen media type");
        }
    }

    private void validateWarnings(JsonNode warnings) {
        if (warnings == null || !warnings.isArray()) {
            throw invalid("INVALID_STRUCTURE", "warnings must be an array");
        }
        for (JsonNode warning : warnings) {
            if (!warning.isObject() || !AnalysisParserContract.WARNING_CODES.contains(text(warning, "code"))
                    || !warning.path("message").isTextual()) {
                throw invalid("INVALID_STRUCTURE", "a warning is not a known warning shape");
            }
            JsonNode page = warning.get("page");
            if (page != null && !page.isNull() && !page.isIntegralNumber()) {
                throw invalid("INVALID_STRUCTURE", "a warning page must be an integer or null");
            }
        }
    }

    private void validatePdf(JsonNode pdf) {
        int pageCount = intValue(pdf, "pageCount");
        if (pageCount < 1 || pageCount > AnalysisParserContract.MAX_PDF_PAGES) {
            throw invalid("INVALID_STRUCTURE", "the pdf page count is out of range");
        }
        JsonNode pages = array(pdf, "pages");
        if (pages.size() != pageCount) {
            throw invalid("INVALID_STRUCTURE", "pageCount does not match the pages array");
        }
        long textBytes = 0;
        for (int i = 0; i < pages.size(); i++) {
            JsonNode page = pages.get(i);
            if (!page.isObject() || intValue(page, "page") != i + 1) {
                throw invalid("INVALID_STRUCTURE", "pdf pages must be 1-based and contiguous");
            }
            String value = text(page, "text");
            textBytes += value.getBytes(StandardCharsets.UTF_8).length;
        }
        if (textBytes > AnalysisParserContract.MAX_TEXT_VALUE_BYTES) {
            throw invalid("TEXT_VALUE_LIMIT", "extracted text exceeds the size limit");
        }
    }

    private void validateXlsx(JsonNode xlsx) {
        int sheetCount = intValue(xlsx, "sheetCount");
        if (sheetCount < 1 || sheetCount > AnalysisParserContract.MAX_SHEETS) {
            throw invalid("INVALID_STRUCTURE", "the sheet count is out of range");
        }
        JsonNode sheets = array(xlsx, "sheets");
        if (sheets.size() != sheetCount) {
            throw invalid("INVALID_STRUCTURE", "sheetCount does not match the sheets array");
        }
        long textBytes = 0;
        long cellCount = 0;
        for (int s = 0; s < sheets.size(); s++) {
            JsonNode sheet = sheets.get(s);
            if (!sheet.isObject() || intValue(sheet, "index") != s + 1) {
                throw invalid("INVALID_STRUCTURE", "sheet indexes must be 1-based and contiguous");
            }
            text(sheet, "name");
            text(sheet, "state");
            JsonNode rows = array(sheet, "rows");
            if (rows.size() > AnalysisParserContract.MAX_ROWS_PER_SHEET) {
                throw invalid("INVALID_STRUCTURE", "a sheet exceeds the row limit");
            }
            int lastRow = 0;
            Set<String> coordinates = new HashSet<>();
            for (JsonNode row : rows) {
                if (!row.isObject()) {
                    throw invalid("INVALID_STRUCTURE", "a row must be an object");
                }
                int rowNumber = intValue(row, "row");
                if (rowNumber < 1 || rowNumber <= lastRow) {
                    throw invalid("INVALID_STRUCTURE", "row numbers must be positive and strictly increasing");
                }
                lastRow = rowNumber;
                JsonNode cells = array(row, "cells");
                for (JsonNode cell : cells) {
                    if (!cell.isObject()) {
                        throw invalid("INVALID_STRUCTURE", "a cell must be an object");
                    }
                    String coordinate = text(cell, "coordinate");
                    if (!coordinates.add(coordinate)) {
                        throw invalid("INVALID_STRUCTURE", "duplicate cell coordinate in a sheet");
                    }
                    int column = intValue(cell, "column");
                    if (column < 1 || column > AnalysisParserContract.MAX_COLUMNS_PER_SHEET) {
                        throw invalid("INVALID_STRUCTURE", "a cell column is out of range");
                    }
                    if (!AnalysisParserContract.CELL_TYPES.contains(text(cell, "type"))) {
                        throw invalid("INVALID_STRUCTURE", "a cell type is not a known type");
                    }
                    textBytes += valueBytes(cell.get("value"));
                    JsonNode cachedValue = cell.get("cachedValue");
                    if (cachedValue != null && !cachedValue.isNull()) {
                        if (!cachedValue.isTextual()) {
                            throw invalid("INVALID_STRUCTURE", "a cached value must be a string or null");
                        }
                        textBytes += cachedValue.asText().getBytes(StandardCharsets.UTF_8).length;
                    }
                    JsonNode cachedType = cell.get("cachedType");
                    if (cachedType != null && !cachedType.isNull()
                            && (!cachedType.isTextual()
                                    || !AnalysisParserContract.CELL_TYPES.contains(cachedType.asText()))) {
                        throw invalid("INVALID_STRUCTURE", "a cached type is not a known type");
                    }
                    JsonNode numberFormat = cell.get("numberFormat");
                    if (numberFormat != null && !numberFormat.isNull() && !numberFormat.isTextual()) {
                        throw invalid("INVALID_STRUCTURE", "a number format must be a string or null");
                    }
                    cellCount++;
                    if (cellCount > AnalysisParserContract.MAX_NONEMPTY_CELLS) {
                        throw invalid("XLSX_CELL_LIMIT", "the workbook exceeds the non-empty cell limit");
                    }
                }
            }
        }
        if (textBytes > AnalysisParserContract.MAX_TEXT_VALUE_BYTES) {
            throw invalid("TEXT_VALUE_LIMIT", "extracted text and values exceed the size limit");
        }
    }

    private static long valueBytes(JsonNode value) {
        if (value == null || value.isNull() || value.isBoolean() || value.isNumber()) {
            return 0;
        }
        if (!value.isTextual()) {
            throw new AnalysisValidationException("INVALID_STRUCTURE", "a cell value must be a scalar");
        }
        return value.asText().getBytes(StandardCharsets.UTF_8).length;
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isObject()) {
            throw new AnalysisValidationException("INVALID_STRUCTURE", field + " must be an object");
        }
        return node;
    }

    private static JsonNode array(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isArray()) {
            throw new AnalysisValidationException("INVALID_STRUCTURE", field + " must be an array");
        }
        return node;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isTextual()) {
            throw new AnalysisValidationException("INVALID_STRUCTURE", field + " must be a string");
        }
        return node.asText();
    }

    private static int intValue(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw new AnalysisValidationException("INVALID_STRUCTURE", field + " must be an integer");
        }
        return node.asInt();
    }

    private static long longValue(JsonNode parent, String field) {
        JsonNode node = parent.get(field);
        if (node == null || !node.isIntegralNumber() || !node.canConvertToLong()) {
            throw new AnalysisValidationException("INVALID_STRUCTURE", field + " must be an integer");
        }
        return node.asLong();
    }

    private static AnalysisValidationException invalid(String code, String message) {
        return new AnalysisValidationException(code, message);
    }

    private static AnalysisConflictException conflict(String code, String message) {
        return new AnalysisConflictException(code, message);
    }
}
