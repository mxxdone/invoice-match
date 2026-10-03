package com.invoicematch.core.analysis.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.AnalysisResultFixtures;
import com.invoicematch.core.document.domain.DocumentEvidence;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Structural/bounds validation of the P2-04 {@code document-parse-v1} wire shape,
 * without a database or parser. It proves the Java side interprets the wire
 * contract exactly and rejects schema, kind, position, limit and size violations.
 */
class AnalysisResultValidatorTest {

    private final AnalysisResultValidator validator = new AnalysisResultValidator();
    private final DocumentEvidence document = new DocumentEvidence(
            UUID.fromString("00000000-0000-0000-0000-0000000000aa"),
            UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
            "invoice.pdf",
            "application/pdf",
            12,
            "a".repeat(64));
    private final DocumentEvidence spreadsheet = new DocumentEvidence(
            UUID.fromString("00000000-0000-0000-0000-0000000000cc"),
            UUID.fromString("00000000-0000-0000-0000-0000000000dd"),
            "book.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            20,
            "c".repeat(64));

    @Test
    void acceptsAValidPdfResult() {
        ValidatedDocumentResult validated = validator.validate(success(pdf("text")), document);
        assertThat(validated.outcome()).isEqualTo("SUCCESS");
        assertThat(validated.payloadHash()).hasSize(64);
        assertThat(validated.payloadJson()).contains("document-parse-v1");
    }

    @Test
    void acceptsAKnownFailureCodeWithNoPayload() {
        ValidatedDocumentResult validated =
                validator.validate(command("FAILURE", null, "PDF_CORRUPT"), document);
        assertThat(validated.outcome()).isEqualTo("FAILURE");
        assertThat(validated.payloadJson()).isNull();
        assertThat(validated.errorCode()).isEqualTo("PDF_CORRUPT");
    }

