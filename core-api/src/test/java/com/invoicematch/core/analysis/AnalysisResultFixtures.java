package com.invoicematch.core.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.domain.AnalysisParserContract;
import java.util.UUID;

/**
 * Builders for valid P2-04 {@code document-parse-v1} wire results used by the
 * P2-07 execution tests. They mirror the real Python wire keys exactly, without
 * reproducing any parser logic.
 */
public final class AnalysisResultFixtures {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AnalysisResultFixtures() {
    }

    public static ObjectNode pdf(UUID documentId, long sizeBytes, String checksum, String... pageTexts) {
        ObjectNode root = base(documentId, sizeBytes, checksum, "pdf");
        ObjectNode pdf = root.putObject("pdf");
        pdf.put("pageCount", pageTexts.length);
        ArrayNode pages = pdf.putArray("pages");
        for (int i = 0; i < pageTexts.length; i++) {
            ObjectNode page = pages.addObject();
            page.put("page", i + 1);
            page.put("text", pageTexts[i]);
        }
        root.putNull("xlsx");
        return root;
    }

    public static ObjectNode xlsx(UUID documentId, long sizeBytes, String checksum, String cellValue) {
        ObjectNode root = base(documentId, sizeBytes, checksum, "xlsx");
        root.putNull("pdf");
        ObjectNode xlsx = root.putObject("xlsx");
        xlsx.put("sheetCount", 1);
        ArrayNode sheets = xlsx.putArray("sheets");
        ObjectNode sheet = sheets.addObject();
        sheet.put("index", 1);
        sheet.put("name", "Sheet1");
        sheet.put("state", "visible");
        ArrayNode rows = sheet.putArray("rows");
        ObjectNode row = rows.addObject();
        row.put("row", 1);
        ArrayNode cells = row.putArray("cells");
        ObjectNode cell = cells.addObject();
        cell.put("coordinate", "A1");
        cell.put("column", 1);
        cell.put("type", "string");
        cell.put("value", cellValue);
        cell.putNull("cachedValue");
        cell.putNull("cachedType");
        cell.putNull("numberFormat");
        return root;
    }

    public static ObjectNode base(UUID documentId, long sizeBytes, String checksum, String kind) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", AnalysisParserContract.SCHEMA_VERSION);
        root.put("parserVersion", AnalysisParserContract.PARSER_VERSION);
        root.put("documentId", documentId.toString());
        ObjectNode source = root.putObject("source");
        source.put("mediaType", "pdf".equals(kind)
                ? "application/pdf"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        source.put("sizeBytes", sizeBytes);
        source.put("sha256", checksum);
        root.put("kind", kind);
        root.putArray("warnings");
        return root;
    }
}
