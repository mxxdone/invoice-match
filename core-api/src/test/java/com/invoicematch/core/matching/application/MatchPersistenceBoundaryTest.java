package com.invoicematch.core.matching.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.invoicematch.core.invoicecase.application.MatchCaseSnapshot;
import com.invoicematch.core.purchasingreference.application.CurrentPurchaseOrderSnapshot;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Structural boundary test: match_result persistence is reachable only from the
 * secured, idempotent OPERATOR {@code MatchingService.run} and from the complete
 * public {@code ReviewService.recordMapping} orchestration. There is no public
 * raw append seam, the planner cannot persist, and the internal re-match
 * collaborator is package-private inside {@code review.application}.
 */
class MatchPersistenceBoundaryTest {

    @Test
    void matchingServiceHasNoPublicRawAppendSeam() throws Exception {
        Method append = MatchingService.class.getDeclaredMethod(
                "appendResult", MatchCaseSnapshot.class, CurrentPurchaseOrderSnapshot.class);

        assertThat(Modifier.isPrivate(append.getModifiers())).isTrue();

        List<String> publicMethodNames = Arrays.stream(MatchingService.class.getMethods())
                .filter(method -> method.getDeclaringClass().equals(MatchingService.class))
                .map(Method::getName)
                .toList();
        assertThat(publicMethodNames)
                .doesNotContain("appendResult", "rematchForMapping", "rematch");
    }

    @Test
    void thePlannerCannotPersistBecauseItHasNoRepository() {
        List<String> fieldTypes = Arrays.stream(MatchResultPlanner.class.getDeclaredFields())
                .map(field -> field.getType().getSimpleName())
                .toList();

        assertThat(fieldTypes)
                .as("the pure planner must not be able to reach match_result persistence")
                .noneMatch(type -> type.contains("Repository"));
        assertThat(Arrays.stream(MatchResultPlanner.class.getMethods())
                        .map(method -> method.getReturnType().getSimpleName()))
                .noneMatch(type -> type.equals("MatchResult"));
    }

    @Test
    void internalRematchIsPackagePrivateInsideTheReviewPackage() throws Exception {
        Class<?> type = Class.forName("com.invoicematch.core.review.application.InternalMatchRematch");

        assertThat(Modifier.isPublic(type.getModifiers())).isFalse();
        assertThat(type.getPackageName()).isEqualTo("com.invoicematch.core.review.application");

        Method append = type.getDeclaredMethod("append", UUID.class);
        assertThat(Modifier.isPublic(append.getModifiers())).isFalse();
        assertThat(Modifier.isPrivate(append.getModifiers())).isFalse();
    }

    @Test
    void reviewMappingIsTheOtherAuthorizedPublicOrchestration() throws Exception {
        Class<?> reviewService = Class.forName("com.invoicematch.core.review.application.ReviewService");
        Method recordMapping = reviewService.getMethod(
                "recordMapping", Class.forName("com.invoicematch.core.review.application.RecordMappingDecisionCommand"));

        assertThat(Modifier.isPublic(recordMapping.getModifiers())).isTrue();
        assertThat(Arrays.stream(reviewService.getMethods())
                        .filter(method -> method.getDeclaringClass().equals(reviewService))
                        .map(Method::getName))
                .doesNotContain("rematch", "rematchForMapping", "appendResult");
    }
}