    @Test
    void rejectsUnknownSchemaKindAndErrorCode() {
        ObjectNode unknownSchema = pdf("text");
        unknownSchema.put("schemaVersion", "bogus");
        assertThatThrownBy(() -> validator.validate(success(unknownSchema), document))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode unknownKind = pdf("text");
        unknownKind.put("kind", "csv");
        assertThatThrownBy(() -> validator.validate(success(unknownKind), document))
                .isInstanceOf(AnalysisValidationException.class);

        assertThatThrownBy(() -> validator.validate(command("FAILURE", null, "NOT_A_CODE"), document))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void rejectsSuccessWithErrorAndFailureWithPayload() {
        assertThatThrownBy(() -> validator.validate(command("SUCCESS", pdf("text"), "PDF_CORRUPT"), document))
                .isInstanceOf(AnalysisValidationException.class);
        assertThatThrownBy(() -> validator.validate(command("FAILURE", pdf("text"), "PDF_CORRUPT"), document))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void rejectsNonContiguousPages() {
        ObjectNode nonContiguous = AnalysisResultFixtures.pdf(
                document.documentId(), document.sizeBytes(), document.checksum(), "one", "two");
        ((ObjectNode) nonContiguous.get("pdf").get("pages").get(1)).put("page", 5);
        assertThatThrownBy(() -> validator.validate(success(nonContiguous), document))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void rejectsDuplicateCoordinatesAndOutOfRangeColumns() {
        ObjectNode duplicate = xlsx("KRW");
        ObjectNode row = (ObjectNode) duplicate.get("xlsx").get("sheets").get(0).get("rows").get(0);
        row.withArray("cells").add(row.get("cells").get(0).deepCopy());
        assertThatThrownBy(() -> validator.validate(success(duplicate), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode wide = xlsx("KRW");
        ((ObjectNode) wide.get("xlsx").get("sheets").get(0).get("rows").get(0).get("cells").get(0))
                .put("column", 257);
        assertThatThrownBy(() -> validator.validate(success(wide), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void rejectsOversizedTextAndOversizedCanonicalJson() {
        ObjectNode largeText = pdf("x".repeat(1024 * 1024 + 1));
        assertThatThrownBy(() -> validator.validate(success(largeText), document))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode largeBlob = pdf("text");
        largeBlob.withArray("warnings").addObject()
                .put("code", "EMPTY_TEXT_LAYER").put("message", "y".repeat(4 * 1024 * 1024 + 1))
                .putNull("page");
        assertThatThrownBy(() -> validator.validate(success(largeBlob), document))
                .isInstanceOfSatisfying(AnalysisValidationException.class,
                        error -> assertThat(error.code()).isEqualTo("RESULT_TOO_LARGE"));
    }

    @Test
    void rejectsMetadataMismatchAsAConflict() {
        ObjectNode wrongSource = pdf("text");
        ((ObjectNode) wrongSource.get("source")).put("sha256", "0".repeat(64));
        assertThatThrownBy(() -> validator.validate(success(wrongSource), document))
                .isInstanceOf(AnalysisConflictException.class);
    }

    @Test
    void acceptsLowercaseCoordinatesAndRejectsRowColumnMismatch() {
        ObjectNode lowercase = xlsx("KRW");
        cell(lowercase).put("coordinate", "a1");
        assertThat(validator.validate(success(lowercase), spreadsheet).outcome()).isEqualTo("SUCCESS");

        ObjectNode mismatched = xlsx("KRW");
        cell(mismatched).put("coordinate", "B1");
        assertThatThrownBy(() -> validator.validate(success(mismatched), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void rejectsRowBeyondTheLimit() {
        ObjectNode beyond = xlsx("KRW");
        ObjectNode row = (ObjectNode) beyond.get("xlsx").get("sheets").get(0).get("rows").get(0);
        row.put("row", 10_001);
        assertThatThrownBy(() -> validator.validate(success(beyond), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void rejectsValueTypeAndCachedCombinationMismatches() {
        ObjectNode booleanAsString = xlsx("true");
        cell(booleanAsString).put("type", "boolean");
        assertThatThrownBy(() -> validator.validate(success(booleanAsString), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode stringAsBoolean = xlsx("value");
        cell(stringAsBoolean).put("value", true);
        assertThatThrownBy(() -> validator.validate(success(stringAsBoolean), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode nonFormulaCached = xlsx("KRW");
        cell(nonFormulaCached).put("cachedType", "string");
        assertThatThrownBy(() -> validator.validate(success(nonFormulaCached), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode formulaWithoutCache = xlsx("=A1");
        cell(formulaWithoutCache).put("type", "formula");
        assertThatThrownBy(() -> validator.validate(success(formulaWithoutCache), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode formulaWithFormat = xlsx("=A1");
        cell(formulaWithFormat).put("type", "formula");
        cell(formulaWithFormat).put("cachedType", "number");
        cell(formulaWithFormat).put("numberFormat", "0.00");
        assertThatThrownBy(() -> validator.validate(success(formulaWithFormat), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void acceptsAFormulaWithAStringCachedValueAndRejectsEmptySuccessErrorCode() {
        ObjectNode formula = xlsx("=A1");
        cell(formula).put("type", "formula");
        cell(formula).put("cachedType", "boolean");
        cell(formula).put("cachedValue", "1");
        assertThat(validator.validate(success(formula), spreadsheet).outcome()).isEqualTo("SUCCESS");

        assertThatThrownBy(() -> validator.validate(command("SUCCESS", pdf("text"), ""), document))
                .isInstanceOf(AnalysisValidationException.class);
    }

    @Test
    void rejectsUnknownWireFields() {
        ObjectNode topLevel = pdf("text");
        topLevel.put("unexpected", "x");
        assertThatThrownBy(() -> validator.validate(success(topLevel), document))
                .isInstanceOf(AnalysisValidationException.class);

        ObjectNode unknownCell = xlsx("KRW");
        cell(unknownCell).put("unexpected", "x");
        assertThatThrownBy(() -> validator.validate(success(unknownCell), spreadsheet))
                .isInstanceOf(AnalysisValidationException.class);
    }

    private static ObjectNode cell(ObjectNode root) {
        return (ObjectNode) root.get("xlsx").get("sheets").get(0).get("rows").get(0).get("cells").get(0);
    }

    private ObjectNode pdf(String text) {
        return AnalysisResultFixtures.pdf(document.documentId(), document.sizeBytes(), document.checksum(), text);
    }

    private ObjectNode xlsx(String cellValue) {
        return AnalysisResultFixtures.xlsx(
                spreadsheet.documentId(), spreadsheet.sizeBytes(), spreadsheet.checksum(), cellValue);
    }

    private static AnalysisDocumentResultCommand success(JsonNode result) {
        return command("SUCCESS", result, null);
    }

    private static AnalysisDocumentResultCommand command(String outcome, JsonNode result, String errorCode) {
        return new AnalysisDocumentResultCommand(
                UUID.fromString("00000000-0000-0000-0000-0000000000aa"), 1, "hash", null, outcome, result, errorCode);
    }
}
