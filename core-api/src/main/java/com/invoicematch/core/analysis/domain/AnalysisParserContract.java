package com.invoicematch.core.analysis.domain;

import java.util.Set;

/**
 * Fixed identity and bounds of the Python {@code document-parse-v1} wire
 * contract (P2-04). These values are not derived from the clock or environment;
 * the worker emits exactly this schema/parser identity and the JSON result shape
 * documented in {@code ai-worker/application/wire.py}. The Java side only
 * interprets that wire shape and never re-parses PDF/XLSX or evaluates a
 * formula/date.
 *
 * <p>Raising any bound is a contract change.
 */
public final class AnalysisParserContract {

    /** Top-level discriminator of a parse result. */
    public static final String SCHEMA_VERSION = "document-parse-v1";

    /** Frozen engine/library identity produced by P2-04. */
    public static final String PARSER_VERSION =
            "document-parse-v1;pdf=pypdf-6.19.0;xml=defusedxml-0.7.1;ooxml=ai-worker-safe-ooxml-1";

    public static final int MAX_PDF_PAGES = 100;
    public static final int MAX_SHEETS = 20;
    public static final int MAX_ROWS_PER_SHEET = 10_000;
    public static final int MAX_COLUMNS_PER_SHEET = 256;
    public static final int MAX_NONEMPTY_CELLS = 100_000;
    public static final long MAX_TEXT_VALUE_BYTES = 1024L * 1024L;
    public static final long MAX_RESULT_JSON_BYTES = 4L * 1024L * 1024L;

    /** The only parse warning code the worker emits. */
    public static final Set<String> WARNING_CODES = Set.of("EMPTY_TEXT_LAYER");

    public static final Set<String> CELL_TYPES =
            Set.of("string", "number", "boolean", "date", "formula", "error");

    /**
     * The complete, stable parser failure code whitelist from
     * {@code ai-worker/domain/errors.py}. A failure result carries only one of
     * these codes; no arbitrary message or path is accepted.
     */
    public static final Set<String> ERROR_CODES = Set.of(
            "INPUT_TOO_LARGE", "SIZE_MISMATCH", "CHECKSUM_MISMATCH", "FORMAT_UNSUPPORTED", "FORMAT_MISMATCH",
            "INPUT_UNREADABLE", "PDF_ENCRYPTED", "PDF_CORRUPT", "PDF_PAGE_LIMIT", "XLSX_ENCRYPTED", "XLSX_CORRUPT",
            "XLSX_SHEET_LIMIT", "XLSX_DIMENSION_LIMIT", "XLSX_CELL_LIMIT", "ZIP_ENTRY_LIMIT", "ZIP_ENTRY_TOO_LARGE",
            "ZIP_TOTAL_TOO_LARGE", "ZIP_COMPRESSION_RATIO", "ZIP_ENCRYPTED_ENTRY", "ZIP_DUPLICATE_ENTRY",
            "ZIP_UNSAFE_PATH", "XML_UNSAFE", "TEXT_VALUE_LIMIT", "RESULT_TOO_LARGE", "TIMEOUT",
            "MEMORY_LIMIT_EXCEEDED", "OUTPUT_LIMIT_EXCEEDED", "PARSER_CRASHED", "INTERNAL_ERROR", "UNSUPPORTED_HOST");

    private AnalysisParserContract() {
    }
}
