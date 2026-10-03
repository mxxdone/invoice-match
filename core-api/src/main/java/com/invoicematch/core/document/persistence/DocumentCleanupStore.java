package com.invoicematch.core.document.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class DocumentCleanupStore {
    private final JdbcTemplate jdbc;
    public DocumentCleanupStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Claim(UUID uploadId,UUID caseId,String uploadKey,UUID token) {}
    @Transactional
    public Optional<Claim> claim(Duration grace,Duration lease) {
        jdbc.update("with expired as (select upload_id from document_upload_cleanup where status='CLAIMED' and lease_until<=clock_timestamp() for update skip locked limit 100) update document_upload_cleanup set status='READY',claim_token=null,lease_until=null,next_attempt_at=clock_timestamp()"
                + " where upload_id in (select upload_id from expired)");
        jdbc.update("insert into document_upload_cleanup(upload_id) select u.id from document_upload u"
                + " where not exists(select 1 from document_upload_cleanup x where x.upload_id=u.id) and expires_at+cast(? as double precision)*interval '1 second'<=clock_timestamp()"
                + " order by expires_at,id limit 100 on conflict(upload_id) do nothing",grace.toSeconds());
        return jdbc.query("""
            with candidate as (select x.upload_id from document_upload_cleanup x join document_upload u on u.id=x.upload_id
              where x.status='READY' and x.next_attempt_at<=clock_timestamp()
                and u.expires_at+cast(? as double precision)*interval '1 second'<=clock_timestamp()
              order by x.next_attempt_at,x.upload_id for update of x skip locked limit 1),
            claimed as (update document_upload_cleanup x set status='CLAIMED',claim_token=?,
              lease_until=clock_timestamp()+cast(? as double precision)*interval '1 second',attempt_count=attempt_count+1
              from candidate c where x.upload_id=c.upload_id returning x.upload_id,x.claim_token)
            select u.id,u.invoice_case_id,u.upload_key,c.claim_token from claimed c join document_upload u on u.id=c.upload_id
            """,(r,n)->new Claim(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getObject(4,UUID.class)),
                grace.toSeconds(),UUID.randomUUID(),lease.toSeconds()).stream().findFirst();
    }
    @Transactional
    public boolean settle(Claim claim,String status,String code,Duration delay) {
        return jdbc.update("update document_upload_cleanup set status=?,claim_token=null,lease_until=null,last_error_code=?,"
                + "completed_at=case when ?='DONE' then clock_timestamp() else null end,"
                + "next_attempt_at=case when ?='READY' then clock_timestamp()+cast(? as double precision)*interval '1 second' else next_attempt_at end"
                + " where upload_id=? and status='CLAIMED' and claim_token=? and lease_until>clock_timestamp()",
                status,code,status,status,delay.toSeconds(),claim.uploadId(),claim.token())==1;
    }
}
