package com.invoicematch.core.document.persistence;

import com.invoicematch.core.document.domain.DocumentEvidence;
import com.invoicematch.core.document.domain.UploadIntent;
import com.invoicematch.core.document.domain.RegisteredDocument;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentStore {
    private final JdbcTemplate jdbc;
    public DocumentStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void reserve(UploadIntent u) {
        jdbc.update("insert into document_upload (id, invoice_case_id, draft_revision_id, file_name, media_type,"
                + " size_bytes, checksum, upload_key, expires_at, created_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                u.id(), u.caseId(), u.draftRevisionId(), u.fileName(), u.mediaType(), u.sizeBytes(), u.checksum(),
                u.uploadKey(), Timestamp.from(u.expiresAt()), Timestamp.from(u.createdAt()));
    }
    public Optional<UploadIntent> upload(UUID caseId, UUID id) {
        return jdbc.query("select * from document_upload where invoice_case_id = ? and id = ?",
                (rs, n) -> uploadRow(rs), caseId, id).stream().findFirst();
    }
    /**
     * Occupied document slots of a draft revision: every completed or inherited
     * reference plus each still-valid, not-yet-completed upload reservation. A
     * completed document is counted once through its reference, never again
     * through its upload reservation.
     */
    public long occupiedSlots(UUID revisionId, Instant now) {
        return jdbc.queryForObject("select"
                + " (select count(*) from draft_revision_document where draft_revision_id = ?)"
                + " + (select count(*) from document_upload u where u.draft_revision_id = ?"
                + "     and u.expires_at > ? and not exists(select 1 from document d where d.id = u.id))",
                Long.class, revisionId, revisionId, Timestamp.from(now));
    }
    public void reference(UUID revisionId, UUID caseId, UUID documentId, Instant createdAt) {
        jdbc.update("insert into draft_revision_document"
                + " (draft_revision_id, document_id, invoice_case_id, created_at) values (?, ?, ?, ?)",
                revisionId, documentId, caseId, Timestamp.from(createdAt));
    }
    public List<DocumentEvidence> evidenceForRevision(UUID revisionId) {
        return jdbc.query("select d.id as document_id, u.draft_revision_id as source_revision_id, u.file_name,"
                + " u.media_type, u.size_bytes, u.checksum from draft_revision_document r"
                + " join document d on d.id = r.document_id"
                + " join document_upload u on u.id = r.document_id"
                + " where r.draft_revision_id = ? order by d.id",
                (rs, n) -> new DocumentEvidence(rs.getObject("document_id", UUID.class),
                        rs.getObject("source_revision_id", UUID.class), rs.getString("file_name"),
                        rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("checksum")),
                revisionId);
    }
    public void register(RegisteredDocument d) {
        jdbc.update("insert into document (id, object_key, registered_case_version, registered_at) values (?, ?, ?, ?)",
                d.upload().id(), d.objectKey(), d.caseVersion(), Timestamp.from(d.registeredAt()));
    }
    public Optional<RegisteredDocument> document(UUID caseId, UUID id) {
        return jdbc.query("select u.*, d.object_key, d.registered_case_version, d.registered_at"
                + " from document_upload u join document d on d.id = u.id where u.invoice_case_id = ? and u.id = ?",
                (rs, n) -> documentRow(rs), caseId, id).stream().findFirst();
    }
    public List<RegisteredDocument> list(UUID caseId, int limit, long offset) {
        return jdbc.query("select u.*, d.object_key, d.registered_case_version, d.registered_at"
                + " from document_upload u join document d on d.id = u.id where u.invoice_case_id = ?"
                + " order by d.registered_at, u.id limit ? offset ?",
                (rs, n) -> documentRow(rs), caseId, limit, offset);
    }
    private static UploadIntent uploadRow(ResultSet rs) throws SQLException {
        return new UploadIntent(rs.getObject("id", UUID.class), rs.getObject("invoice_case_id", UUID.class),
                rs.getObject("draft_revision_id", UUID.class), rs.getString("file_name"), rs.getString("media_type"),
                rs.getLong("size_bytes"), rs.getString("checksum"), rs.getString("upload_key"),
                rs.getTimestamp("expires_at").toInstant(), rs.getTimestamp("created_at").toInstant());
    }
    private static RegisteredDocument documentRow(ResultSet rs) throws SQLException {
        return new RegisteredDocument(uploadRow(rs), rs.getString("object_key"),
                rs.getLong("registered_case_version"), rs.getTimestamp("registered_at").toInstant());
    }
}
