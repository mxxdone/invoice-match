package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.invoicematch.core.support.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The result route carries the larger body cap, the other internal routes keep
 * the default, and an oversized body is rejected with 413 before security or any
 * business effect. The cap is enforced on the read stream, so it also covers a
 * chunked body with no Content-Length (see {@code BoundedRequestBodyFilterTest}).
 */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class AnalysisResultBodyCapIntegrationTest extends AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void smallCaps(DynamicPropertyRegistry registry) {
        registry.add("http.request.max-body-bytes", () -> 64);
        registry.add("http.request.analysis-result-max-body-bytes", () -> 128);
    }

    @Autowired
    MockMvc mvc;
    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        jdbc.execute("truncate table invoice_case cascade");
    }

    @Test
    void oversizedResultBodyIs413AndOversizedClaimBodyUsesTheDefaultCap() throws Exception {
        UUID runId = UUID.randomUUID();
        byte[] oversizedForResult = "x".repeat(200).getBytes(StandardCharsets.UTF_8);
        byte[] oversizedForClaim = "x".repeat(200).getBytes(StandardCharsets.UTF_8);

        MvcResult result = mvc.perform(post("/internal/analysis-runs/{id}/results", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedForResult))
                .andReturn();
        MvcResult claim = mvc.perform(post("/internal/analysis-runs/{id}/claim", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedForClaim))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(413);
        assertThat(claim.getResponse().getStatus()).isEqualTo(413);
        assertThat(jdbc.queryForObject("select count(*) from analysis_document_result", Integer.class)).isZero();
    }

    @Test
    void aBodyBetweenTheTwoCapsReachesTheResultRouteButNotTheClaimRoute() throws Exception {
        UUID runId = UUID.randomUUID();
        byte[] betweenCaps = "x".repeat(100).getBytes(StandardCharsets.UTF_8);

        MvcResult result = mvc.perform(post("/internal/analysis-runs/{id}/results", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(betweenCaps))
                .andReturn();
        MvcResult claim = mvc.perform(post("/internal/analysis-runs/{id}/claim", runId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(betweenCaps))
                .andReturn();

        // The result route accepts the stream (then the disabled machine surface is 401);
        // the claim route still enforces the default cap.
        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(claim.getResponse().getStatus()).isEqualTo(413);
    }
}
