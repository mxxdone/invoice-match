package com.invoicematch.core.invoicecase.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ReplaceDraftLinesRequest(
        @NotBlank @Size(max = 128) String requestId,
        @NotNull @Min(0) Long expectedCaseVersion,
        @NotNull @Valid List<@NotNull @Valid LineRequest> lines) {

    public record LineRequest(
            @Min(1) int lineNumber,
            @NotBlank @Size(max = 500) String rawItemName,
            @Min(1) int quantity,
            @Min(0) long unitPrice,
            @Size(max = 64) String confirmedItemId) {
    }
}
