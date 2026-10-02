package com.invoicematch.core.document.persistence;

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
    public long occupiedSlots(UUID revisionId, Instant now) {
        return jdbc.queryForObject("select count(*) from document_upload u where draft_revision_id = ?"
                + " and (expires_at > ? or exists(select 1 from document d where d.id = u.id))",
                Long.class, revisionId, Timestamp.from(now));
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
