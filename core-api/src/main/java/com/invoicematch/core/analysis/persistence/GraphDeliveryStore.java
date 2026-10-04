package com.invoicematch.core.analysis.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Call only after case -> graph ownership locks. Delivery state never changes immutable intent. */
@Repository
public class GraphDeliveryStore {
    private final JdbcTemplate jdbc;
    public GraphDeliveryStore(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    public record Event(UUID id,UUID runId,String segment,String payload) {}
    public record Dispatch(UUID id,UUID runId,UUID eventId,UUID token,String segment,String payload) {}
    public void start(UUID id,String payload) {
        jdbc.update("insert into graph_event(id,run_id,segment,payload) values(?,?,'START',cast(? as jsonb))",id,id,payload);
        jdbc.update("insert into graph_dispatch(id,event_id,dedup_key) values(?,?,'initial')",id,id);
    }
    public Optional<Event> event(UUID runId,String segment) {
        return jdbc.query("select * from graph_event where run_id=? and segment=?",(rs,n)->new Event(
            rs.getObject("id",UUID.class),runId,segment,rs.getString("payload")),runId,segment).stream().findFirst();
    }
    public boolean due(UUID id) {return Boolean.TRUE.equals(jdbc.queryForObject("select next_attempt_at<=clock_timestamp() from graph_run where id=?",Boolean.class,id));}
    public void consume(Event event,UUID reviewId,UUID token) {
        jdbc.update("insert into graph_resume_consumption(event_id,run_id,review_id,first_token) values(?,?,?,?) on conflict(event_id) do nothing",
            event.id(),event.runId(),reviewId,token);
    }
    public boolean consumed(UUID id,UUID reviewId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from graph_resume_consumption where run_id=? and review_id=?)",Boolean.class,id,reviewId));
    }
    public Optional<String> failure(UUID id,UUID token) {
        return jdbc.query("select run_status from graph_failure where run_id=? and execution_token=?",(rs,n)->rs.getString(1),id,token).stream().findFirst();
    }
    public void failure(UUID id,UUID token,String segment,String code,String status,Duration delay) {
        jdbc.update("insert into graph_failure(run_id,execution_token,segment,error_code,run_status) values(?,?,?,?,?)",id,token,segment,code,status);
        jdbc.update("update graph_run set status=?,error_code=?,execution_token=null,lease_until=null,next_attempt_at=clock_timestamp()+(? * interval '1 millisecond'),updated_at=clock_timestamp() where id=?",
            status,code,delay.toMillis(),id);
    }
    public void defer(Event event,String key,Duration delay) {
        jdbc.update("""
            insert into graph_dispatch(id,event_id,dedup_key,next_attempt_at)
            select ?,?,?,greatest(next_attempt_at,clock_timestamp()+(? * interval '1 millisecond'),coalesce(lease_until,clock_timestamp()))
                from graph_run where id=? on conflict(event_id,dedup_key) do update
                set status='READY',dispatch_token=null,lease_until=null,published_at=null,
                    next_attempt_at=greatest(graph_dispatch.next_attempt_at,excluded.next_attempt_at)
                where graph_dispatch.status in ('READY','CLAIMED','PUBLISHED')
            """,UUID.randomUUID(),event.id(),key,delay.toMillis(),event.runId());
    }
    public void cancel(UUID id) {
        jdbc.update("update graph_dispatch set status='CANCELLED',dispatch_token=null,lease_until=null where event_id in(select id from graph_event where run_id=?) and status in ('READY','CLAIMED')",id);
    }
    public void cancelOther(UUID id,String segment) {
        jdbc.update("update graph_dispatch set status='CANCELLED',dispatch_token=null,lease_until=null where event_id in(select id from graph_event where run_id=? and segment<>?) and status in ('READY','CLAIMED')",id,segment);
    }
    public void postpone(UUID id,String segment) {
        jdbc.update("""
            update graph_dispatch d set status='READY',dispatch_token=null,lease_until=null,
                next_attempt_at=greatest(d.next_attempt_at,g.next_attempt_at,g.lease_until)
            from graph_event e join graph_run g on g.id=e.run_id
            where d.event_id=e.id and e.run_id=? and e.segment=? and
                (d.status='READY' or (d.status='CLAIMED' and d.lease_until<=clock_timestamp()))
            """,id,segment);
    }
    public List<UUID> candidates(int limit) {
        return jdbc.query("""
            select distinct e.run_id from graph_dispatch d join graph_event e on e.id=d.event_id
            where (d.status='READY' and d.next_attempt_at<=clock_timestamp()) or (d.status='CLAIMED' and d.lease_until<=clock_timestamp())
            order by e.run_id limit ?
            """,(rs,n)->rs.getObject(1,UUID.class),limit);
    }
    public Optional<Dispatch> claim(UUID runId,String segment,Duration lease) {
        UUID token=UUID.randomUUID();
        return jdbc.query("""
            update graph_dispatch set status='CLAIMED',dispatch_token=?,lease_until=clock_timestamp()+(? * interval '1 millisecond'),attempt_count=attempt_count+1
            where id=(select d.id from graph_dispatch d join graph_event e on e.id=d.event_id where e.run_id=? and e.segment=?
                and ((d.status='READY' and d.next_attempt_at<=clock_timestamp()) or (d.status='CLAIMED' and d.lease_until<=clock_timestamp()))
                order by d.next_attempt_at,d.id limit 1 for update of d skip locked)
            returning id,event_id,(select payload::text from graph_event where id=event_id) payload
            """,(rs,n)->new Dispatch(rs.getObject("id",UUID.class),runId,rs.getObject("event_id",UUID.class),token,segment,rs.getString("payload")),
            token,lease.toMillis(),runId,segment).stream().findFirst();
    }
    public boolean settle(Dispatch d,boolean published,Duration delay) {
        return jdbc.update("""
            update graph_dispatch set status=?,dispatch_token=null,lease_until=null,
                published_at=case when ? then clock_timestamp() else null end,next_attempt_at=clock_timestamp()+(? * interval '1 millisecond')
            where id=? and status='CLAIMED' and dispatch_token=? and lease_until>clock_timestamp()
            """,published?"PUBLISHED":"READY",published,delay.toMillis(),d.id(),d.token())==1;
    }
}
