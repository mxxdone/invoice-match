package com.invoicematch.core.analysis.application;
import java.util.UUID;
public record AnalysisFailureCommand(UUID claimToken, Integer inputVersion, String evidencePayloadHash, String errorCode) {}
