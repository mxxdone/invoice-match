package com.invoicematch.core.purchasingreference.adapter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
record ExternalSupplier(String supplierId, String name) {
}
