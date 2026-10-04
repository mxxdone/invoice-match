package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.*;
import com.invoicematch.core.analysis.application.*;
import com.invoicematch.core.matching.application.RunMatchCommand;
import com.invoicematch.core.support.TestActors;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Default AI-off deployment still parses and matches documents normally. */
class ProposalDisabledIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {
    @Autowired ProposalService proposals;
    @Autowired ProposalProperties properties;
    @Autowired GraphExecutionService graph;
    @Autowired GraphProperties graphProperties;
    @Test void ordinaryParserAndHumanMatchingRemainUsableWithoutAnyAiReservation() {
        assertThat(properties.enabled()).isFalse();
        var f=preparePdfRun(1);var c=claim(f);var d=f.documents().getFirst();
        execution.recordResult(f.runId(),resultCommand(f,c.claimToken(),d.documentId(),"SUCCESS",pdfResult(d,"A4 용지"),null));
        TestActors.run("operator","OPERATOR",()->matchingService.run(new RunMatchCommand(f.caseId(),UUID.randomUUID().toString())));
        long version=caseVersion(f.caseId());
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->proposals.reserve(f.caseId(),
                new ProposalService.ReserveCommand(UUID.randomUUID().toString(),version))))
                .isInstanceOf(AnalysisConflictException.class).extracting(e->((AnalysisConflictException)e).code()).isEqualTo("AI_DISABLED");
        assertThat(jdbc.queryForObject("select status from analysis_run where id=?",String.class,f.runId())).isEqualTo("COMPLETED");
        assertThat(count("proposal_run")).isZero();assertThat(count("proposal_request_outbox")).isZero();
        assertThat(graphProperties.enabled()).isFalse();
        assertThatThrownBy(()->TestActors.call("operator","OPERATOR",()->graph.reserve(f.caseId(),
                new ProposalService.ReserveCommand(UUID.randomUUID().toString(),version))))
                .isInstanceOf(AnalysisConflictException.class).extracting(e->((AnalysisConflictException)e).code()).isEqualTo("GRAPH_DISABLED");
        assertThat(count("graph_run")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='graph:reserve'",Integer.class)).isZero();
        assertThat(caseVersion(f.caseId())).isEqualTo(version);assertThat(count("payment_request")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from idempotency_record where scope='proposal:reserve'",Integer.class)).isZero();
    }
}
