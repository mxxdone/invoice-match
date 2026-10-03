package com.invoicematch.core.analysis.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.AnalysisParserContract;
import com.invoicematch.core.document.domain.DocumentEvidence;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Validates one machine result against the frozen manifest document and the
 * P2-04 {@code document-parse-v1} wire contract, then canonicalizes it. Java
 * never re-parses PDF/XLSX and never evaluates a formula or date; it only walks
 * the bounded JSON tree, enforces the wire limits and hashes the canonical form.
 *
 * <p>The wire shape of {@code ai-worker/application/wire.py} is interpreted
 * exactly: unknown object keys are rejected, required/null fields must be
 * present, an A1 coordinate must agree with its row/column, cell values must
 * match their declared type, and the cached/number-format combinations must be
 * the ones the worker can emit. Nothing here invents parser semantics.
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

    private static final Pattern COORDINATE = Pattern.compile("([A-Za-z]{1,3})([0-9]{1,9})");
    private static final int MAX_JSON_DEPTH = 8;
    private static final long MAX_JSON_NODES = 1_000_000L;

    private static final Set<String> RESULT_KEYS = Set.of(
            "schemaVersion", "parserVersion", "documentId", "source", "kind", "warnings", "pdf", "xlsx");
    private static final Set<String> SOURCE_KEYS = Set.of("mediaType", "sizeBytes", "sha256");
    private static final Set<String> WARNING_KEYS = Set.of("code", "message", "page");
    private static final Set<String> PDF_KEYS = Set.of("pageCount", "pages");
    private static final Set<String> PAGE_KEYS = Set.of("page", "text");
    private static final Set<String> XLSX_KEYS = Set.of("sheetCount", "sheets");
    private static final Set<String> SHEET_KEYS = Set.of("index", "name", "state", "rows");
    private static final Set<String> ROW_KEYS = Set.of("row", "cells");
    private static final Set<String> CELL_KEYS = Set.of(
            "coordinate", "column", "type", "value", "cachedValue", "cachedType", "numberFormat");

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
        if (command.errorCode() != null) {
            throw invalid("INVALID_RESULT", "a success result must not carry an error code");
        }
        JsonNode result = command.result();
        if (result == null || !result.isObject()) {
            throw invalid("INVALID_RESULT", "a success result must be a JSON object");
        }
        assertBounded(result, 0, new long[] {0});
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

    /** Bounds the tree (depth and node count) before any recursive canonicalization. */
    private static void assertBounded(JsonNode node, int depth, long[] nodes) {
        if (depth > MAX_JSON_DEPTH) {
            throw invalid("INVALID_STRUCTURE", "the result JSON is nested too deeply");
        }
        if (++nodes[0] > MAX_JSON_NODES) {
            throw invalid("INVALID_STRUCTURE", "the result JSON has too many nodes");
        }
        if (node.isContainerNode()) {
            for (JsonNode child : node) {
                assertBounded(child, depth + 1, nodes);
            }
        }
    }

    private void validateStructure(JsonNode result, DocumentEvidence document) {
        rejectUnknown(result, RESULT_KEYS, "result");
        requirePresent(result, "source", "result");
        requirePresent(result, "warnings", "result");
        requirePresent(result, "pdf", "result");
        requirePresent(result, "xlsx", "result");

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
        rejectUnknown(source, SOURCE_KEYS, "source");
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
            if (pdf == null || !pdf.isObject() || !isNullNode(xlsx)) {
                throw invalid("INVALID_STRUCTURE", "a pdf result must carry only the pdf structure");
            }
        } else if (xlsx == null || !xlsx.isObject() || !isNullNode(pdf)) {
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
            if (!warning.isObject()) {
                throw invalid("INVALID_STRUCTURE", "a warning must be an object");
            }
            rejectUnknown(warning, WARNING_KEYS, "warning");
            requirePresent(warning, "message", "warning");
            requirePresent(warning, "page", "warning");
            if (!AnalysisParserContract.WARNING_CODES.contains(text(warning, "code"))) {
                throw invalid("INVALID_STRUCTURE", "a warning code is not a known code");
            }
            text(warning, "message");
            JsonNode page = warning.get("page");
            if (!isNullNode(page) && !page.isIntegralNumber()) {
                throw invalid("INVALID_STRUCTURE", "a warning page must be an integer or null");
            }
        }
    }

    private void validatePdf(JsonNode pdf) {
        rejectUnknown(pdf, PDF_KEYS, "pdf");
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
            if (!page.isObject()) {
                throw invalid("INVALID_STRUCTURE", "a page must be an object");
            }
            rejectUnknown(page, PAGE_KEYS, "page");
            if (intValue(page, "page") != i + 1) {
                throw invalid("INVALID_STRUCTURE", "pdf pages must be 1-based and contiguous");
            }
            textBytes += text(page, "text").getBytes(StandardCharsets.UTF_8).length;
        }
        if (textBytes > AnalysisParserContract.MAX_TEXT_VALUE_BYTES) {
            throw invalid("TEXT_VALUE_LIMIT", "extracted text exceeds the size limit");
        }
    }

    private void validateXlsx(JsonNode xlsx) {
        rejectUnknown(xlsx, XLSX_KEYS, "xlsx");
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
            if (!sheet.isObject()) {
                throw invalid("INVALID_STRUCTURE", "a sheet must be an object");
            }
            rejectUnknown(sheet, SHEET_KEYS, "sheet");
            if (intValue(sheet, "index") != s + 1) {
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
                rejectUnknown(row, ROW_KEYS, "row");
                int rowNumber = intValue(row, "row");
                if (rowNumber < 1 || rowNumber > AnalysisParserContract.MAX_ROWS_PER_SHEET || rowNumber <= lastRow) {
                    throw invalid("INVALID_STRUCTURE",
                            "row numbers must be positive, at most the row limit and strictly increasing");
                }
                lastRow = rowNumber;
                JsonNode cells = array(row, "cells");
                int lastColumn = 0;
                for (JsonNode cell : cells) {
                    if (!cell.isObject()) {
                        throw invalid("INVALID_STRUCTURE", "a cell must be an object");
                    }
                    rejectUnknown(cell, CELL_KEYS, "cell");
                    int column = intValue(cell, "column");
                    if (column <= lastColumn || column > AnalysisParserContract.MAX_COLUMNS_PER_SHEET) {
                        throw invalid("INVALID_STRUCTURE", "cell columns must be strictly increasing and in range");
                    }
                    lastColumn = column;
                    if (!coordinates.add(coordinate(cell, rowNumber, column))) {
                        throw invalid("INVALID_STRUCTURE", "duplicate cell position in a sheet");
                    }
                    textBytes += cellTextBytes(cell);
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

    /** Validates the cell mapping and returns its extracted-text byte contribution. */
    private static long cellTextBytes(JsonNode cell) {
        requirePresent(cell, "coordinate", "cell");
        requirePresent(cell, "cachedValue", "cell");
        requirePresent(cell, "cachedType", "cell");
        requirePresent(cell, "numberFormat", "cell");
        String type = text(cell, "type");
        if (!AnalysisParserContract.CELL_TYPES.contains(type)) {
            throw invalid("INVALID_STRUCTURE", "a cell type is not a known type");
        }
        JsonNode value = cell.get("value");
        if (value == null || value.isNull()) {
            throw invalid("INVALID_STRUCTURE", "a cell value is required");
        }
        JsonNode cachedValue = cell.get("cachedValue");
        JsonNode cachedType = cell.get("cachedType");
        JsonNode numberFormat = cell.get("numberFormat");

        long bytes = 0;
        if ("boolean".equals(type)) {
            if (!value.isBoolean()) {
                throw invalid("INVALID_STRUCTURE", "a boolean cell value must be a boolean");
            }
        } else {
            if (!value.isTextual()) {
                throw invalid("INVALID_STRUCTURE", "a non-boolean cell value must be a string");
            }
            bytes += value.asText().getBytes(StandardCharsets.UTF_8).length;
        }

        if ("formula".equals(type)) {
            // Only a formula carries a cached type; the cached value is the raw
            // cached scalar as a string (a boolean cached value stays "0"/"1").
            if (isNullNode(cachedType) || !cachedType.isTextual()
                    || !AnalysisParserContract.CELL_TYPES.contains(cachedType.asText())) {
                throw invalid("INVALID_STRUCTURE", "a formula cell must carry a known cached type");
            }
            if (!isNullNode(cachedValue) && !cachedValue.isTextual()) {
                throw invalid("INVALID_STRUCTURE", "a cached value must be a string or null");
            }
            if (!isNullNode(numberFormat)) {
                throw invalid("INVALID_STRUCTURE", "a formula cell must not carry a number format");
            }
            if (!isNullNode(cachedValue)) {
                bytes += cachedValue.asText().getBytes(StandardCharsets.UTF_8).length;
            }
        } else {
            if (!isNullNode(cachedType) || !isNullNode(cachedValue)) {
                throw invalid("INVALID_STRUCTURE", "only a formula cell carries a cached value/type");
            }
            if (!isNullNode(numberFormat)) {
                // Only a numeric cell detected as a date carries a number format.
                if (!"date".equals(type) || !numberFormat.isTextual()) {
                    throw invalid("INVALID_STRUCTURE", "a number format belongs to a date cell only");
                }
            }
        }
        return bytes;
    }

    /** Validates that a coordinate is A1-shaped and agrees with its row/column. */
    private static String coordinate(JsonNode cell, int row, int column) {
        String coordinate = text(cell, "coordinate");
        var matcher = COORDINATE.matcher(coordinate);
        if (!matcher.matches()) {
            throw invalid("INVALID_STRUCTURE", "a cell coordinate is not A1-shaped");
        }
        int letters = 0;
        for (char character : matcher.group(1).toUpperCase(java.util.Locale.ROOT).toCharArray()) {
            letters = letters * 26 + (character - 'A' + 1);
        }
        int coordinateRow;
        try {
            coordinateRow = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException e) {
            throw invalid("INVALID_STRUCTURE", "a cell coordinate row is not a valid number");
        }
        if (letters != column || coordinateRow != row) {
            throw invalid("INVALID_STRUCTURE", "a cell coordinate does not match its row/column");
        }
        return coordinate.toUpperCase(java.util.Locale.ROOT);
    }

    private static void rejectUnknown(JsonNode node, Set<String> allowed, String context) {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            if (!allowed.contains(names.next())) {
                throw invalid("INVALID_STRUCTURE", context + " contains an unknown field");
            }
        }
    }

    private static void requirePresent(JsonNode parent, String field, String context) {
        if (!parent.has(field)) {
            throw invalid("INVALID_STRUCTURE", context + " is missing the required field " + field);
        }
    }

    private static boolean isNullNode(JsonNode node) {
        return node == null || node.isNull();
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
