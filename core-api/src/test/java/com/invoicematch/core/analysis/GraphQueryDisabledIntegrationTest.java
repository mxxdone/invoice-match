package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;

import com.invoicematch.core.analysis.application.GraphProperties;
import com.invoicematch.core.analysis.application.GraphQueryService;
import com.invoicematch.core.analysis.application.GraphViews;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Default AI-off deployment still serves stored graph history without any machine surface. */
class GraphQueryDisabledIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @Autowired GraphQueryService queries;
    @Autowired GraphProperties graphProperties;

    @Test void historyIsReadableWhileGraphIsDisabledAndUnsupportedMetadataOnly() {
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"paper 60 2500"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        UUID bundle=jdbc.queryForObject("select id from evidence_bundle where invoice_case_id=? order by version_number desc limit 1",UUID.class,f.caseId());
        UUID match=jdbc.queryForObject("select id from match_result where invoice_case_id=? order by result_number desc limit 1",UUID.class,f.caseId());
        jdbc.execute("alter table graph_run disable trigger trg_graph_writer_schema");
        UUID legacy=UUID.randomUUID();
        try {
            jdbc.update("insert into graph_run(id,invoice_case_id,case_version,evidence_bundle_id,parser_run_id,match_result_id,"
                    + "context,context_hash,workflow_version,graph_version,serializer_version,checkpoint_schema,status,cost_ceiling,active_segment) "
                    + "values(?,?,?,?,?,?,'{\"legacy\":true}'::jsonb,?,'ai-review-v2','invoice-review-graph-v1','graph-checkpoint-json-v1',2,'QUEUED',1,'START')",
                legacy,f.caseId(),caseVersion(f.caseId()),bundle,f.runId(),match,"e".repeat(64));
        } finally {
            jdbc.execute("alter table graph_run enable trigger trg_graph_writer_schema");
        }
        assertThat(graphProperties.enabled()).isFalse();
        GraphViews.Page page=TestActors.call("operator","OPERATOR",()->queries.list(f.caseId()));
        assertThat(page.enabled()).isFalse();
        assertThat(page.history()).hasSize(1);
        assertThat(page.latest().run().id()).isEqualTo(legacy);
        assertThat(page.latest().run().supported()).isFalse();
        assertThat(page.latest().pending()).isNull();
        assertThat(page.latest().review()).isNull();
        assertThat(page.latest().payload()).isNull();
        assertThat(page.latest().sources()).isEmpty();
    }
}
