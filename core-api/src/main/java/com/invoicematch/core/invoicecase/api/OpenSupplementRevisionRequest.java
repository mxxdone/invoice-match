package com.invoicematch.core.invoicecase.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record OpenSupplementRevisionRequest(
        @NotBlank @Size(max = 128) String requestId, @NotNull @Min(0) Long expectedCaseVersion) {
}
