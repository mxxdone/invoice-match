package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The machine surface is fail-closed by default. With {@code analysis.worker}
 * disabled, no Bearer (even a plausible one) and no human HTTP Basic credential
 * can authenticate any internal request.
 */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class AnalysisWorkerDisabledIntegrationTest extends AbstractAnalysisIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void disabledSurfaceRejectsEveryInternalRequestWith401() throws Exception {
        UUID runId = UUID.randomUUID();
        String body = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"inputVersion\":1,"
                + "\"evidencePayloadHash\":\"h\",\"workflowVersion\":\"document-parser-v1\"}";

        MvcResult noToken = mvc.perform(post("/internal/analysis-runs/{id}/claim", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        MvcResult bearer = mvc.perform(post("/internal/analysis-runs/{id}/claim", runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + "x".repeat(40))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        MvcResult basic = mvc.perform(post("/internal/analysis-runs/{id}/claim", runId)
                        .with(httpBasic("submitter", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(noToken.getResponse().getStatus()).isEqualTo(401);
        assertThat(bearer.getResponse().getStatus()).isEqualTo(401);
        assertThat(basic.getResponse().getStatus()).isEqualTo(401);
    }
}
