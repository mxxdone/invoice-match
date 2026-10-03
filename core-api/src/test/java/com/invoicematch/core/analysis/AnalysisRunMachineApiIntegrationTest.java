package com.invoicematch.core.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.invoicematch.core.analysis.application.ClaimOutcome;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Machine HTTP surface against real PostgreSQL. Exercises the exact allowed
 * method/paths, the isolated Bearer authority, {@code no-store}, status mapping
 * (400/401/404/409) and the authoritative frozen manifest in the claim response.
 */
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
class AnalysisRunMachineApiIntegrationTest extends AbstractAnalysisExecutionIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void claimReturnsManifestAndNoStore() throws Exception {
        RunFixture fixture = preparePdfRun(2);

        MvcResult result = mvc.perform(post("/internal/analysis-runs/{id}/claim", fixture.runId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(claimBody(fixture))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        JsonNode body = json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.get("disposition").asText()).isEqualTo("CLAIMED");
        assertThat(body.get("claimToken").asText()).isNotBlank();
        assertThat(body.get("leaseUntil").asText()).isNotBlank();
        assertThat(body.get("documents").size()).isEqualTo(2);
        assertThat(body.get("documents").get(0).has("documentId")).isTrue();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("uploads/", "originals/", "objectKey", "secret");
    }

    @Test
    void missingOrWrongTokenIs401() throws Exception {
        RunFixture fixture = preparePdfRun(1);
        String body = toJson(claimBody(fixture));

        MvcResult missing = mvc.perform(post("/internal/analysis-runs/{id}/claim", fixture.runId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        MvcResult wrong = mvc.perform(post("/internal/analysis-runs/{id}/claim", fixture.runId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not-the-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(missing.getResponse().getStatus()).isEqualTo(401);
        assertThat(wrong.getResponse().getStatus()).isEqualTo(401);
        assertThat(missing.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("QUEUED");
    }

    @Test
    void humanBasicCredentialCannotAuthenticateTheInternalSurface() throws Exception {
        RunFixture fixture = preparePdfRun(1);

        MvcResult result = mvc.perform(post("/internal/analysis-runs/{id}/claim", fixture.runId())
                        .with(httpBasic("submitter", "password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(claimBody(fixture))))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(run(fixture.caseId(), 1).get("status")).isEqualTo("QUEUED");
    }

    @Test
    void unknownRunIs404AndMalformedUuidIs400() throws Exception {
        RunFixture fixture = preparePdfRun(1);
        String body = toJson(claimBody(fixture));

        MvcResult notFound = mvc.perform(post("/internal/analysis-runs/{id}/claim", UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();
        MvcResult malformed = mvc.perform(post("/internal/analysis-runs/not-a-uuid/claim")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andReturn();

        assertThat(notFound.getResponse().getStatus()).isEqualTo(404);
        assertThat(malformed.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void missingFieldAndWrongIdentityAreRejected() throws Exception {
        RunFixture fixture = preparePdfRun(1);

        MvcResult missing = mvc.perform(post("/internal/analysis-runs/{id}/claim", fixture.runId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"inputVersion\":1}"))
                .andReturn();
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);

        Map<String, Object> wrong = claimBody(fixture);
        wrong.put("eventId", UUID.randomUUID().toString());
        MvcResult mismatch = mvc.perform(post("/internal/analysis-runs/{id}/claim", fixture.runId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(wrong)))
                .andReturn();
        assertThat(mismatch.getResponse().getStatus()).isEqualTo(409);
    }

    @Test
    void resultSchemaViolationIs400AndMetadataMismatchIs409() throws Exception {
        RunFixture fixture = preparePdfRun(1);
        SeededDocument document = fixture.documents().get(0);
        String claimToken = claimTokenOverHttp(fixture);

        ObjectNode badSchema = pdfResult(document, "text");
        badSchema.put("schemaVersion", "bogus");
        MvcResult schema = postResult(fixture, claimToken, document.documentId(), "SUCCESS", badSchema, null);
        assertThat(schema.getResponse().getStatus()).isEqualTo(400);

        ObjectNode badMetadata = pdfResult(document, "text");
        ((ObjectNode) badMetadata.get("source")).put("sha256", "0".repeat(64));
        MvcResult metadata = postResult(fixture, claimToken, document.documentId(), "SUCCESS", badMetadata, null);
        assertThat(metadata.getResponse().getStatus()).isEqualTo(409);
        assertThat(count("analysis_document_result")).isZero();
    }

    @Test
    void unknownInternalPathAndWrongMethodAreNotAllowed() throws Exception {
        UUID runId = UUID.randomUUID();

        MvcResult unknownPath = mvc.perform(post("/internal/analysis-runs/{id}/bogus", runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();
        MvcResult wrongMethod = mvc.perform(get("/internal/analysis-runs/{id}/claim", runId)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN))
                .andReturn();

        assertThat(unknownPath.getResponse().getStatus()).isIn(401, 403, 404);
        assertThat(wrongMethod.getResponse().getStatus()).isIn(401, 403, 405);
        assertThat(unknownPath.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain("Exception", "com.invoicematch");
    }

    private String claimTokenOverHttp(RunFixture fixture) throws Exception {
        MvcResult result = mvc.perform(post("/internal/analysis-runs/{id}/claim", fixture.runId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(claimBody(fixture))))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return json.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("claimToken")
                .asText();
    }

    private MvcResult postResult(
            RunFixture fixture, String token, UUID documentId, String outcome, JsonNode result, String errorCode)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("claimToken", token);
        body.put("inputVersion", fixture.inputVersion());
        body.put("evidencePayloadHash", fixture.evidencePayloadHash());
        body.put("documentId", documentId.toString());
        body.put("outcome", outcome);
        body.put("result", result);
        body.put("errorCode", errorCode);
        return mvc.perform(post("/internal/analysis-runs/{id}/results", fixture.runId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + WORKER_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(toJson(body)))
                .andReturn();
    }

    private Map<String, Object> claimBody(RunFixture fixture) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", fixture.eventId().toString());
        body.put("inputVersion", fixture.inputVersion());
        body.put("evidencePayloadHash", fixture.evidencePayloadHash());
        body.put("workflowVersion", fixture.workflowVersion());
        return body;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}

