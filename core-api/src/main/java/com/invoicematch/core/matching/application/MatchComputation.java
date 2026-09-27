package com.invoicematch.core.matching.application;

import com.invoicematch.core.matching.domain.MatchLineOutcome;
import java.util.List;

/**
 * The canonical JSON payload of a match result, its SHA-256 hash, and the typed
 * per-line outcomes it was built from. The same semantic inputs always produce
 * the same pair; the payload deliberately omits the result id and creation
 * timestamp.
 *
 * <p>The typed {@code normal} flag and {@code lineOutcomes} let a caller (in
 * particular approval) derive an allocation plan from verified typed values
 * instead of re-parsing stored JSON.
 */
public record MatchComputation(
        String canonicalJson, String resultHash, boolean normal, List<MatchLineOutcome> lineOutcomes) {

    public MatchComputation {
        lineOutcomes = List.copyOf(lineOutcomes);
    }
}
