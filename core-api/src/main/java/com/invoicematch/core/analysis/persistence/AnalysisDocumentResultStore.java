package com.invoicematch.core.analysis.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence-only store for immutable per-document results. It joins the
 * caller's short transaction ({@code MANDATORY}); a run lock is always held by
 * the caller, so the insert and the terminal transition commit or roll back
 * together. Results are append-only at the database, so this store never updates
 * or deletes.
 */
@Service
public class AnalysisDocumentResultStore {

    private final JdbcTemplate jdbc;

    public AnalysisDocumentResultStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public Optional<StoredDocumentResult> find(UUID runId, UUID documentId) {
        return jdbc.query(
                "select document_id, outcome, payload_hash, error_code"
                        + " from analysis_document_result where run_id = ? and document_id = ?",
                (rs, n) -> new StoredDocumentResult(
                        rs.getObject("document_id", UUID.class),
                        rs.getString("outcome"),
                        rs.getString("payload_hash"),
                        rs.getString("error_code")),
                runId,
                documentId).stream().findFirst();
    }

    /** The outcome of every document result stored for the run so far. */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public List<StoredDocumentResult> findByRunId(UUID runId) {
        return jdbc.query(
                "select document_id, outcome, payload_hash, error_code"
                        + " from analysis_document_result where run_id = ?",
                (rs, n) -> new StoredDocumentResult(
                        rs.getObject("document_id", UUID.class),
                        rs.getString("outcome"),
                        rs.getString("payload_hash"),
                        rs.getString("error_code")),
                runId);
    }

    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    public void insert(
            UUID runId,
            UUID documentId,
            String sourceChecksum,
            String outcome,
            String parserVersion,
            String resultSchemaVersion,
            String payloadJson,
            String errorCode,
            String payloadHash,
            Instant createdAt) {
        jdbc.update(
                "insert into analysis_document_result"
                        + " (run_id, document_id, source_checksum, outcome, parser_version, result_schema_version,"
                        + " payload, error_code, payload_hash, created_at)"
                        + " values (?, ?, ?, ?, ?, ?, cast(? as jsonb), ?, ?, ?)",
                runId,
                documentId,
                sourceChecksum,
                outcome,
                parserVersion,
                resultSchemaVersion,
                payloadJson,
                errorCode,
                payloadHash,
                java.sql.Timestamp.from(createdAt));
    }
}
