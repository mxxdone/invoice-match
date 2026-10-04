package com.invoicematch.core.analysis.persistence;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** SQL only. Decisions about retryability and ownership belong to application. */
@Repository
public class ProposalRecoveryStore {
    final JdbcTemplate jdbc;
    public ProposalRecoveryStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public boolean recorded(UUID id,UUID token) {return jdbc.queryForObject("select count(*) from proposal_failure where run_id=? and execution_token=?",Integer.class,id,token)==1;}
    public void failure(UUID id,UUID token,int attempt,String code,boolean retry,Duration delay) {
        jdbc.update("insert into proposal_failure(run_id,execution_token,attempt,error_code,disposition) values(?,?,?,?,?)",id,token,attempt,code,retry?"QUEUED":"FAILED");
        jdbc.update("update proposal_run set status=?,execution_token=null,lease_until=null,error_code=?,next_attempt_at=clock_timestamp()+(?*interval '1 millisecond'),updated_at=clock_timestamp() where id=?",
            retry?"QUEUED":"FAILED",code,delay.toMillis(),id);
        if(retry) dispatch(id,"failure:"+token,delay,false);
        else cancelPending(id);
    }
    public void exhausted(UUID id) {
        jdbc.update("update proposal_run set status='FAILED',execution_token=null,lease_until=null,error_code='LEASE_EXPIRED',updated_at=clock_timestamp() where id=?",id);
        cancelPending(id);
    }
    public void cancelPending(UUID id) {
        jdbc.update("update proposal_dispatch set status='CANCELLED',lease_token=null,lease_until=null where run_id=? and status in ('READY','CLAIMED')",id);
        jdbc.update("update proposal_request_outbox set status='CANCELLED',lease_token=null,lease_until=null where id=? and status in ('READY','CLAIMED')",id);
    }
    public void dispatch(UUID run,String key,Duration delay,boolean waitLease) {
        jdbc.update("insert into proposal_dispatch(id,run_id,dedup_key,payload,next_attempt_at) select ?,r.id,?,o.payload,"
            +" greatest(r.next_attempt_at,clock_timestamp()+(?*interval '1 millisecond'),case when ? then coalesce(r.lease_until,clock_timestamp()) else clock_timestamp() end)"
            +" from proposal_run r join proposal_request_outbox o on o.id=r.id where r.id=? on conflict(run_id,dedup_key) do nothing",UUID.randomUUID(),key,delay.toMillis(),waitLease,run);
    }
    public record Dispatch(UUID id,UUID eventId,UUID token,String payload,boolean initial) {}
    @Transactional
    public Optional<Dispatch> claim(boolean initial,Duration lease) {
        String table=initial?"proposal_request_outbox":"proposal_dispatch";
        String runId=initial?"o.id":"o.run_id";
        return jdbc.query("with candidate as (select o.id from "+table+" o join proposal_run r on r.id="+runId
            +" where (o.status='READY' or (o.status='CLAIMED' and o.lease_until<=clock_timestamp())) and o.next_attempt_at<=clock_timestamp()"
            +" and r.status in ('QUEUED','RUNNING') order by o.next_attempt_at,o.id for update of o skip locked limit 1)"
            +" update "+table+" o set status='CLAIMED',lease_token=?,lease_until=clock_timestamp()+(?*interval '1 millisecond'),attempt_count=attempt_count+1"
            +" from candidate c where o.id=c.id returning o.id,"+(initial?"o.id":"o.run_id")+",o.lease_token,o.payload::text",
            (r,n)->new Dispatch(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),initial),UUID.randomUUID(),lease.toMillis()).stream().findFirst();
    }
    @Transactional
    public boolean settle(Dispatch d,boolean published,Duration delay) {
        String table=d.initial()?"proposal_request_outbox":"proposal_dispatch";
        return jdbc.update("update "+table+" set status=?,lease_token=null,lease_until=null,published_at=case when ? then clock_timestamp() else null end,"
            +" next_attempt_at=case when ? then next_attempt_at else clock_timestamp()+(?*interval '1 millisecond') end"
            +" where id=? and status='CLAIMED' and lease_token=? and lease_until>clock_timestamp()",published?"PUBLISHED":"READY",published,published,delay.toMillis(),d.id(),d.token())==1;
    }
}
