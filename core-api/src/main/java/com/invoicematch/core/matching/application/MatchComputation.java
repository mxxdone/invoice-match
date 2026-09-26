package com.invoicematch.core.matching.application;

/**
 * The canonical JSON payload of a match result and its SHA-256 hash. The same
 * semantic inputs always produce the same pair; the payload deliberately omits
 * the result id and creation timestamp.
 */
public record MatchComputation(String canonicalJson, String resultHash) {
}
