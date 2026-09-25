package com.invoicematch.core.invoicecase.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.SupplierId;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

class InvoiceCaseStateTransitionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private record Transition(InvoiceCaseStatus from, InvoiceCaseStatus to) {
    }

    /** The confirmed non-AI Phase 1 state contract. */
    private static final Set<Transition> ALLOWED = Set.of(
            new Transition(InvoiceCaseStatus.DRAFT, InvoiceCaseStatus.SUBMITTED),
            new Transition(InvoiceCaseStatus.SUBMITTED, InvoiceCaseStatus.REVIEW_PENDING),
            new Transition(InvoiceCaseStatus.REVIEW_PENDING, InvoiceCaseStatus.SUPPLEMENT_REQUIRED),
            new Transition(InvoiceCaseStatus.SUPPLEMENT_REQUIRED, InvoiceCaseStatus.SUBMITTED),
            new Transition(InvoiceCaseStatus.REVIEW_PENDING, InvoiceCaseStatus.REJECTED),
            new Transition(InvoiceCaseStatus.REVIEW_PENDING, InvoiceCaseStatus.EXPORT_PENDING),
            new Transition(InvoiceCaseStatus.EXPORT_PENDING, InvoiceCaseStatus.EXPORTED));

    @Test
    void newCaseStartsAsDraft() {
        InvoiceCase invoiceCase = newCase();
        assertThat(invoiceCase.status()).isEqualTo(InvoiceCaseStatus.DRAFT);
        assertThat(invoiceCase.submittedAt()).isNull();
    }

    static Stream<Arguments> allowedTransitions() {
        return ALLOWED.stream().map(transition -> Arguments.of(transition.from(), transition.to()));
    }

    static Stream<Arguments> rejectedTransitions() {
        List<Arguments> rejected = new ArrayList<>();
        for (InvoiceCaseStatus from : InvoiceCaseStatus.values()) {
            for (InvoiceCaseStatus to : InvoiceCaseStatus.values()) {
                if (!ALLOWED.contains(new Transition(from, to))) {
                    rejected.add(Arguments.of(from, to));
                }
            }
        }
        return rejected.stream();
    }

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void acceptsValidPhaseOneTransitions(InvoiceCaseStatus from, InvoiceCaseStatus to) {
        InvoiceCase invoiceCase = caseIn(from);

        invoiceCase.transitionTo(to, T0.plusSeconds(1));

        assertThat(invoiceCase.status()).isEqualTo(to);
    }

    @ParameterizedTest
    @MethodSource("rejectedTransitions")
    void rejectsInvalidTransitionsAndKeepsStatus(InvoiceCaseStatus from, InvoiceCaseStatus to) {
        InvoiceCase invoiceCase = caseIn(from);

        assertThatThrownBy(() -> invoiceCase.transitionTo(to, T0.plusSeconds(1)))
                .isInstanceOf(InvalidStateTransitionException.class);

        assertThat(invoiceCase.status()).isEqualTo(from);
    }

    @ParameterizedTest
    @EnumSource(InvoiceCaseStatus.class)
    void enumContractMatchesConfirmedTransitions(InvoiceCaseStatus from) {
        Set<InvoiceCaseStatus> expected = ALLOWED.stream()
                .filter(transition -> transition.from() == from)
                .map(Transition::to)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(InvoiceCaseStatus.class)));

        assertThat(from.allowedTransitions()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void stampsSubmittedAtOnEachSubmission() {
        InvoiceCase invoiceCase = caseIn(InvoiceCaseStatus.SUPPLEMENT_REQUIRED);
        Instant submittedAt = T0.plusSeconds(500);

        invoiceCase.transitionTo(InvoiceCaseStatus.SUBMITTED, submittedAt);

        assertThat(invoiceCase.submittedAt()).isEqualTo(submittedAt);
        assertThat(invoiceCase.updatedAt()).isEqualTo(submittedAt);
    }

    @Test
    void terminalStatesHaveNoOutgoingTransitions() {
        assertThat(InvoiceCaseStatus.REJECTED.isTerminal()).isTrue();
        assertThat(InvoiceCaseStatus.EXPORTED.isTerminal()).isTrue();
        assertThat(InvoiceCaseStatus.DRAFT.isTerminal()).isFalse();
    }

    private static InvoiceCase caseIn(InvoiceCaseStatus target) {
        InvoiceCase invoiceCase = newCase();
        List<InvoiceCaseStatus> path = pathTo(target);
        for (int index = 0; index < path.size(); index++) {
            invoiceCase.transitionTo(path.get(index), T0.plusSeconds(index + 1L));
        }
        return invoiceCase;
    }

    private static List<InvoiceCaseStatus> pathTo(InvoiceCaseStatus target) {
        return switch (target) {
            case DRAFT -> List.of();
            case SUBMITTED -> List.of(InvoiceCaseStatus.SUBMITTED);
            case REVIEW_PENDING -> List.of(InvoiceCaseStatus.SUBMITTED, InvoiceCaseStatus.REVIEW_PENDING);
            case SUPPLEMENT_REQUIRED ->
                    List.of(InvoiceCaseStatus.SUBMITTED, InvoiceCaseStatus.REVIEW_PENDING,
                            InvoiceCaseStatus.SUPPLEMENT_REQUIRED);
            case REJECTED ->
                    List.of(InvoiceCaseStatus.SUBMITTED, InvoiceCaseStatus.REVIEW_PENDING, InvoiceCaseStatus.REJECTED);
            case EXPORT_PENDING ->
                    List.of(InvoiceCaseStatus.SUBMITTED, InvoiceCaseStatus.REVIEW_PENDING,
                            InvoiceCaseStatus.EXPORT_PENDING);
            case EXPORTED ->
                    List.of(InvoiceCaseStatus.SUBMITTED, InvoiceCaseStatus.REVIEW_PENDING,
                            InvoiceCaseStatus.EXPORT_PENDING, InvoiceCaseStatus.EXPORTED);
        };
    }

    private static InvoiceCase newCase() {
        return InvoiceCase.create(
                InvoiceCaseId.newId(), SupplierId.of("SUP-1"), PurchaseOrderId.of("PO-1"), "INV-1", "INV1", T0);
    }
}
