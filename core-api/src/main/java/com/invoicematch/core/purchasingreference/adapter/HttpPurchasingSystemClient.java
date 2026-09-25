package com.invoicematch.core.purchasingreference.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.invoicematch.core.purchasingreference.application.PurchasingSystemClient;
import com.invoicematch.core.purchasingreference.domain.InvalidExternalFactException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderNotFoundException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderStatus;
import com.invoicematch.core.purchasingreference.domain.PurchasingSystemMalformedPayloadException;
import com.invoicematch.core.purchasingreference.domain.PurchasingSystemUnavailableException;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import com.invoicematch.core.shared.domain.DomainValidationException;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * HTTP adapter for the external purchasing system. Transport, HTTP status and
 * JSON parsing failures are translated into the explicit
 * {@code PurchasingReferenceException} hierarchy so no raw HTTP or Jackson error
 * leaks into the domain contract.
 */
@Component
public class HttpPurchasingSystemClient implements PurchasingSystemClient {

    private static final String AGGREGATE_PATH = "/api/purchase-orders/{purchaseOrderId}";

    // Must match the varchar lengths of the V2 snapshot tables.
    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_SUPPLIER_NAME_LENGTH = 200;
    private static final int MAX_ITEM_NAME_LENGTH = 500;

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public HttpPurchasingSystemClient(
            @Qualifier("purchasingSystemRestClient") RestClient restClient, ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public PurchaseOrderAggregate fetch(PurchaseOrderId purchaseOrderId) {
        String body = fetchBody(purchaseOrderId);
        return toDomain(parse(body, purchaseOrderId));
    }

    private String fetchBody(PurchaseOrderId purchaseOrderId) {
        try {
            return restClient
                    .get()
                    .uri(AGGREGATE_PATH, purchaseOrderId.value())
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new PurchaseOrderNotFoundException(purchaseOrderId.value());
        } catch (RestClientResponseException e) {
            throw new PurchasingSystemUnavailableException(
                    "External purchasing system returned " + e.getStatusCode() + " for " + purchaseOrderId, e);
        } catch (ResourceAccessException e) {
            throw new PurchasingSystemUnavailableException(
                    "External purchasing system call failed for " + purchaseOrderId, e);
        } catch (RestClientException e) {
            throw new PurchasingSystemUnavailableException(
                    "External purchasing system call failed for " + purchaseOrderId, e);
        }
    }

    private ExternalPurchaseOrderResponse parse(String body, PurchaseOrderId purchaseOrderId) {
        if (body == null || body.isBlank()) {
            throw new PurchasingSystemMalformedPayloadException(
                    "External purchasing system returned an empty body for " + purchaseOrderId);
        }
        try {
            return objectMapper.readValue(body, ExternalPurchaseOrderResponse.class);
        } catch (JsonProcessingException e) {
            throw new PurchasingSystemMalformedPayloadException(
                    "External purchasing system returned malformed JSON for " + purchaseOrderId, e);
        }
    }

    private PurchaseOrderAggregate toDomain(ExternalPurchaseOrderResponse wire) {
        try {
            if (wire == null) {
                throw new InvalidExternalFactException("External aggregate is empty");
            }
            long snapshotVersion = requiredLong(wire.snapshotVersion(), "snapshotVersion");

            ExternalPurchaseOrder purchaseOrder = required(wire.purchaseOrder(), "purchaseOrder");
            PurchaseOrderId purchaseOrderId = PurchaseOrderId.of(requiredText(
                    purchaseOrder.purchaseOrderId(), "purchaseOrder.purchaseOrderId", MAX_ID_LENGTH));
            long purchaseOrderVersion = requiredLong(purchaseOrder.version(), "purchaseOrder.version");
            PurchaseOrderStatus status = purchaseOrderStatus(purchaseOrder.status());

            ExternalSupplier supplier = required(purchaseOrder.supplier(), "purchaseOrder.supplier");
            SupplierId supplierId = SupplierId.of(
                    requiredText(supplier.supplierId(), "supplier.supplierId", MAX_ID_LENGTH));
            String supplierName =
                    requiredText(supplier.name(), "supplier.name", MAX_SUPPLIER_NAME_LENGTH);

            List<ExternalPurchaseOrderLine> wireLines =
                    required(purchaseOrder.lines(), "purchaseOrder.lines");
            List<PurchaseOrderLineFacts> lines = new ArrayList<>(wireLines.size());
            for (ExternalPurchaseOrderLine line : wireLines) {
                if (line == null) {
                    throw new InvalidExternalFactException("purchaseOrder.lines contains a null element");
                }
                lines.add(new PurchaseOrderLineFacts(
                        requiredText(
                                line.purchaseOrderLineId(),
                                "purchaseOrderLine.purchaseOrderLineId",
                                MAX_ID_LENGTH),
                        requiredText(line.itemId(), "purchaseOrderLine.itemId", MAX_ID_LENGTH),
                        requiredText(line.itemName(), "purchaseOrderLine.itemName", MAX_ITEM_NAME_LENGTH),
                        Quantity.of(requiredInt(line.orderedQuantity(), "purchaseOrderLine.orderedQuantity")),
                        Money.of(requiredLong(line.unitPrice(), "purchaseOrderLine.unitPrice"))));
            }

            List<ExternalReceipt> wireReceipts = required(wire.receipts(), "receipts");
            List<ReceiptFacts> receipts = new ArrayList<>(wireReceipts.size());
            for (ExternalReceipt receipt : wireReceipts) {
                if (receipt == null) {
                    throw new InvalidExternalFactException("receipts contains a null element");
                }
                receipts.add(toReceiptFacts(receipt));
            }

            PurchaseOrderFacts purchaseOrderFacts =
                    new PurchaseOrderFacts(purchaseOrderVersion, status, supplierId, supplierName, lines);
            return new PurchaseOrderAggregate(purchaseOrderId, snapshotVersion, purchaseOrderFacts, receipts);
        } catch (DomainValidationException e) {
            throw new InvalidExternalFactException("External aggregate contains an invalid value", e);
        }
    }

    private ReceiptFacts toReceiptFacts(ExternalReceipt receipt) {
        String receiptId = requiredText(receipt.receiptId(), "receipt.receiptId", MAX_ID_LENGTH);
        ReceiptStatus status = receiptStatus(receipt.status());
        if (receipt.receiptDate() == null) {
            throw new InvalidExternalFactException("receipt.receiptDate is required for " + receiptId);
        }
        long version = requiredLong(receipt.version(), "receipt.version");

        List<ExternalReceiptLine> wireLines = required(receipt.lines(), "receipt.lines");
        List<ReceiptLineFacts> lines = new ArrayList<>(wireLines.size());
        for (ExternalReceiptLine line : wireLines) {
            if (line == null) {
                throw new InvalidExternalFactException("receipt.lines contains a null element for " + receiptId);
            }
            lines.add(new ReceiptLineFacts(
                    requiredText(line.receiptLineId(), "receiptLine.receiptLineId", MAX_ID_LENGTH),
                    requiredLong(line.version(), "receiptLine.version"),
                    requiredText(line.purchaseOrderLineId(), "receiptLine.purchaseOrderLineId", MAX_ID_LENGTH),
                    ConfirmedQuantity.of(requiredInt(line.confirmedQuantity(), "receiptLine.confirmedQuantity"))));
        }
        return new ReceiptFacts(receiptId, status, receipt.receiptDate(), version, lines);
    }

    private PurchaseOrderStatus purchaseOrderStatus(String value) {
        String status = requiredText(value, "purchaseOrder.status", MAX_ID_LENGTH);
        try {
            return PurchaseOrderStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidExternalFactException("Unknown purchase order status: " + status, e);
        }
    }

    private ReceiptStatus receiptStatus(String value) {
        String status = requiredText(value, "receipt.status", MAX_ID_LENGTH);
        try {
            return ReceiptStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidExternalFactException("Unknown receipt status: " + status, e);
        }
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new InvalidExternalFactException(field + " is required");
        }
        return value;
    }

    private static String requiredText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new InvalidExternalFactException(field + " must not be blank");
        }
        if (value.length() > maxLength) {
            throw new InvalidExternalFactException(
                    field + " exceeds the maximum length of " + maxLength + ": " + value.length());
        }
        return value;
    }

    private static long requiredLong(Long value, String field) {
        if (value == null) {
            throw new InvalidExternalFactException(field + " is required");
        }
        return value;
    }

    private static int requiredInt(Integer value, String field) {
        if (value == null) {
            throw new InvalidExternalFactException(field + " is required");
        }
        return value;
    }
}
