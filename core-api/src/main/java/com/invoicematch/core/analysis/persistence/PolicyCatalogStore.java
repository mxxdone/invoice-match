package com.invoicematch.core.analysis.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Immutable catalog projections and exact SQL ranking, after server-derived scope. */
@Repository
public class PolicyCatalogStore {
    private final JdbcTemplate jdbc;
    public PolicyCatalogStore(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public record Document(UUID id,String contractId,String documentKey,int version,String title,
            LocalDate validFrom,LocalDate validTo,String payloadHash,String embeddingModel,String embeddingVersion,int embeddingDimension) {}
    public record Chunk(UUID id,UUID documentId,int page,int paragraph,String content,String payloadHash,String ruleKey,String effect) {
        public Chunk(UUID id,UUID documentId,int page,int paragraph,String content,String payloadHash) {
            this(id,documentId,page,paragraph,content,payloadHash,null,"INFORMATION");
        }
    }
    public record Hit(Chunk chunk,double score) {}
    public List<Document> scope(String company,String supplier,String po,LocalDate date) {
        return jdbc.query("""
            with latest as (select distinct on(d.contract_id,d.document_key) d.* from policy_document d
            join policy_contract_purchase_order l on l.company_id=d.company_id and l.supplier_id=d.supplier_id
                and l.contract_id=d.contract_id
            where d.company_id=? and d.supplier_id=? and l.purchase_order_id=?
                and d.valid_from<=? and d.valid_to>=?
            order by d.contract_id,d.document_key,d.version desc)
            select * from latest where read_scope='CASE_REVIEW' order by contract_id,document_key limit 21
            """,(rs,n)->new Document(rs.getObject("id",UUID.class),rs.getString("contract_id"),rs.getString("document_key"),
                    rs.getInt("version"),rs.getString("title"),rs.getDate("valid_from").toLocalDate(),rs.getDate("valid_to").toLocalDate(),
                    rs.getString("payload_hash"),rs.getString("embedding_model"),rs.getString("embedding_version"),rs.getInt("embedding_dimension")),company,supplier,po,date,date);
    }
    public record CaseScope(String supplierId,String purchaseOrderId) {}
    public java.util.Optional<CaseScope> caseScope(UUID caseId) {
        return jdbc.query("select c.supplier_id,c.purchase_order_id from invoice_case c join purchase_order_snapshot p"
                + " on p.purchase_order_id=c.purchase_order_id and p.supplier_id=c.supplier_id where c.id=?",
                (rs,n)->new CaseScope(rs.getString(1),rs.getString(2)),caseId).stream().findFirst();
    }
    public void lockScopeRead(String po) {
        jdbc.queryForList("select purchase_order_id from purchase_order_snapshot where purchase_order_id=? for share",po);
    }
    public void lockScopeWrite(String po) {
        jdbc.queryForList("select purchase_order_id from purchase_order_snapshot where purchase_order_id=? for update",po);
    }
    public boolean vectorAvailable() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists(select 1 from pg_extension where extname='vector')",Boolean.class));
    }
    public void contract(String company,String supplier,String contract,String po) {
        jdbc.update("insert into policy_contract values(?,?,?) on conflict do nothing",company,supplier,contract);
        jdbc.update("insert into policy_contract_purchase_order values(?,?,?,?) on conflict do nothing",company,supplier,contract,po);
    }
    public void document(UUID id,String company,String supplier,String contract,String key,int version,String title,
            LocalDate from,LocalDate to,String hash,String model,String embeddingVersion,int dimension) {
        jdbc.update("insert into policy_document(id,company_id,supplier_id,contract_id,document_key,version,title,valid_from,valid_to,"
                + "payload_hash,embedding_model,embedding_version,embedding_dimension,read_scope) values(?,?,?,?,?,?,?,?,?,?,?,?,?,'CASE_REVIEW')",
                id,company,supplier,contract,key,version,title,from,to,hash,model,embeddingVersion,dimension);
    }
    public void chunk(Chunk chunk,float[] embedding) {
        jdbc.update("insert into policy_chunk(id,document_id,page,paragraph,content,payload_hash,embedding,rule_key,effect)"
                + " values(?,?,?,?,?,?,cast(? as real[]),?,?)",chunk.id(),chunk.documentId(),chunk.page(),
                chunk.paragraph(),chunk.content(),chunk.payloadHash(),array(embedding),chunk.ruleKey(),chunk.effect());
    }
    public List<String> conflicts(List<UUID> ids) {
        if(ids.isEmpty()) return List.of();
        return jdbc.query("select rule_key from policy_chunk where document_id=any(cast(? as uuid[]))"
                + " and rule_key is not null and effect in ('ALLOW','DENY') group by rule_key"
                + " having count(distinct effect)>1 order by rule_key limit 11",(rs,n)->rs.getString(1),ids(ids));
    }
    public List<Hit> lexical(List<UUID> ids,String query,int limit) {
        if(ids.isEmpty()) return List.of();
        return jdbc.query("""
            select c.*,ts_rank_cd(to_tsvector('simple',c.content),plainto_tsquery('simple',?)) score
            from policy_chunk c where c.document_id=any(cast(? as uuid[]))
                and to_tsvector('simple',c.content) @@ plainto_tsquery('simple',?)
            order by score desc,c.document_id,c.page,c.paragraph,c.id limit ?
            """,(rs,n)->hit(rs),query,ids(ids),query,limit);
    }
    public List<Hit> vector(List<UUID> ids,String model,String embeddingVersion,int dimension,float[] query,int limit) {
        if(ids.isEmpty()) return List.of();
        return jdbc.query("""
            with scoped as materialized (
                select c.* from policy_chunk c join policy_document d on d.id=c.document_id
                where c.document_id=any(cast(? as uuid[])) and d.embedding_model=? and d.embedding_version=? and d.embedding_dimension=?
                    and cardinality(c.embedding)=?
            )
            select scoped.*,1-(embedding::vector <=> cast(? as vector)) score from scoped
            order by embedding::vector <=> cast(? as vector),document_id,page,paragraph,id limit ?
            """,(rs,n)->hit(rs),ids(ids),model,embeddingVersion,dimension,dimension,vector(query),vector(query),limit);
    }
    private static Hit hit(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Hit(new Chunk(rs.getObject("id",UUID.class),rs.getObject("document_id",UUID.class),rs.getInt("page"),
                rs.getInt("paragraph"),rs.getString("content"),rs.getString("payload_hash"),rs.getString("rule_key"),rs.getString("effect")),rs.getDouble("score"));
    }
    private static String ids(List<UUID> ids) { return "{"+String.join(",",ids.stream().map(UUID::toString).toList())+"}"; }
    private static String array(float[] values) { return "{"+numbers(values)+"}"; }
    private static String vector(float[] values) { return "["+numbers(values)+"]"; }
    private static String numbers(float[] values) {
        var out=new StringBuilder();for(float value:values) { if(!out.isEmpty()) out.append(',');out.append(value); }return out.toString();
    }
}
