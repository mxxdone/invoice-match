package com.invoicematch.core.security;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

/**
 * Pure, framework-free authorization rules. Keeping the decision logic here
 * (rather than in a controller expression or a data-access check) makes the
 * authorization matrix explicit, unit-testable and reusable by P1-07's approval
 * flow.
 */
public final class AuthorizationPolicy {

    private AuthorizationPolicy() {
    }

    /** Requires that the actor holds at least one of the given roles. */
    public static void requireAnyRole(Actor actor, Role... roles) {
        Objects.requireNonNull(actor, "actor");
        for (Role role : roles) {
            if (actor.hasRole(role)) {
                return;
            }
        }
        throw new ForbiddenActionException(
                "Actor " + actor.username() + " requires one of " + Arrays.toString(roles));
    }

    /**
     * A SUBMITTER may edit/submit a case only when they are its authoritative
     * submitter. Ownership is compared on the authenticated username, never on a
     * request field.
     */
    public static void requireSubmitterOwner(Actor actor, UUID caseId, String submittedBy) {
        requireAnyRole(actor, Role.SUBMITTER);
        if (!actor.username().equals(submittedBy)) {
            throw new ForbiddenActionException(
                    "Actor " + actor.username() + " is not the submitter of case " + caseId);
        }
    }

    /**
     * Case reads are allowed to APPROVER and OPERATOR for any case, and to a
     * SUBMITTER only for a case they submitted. This keeps a submitter from
     * reading other submitters' cases while reviewers and operators work across
     * cases.
     */
    public static void requireCaseRead(Actor actor, UUID caseId, String submittedBy) {
        Objects.requireNonNull(actor, "actor");
        if (actor.hasRole(Role.APPROVER) || actor.hasRole(Role.OPERATOR)) {
            return;
        }
        if (actor.hasRole(Role.SUBMITTER) && actor.username().equals(submittedBy)) {
            return;
        }
        throw new ForbiddenActionException(
                "Actor " + actor.username() + " may not read case " + caseId);
    }

    /**
     * Reusable P1-07 approval rule: the actor must be an APPROVER and must not
     * be the person who submitted the case. The comparison is on the canonical
     * username, so the same person cannot approve regardless of how their roles
     * are represented.
     */
    public static void requireApproverNotSubmitter(Actor actor, String submittedBy) {
        requireAnyRole(actor, Role.APPROVER);
        if (actor.username().equals(submittedBy)) {
            throw new ForbiddenActionException(
                    "Actor " + actor.username() + " submitted the case and may not approve it");
        }
    }

    /** Rejects the submission of another actor's case without leaking its data. */
    public static void requireSameActor(Actor actor, String submittedBy) {
        if (!actor.username().equals(submittedBy)) {
            throw new ForbiddenActionException("Actor " + actor.username() + " does not own this case");
        }
    }
}
