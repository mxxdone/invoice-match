package com.invoicematch.core.analysis.persistence;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Projections of approved historical mappings; no actors or unconfirmed decisions. */
@Repository
public class ProposalToolStore {
    private final JdbcTemplate jdbc;
    public ProposalToolStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record PriorMapping(UUID snapshotId,String snapshotHash,int lineNumber,String rawItemName,
            String itemId,String purchaseOrderLineId) {}
    public List<PriorMapping> approvedMappings(UUID caseId,String supplierId) {
        return jdbc.query("""
            with recent as (
                select s.id,s.payload_hash,s.payload,d.decided_at
                from invoice_case c
                join review_decision d on d.invoice_case_id=c.id and d.decision='APPROVED'
                join review_snapshot s on s.id=d.review_snapshot_id and s.invoice_case_id=c.id
                where c.supplier_id=? and c.id<>?
                order by d.decided_at desc,s.id limit 10
            )
            select r.id,r.payload_hash,(m->>'lineNumber')::integer,l->>'rawItemName',
                m->>'itemId',m->>'purchaseOrderLineId'
            from recent r
            cross join lateral jsonb_array_elements(r.payload->'effectiveMappings') m
            cross join lateral jsonb_array_elements(r.payload->'invoiceLines') l
            where m->>'lineNumber'=l->>'lineNumber'
            order by r.decided_at desc,r.id,(m->>'lineNumber')::integer limit 20
            """,(rs,n)->new PriorMapping(rs.getObject(1,UUID.class),rs.getString(2),rs.getInt(3),
                    rs.getString(4),rs.getString(5),rs.getString(6)),supplierId,caseId);
    }
}
