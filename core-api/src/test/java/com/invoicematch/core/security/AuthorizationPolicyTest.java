package com.invoicematch.core.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure authorization rule tests. They prove the self-approval rule is keyed on
 * the canonical username, so the same person cannot approve no matter how their
 * roles are represented.
 */
class AuthorizationPolicyTest {

    private static final UUID CASE_ID = UUID.randomUUID();

    @Test
    void approverCanApproveAnothersCase() {
        Actor approver = new Actor("approver", Set.of(Role.APPROVER));

        assertThatCode(() -> AuthorizationPolicy.requireApproverNotSubmitter(approver, "submitter"))
                .doesNotThrowAnyException();
    }

    @Test
    void submitterCannotApprove() {
        Actor submitter = new Actor("submitter", Set.of(Role.SUBMITTER));

        assertThatThrownBy(() -> AuthorizationPolicy.requireApproverNotSubmitter(submitter, "submitter"))
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("requires one of");
    }

    @Test
    void sameUsernameCannotApproveEvenWhenAlsoAnApprover() {
        Actor submitterApprover = new Actor("dana", Set.of(Role.SUBMITTER, Role.APPROVER));

        assertThatThrownBy(() -> AuthorizationPolicy.requireApproverNotSubmitter(submitterApprover, "dana"))
                .isInstanceOf(ForbiddenActionException.class)
                .hasMessageContaining("may not approve");
    }

    @Test
    void submitterOwnerRequiresBothSubmitterRoleAndOwnership() {
        Actor owner = new Actor("dana", Set.of(Role.SUBMITTER));
        Actor other = new Actor("erin", Set.of(Role.SUBMITTER));
        Actor approver = new Actor("frank", Set.of(Role.APPROVER));

        assertThatCode(() -> AuthorizationPolicy.requireSubmitterOwner(owner, CASE_ID, "dana"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuthorizationPolicy.requireSubmitterOwner(other, CASE_ID, "dana"))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> AuthorizationPolicy.requireSubmitterOwner(approver, CASE_ID, "frank"))
                .isInstanceOf(ForbiddenActionException.class);
    }

    @Test
    void submitterMayReadOnlyOwnCaseButReviewerAndOperatorReadAnyCase() {
        Actor owner = new Actor("dana", Set.of(Role.SUBMITTER));
        Actor otherSubmitter = new Actor("erin", Set.of(Role.SUBMITTER));
        Actor approver = new Actor("frank", Set.of(Role.APPROVER));
        Actor operator = new Actor("gina", Set.of(Role.OPERATOR));

        assertThatCode(() -> AuthorizationPolicy.requireCaseRead(owner, CASE_ID, "dana"))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuthorizationPolicy.requireCaseRead(approver, CASE_ID, "dana"))
                .doesNotThrowAnyException();
        assertThatCode(() -> AuthorizationPolicy.requireCaseRead(operator, CASE_ID, "dana"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> AuthorizationPolicy.requireCaseRead(otherSubmitter, CASE_ID, "dana"))
                .isInstanceOf(ForbiddenActionException.class);
        assertThatThrownBy(() -> AuthorizationPolicy.requireCaseRead(Actor.system(), CASE_ID, "dana"))
                .isInstanceOf(ForbiddenActionException.class);
    }
}
