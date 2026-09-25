package com.invoicematch.core.purchasingreference.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.invoicematch.core.purchasingreference.domain.ExternalFactUnconfirmedException;
import com.invoicematch.core.purchasingreference.domain.ExternalReferenceMismatchException;
import com.invoicematch.core.purchasingreference.domain.InvalidExternalFactException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderLineFacts;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderStatus;
import com.invoicematch.core.purchasingreference.domain.ReceiptFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptLineFacts;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import com.invoicematch.core.purchasingreference.domain.RefreshOutcome;
import com.invoicematch.core.purchasingreference.domain.RefreshResult;
import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.shared.domain.SupplierId;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PurchasingReferenceServiceTest {

    private static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");
    private static final RefreshPurchaseOrderCommand COMMAND =
            new RefreshPurchaseOrderCommand(PurchaseOrderId.of("PO-1"), SupplierId.of("SUP-1"));

    private PurchasingSystemClient client;
    private PurchaseOrderSnapshotStore store;
    private PurchasingReferenceService service;

    @BeforeEach
    void setUp() {
        client = mock(PurchasingSystemClient.class);
        store = mock(PurchaseOrderSnapshotStore.class);
        service = new PurchasingReferenceService(
                client, new SnapshotPayloadHasher(), store, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    void appliesValidConfirmedAggregate() {
        when(client.fetch(COMMAND.purchaseOrderId())).thenReturn(aggregateWith("PO-1", "SUP-1", PurchaseOrderStatus.CONFIRMED, confirmedReceipt("POL-1")));
        when(store.apply(any(), anyString(), anyString(), any())).thenReturn(RefreshResult.of(RefreshOutcome.CREATED, 1));

        RefreshResult result = service.refresh(COMMAND);

        assertThat(result.outcome()).isEqualTo(RefreshOutcome.CREATED);
        verify(store).apply(any(), anyString(), anyString(), any());
    }

    @Test
    void rejectsPurchaseOrderIdentityMismatch() {
        when(client.fetch(COMMAND.purchaseOrderId()))
                .thenReturn(aggregateWith("PO-OTHER", "SUP-1", PurchaseOrderStatus.CONFIRMED, confirmedReceipt("POL-1")));

        assertThatThrownBy(() -> service.refresh(COMMAND))
                .isInstanceOf(ExternalReferenceMismatchException.class);
        verifyNoInteractions(store);
    }

    @Test
    void rejectsSupplierMismatch() {
        when(client.fetch(COMMAND.purchaseOrderId()))
                .thenReturn(aggregateWith("PO-1", "SUP-OTHER", PurchaseOrderStatus.CONFIRMED, confirmedReceipt("POL-1")));

        assertThatThrownBy(() -> service.refresh(COMMAND))
                .isInstanceOf(ExternalReferenceMismatchException.class);
        verifyNoInteractions(store);
    }

    @Test
    void rejectsUnconfirmedPurchaseOrder() {
        when(client.fetch(COMMAND.purchaseOrderId()))
                .thenReturn(aggregateWith("PO-1", "SUP-1", PurchaseOrderStatus.UNCONFIRMED, confirmedReceipt("POL-1")));

        assertThatThrownBy(() -> service.refresh(COMMAND))
                .isInstanceOf(ExternalFactUnconfirmedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void rejectsUnconfirmedReceipt() {
        ReceiptFacts unconfirmed = new ReceiptFacts(
                "RCV-1",
                ReceiptStatus.UNCONFIRMED,
                LocalDate.of(2026, 1, 5),
                1,
                List.of(new ReceiptLineFacts("RCL-1", 1, "POL-1", ConfirmedQuantity.of(10))));
        when(client.fetch(COMMAND.purchaseOrderId()))
                .thenReturn(aggregateWith("PO-1", "SUP-1", PurchaseOrderStatus.CONFIRMED, unconfirmed));

        assertThatThrownBy(() -> service.refresh(COMMAND))
                .isInstanceOf(ExternalFactUnconfirmedException.class);
        verifyNoInteractions(store);
    }

    @Test
    void rejectsReceiptLineWithUnknownPurchaseOrderLine() {
        when(client.fetch(COMMAND.purchaseOrderId()))
                .thenReturn(aggregateWith("PO-1", "SUP-1", PurchaseOrderStatus.CONFIRMED, confirmedReceipt("POL-MISSING")));

        assertThatThrownBy(() -> service.refresh(COMMAND))
                .isInstanceOf(ExternalReferenceMismatchException.class);
        verifyNoInteractions(store);
    }

    @Test
    void rejectsDuplicatePurchaseOrderLine() {
        PurchaseOrderLineFacts duplicate = line("POL-1");
        PurchaseOrderAggregate aggregate = aggregateWith(
                "PO-1", "SUP-1", PurchaseOrderStatus.CONFIRMED, confirmedReceipt("POL-1"));
        PurchaseOrderAggregate withDuplicate = new PurchaseOrderAggregate(
                aggregate.purchaseOrderId(),
                aggregate.snapshotVersion(),
                new PurchaseOrderFacts(
                        3,
                        PurchaseOrderStatus.CONFIRMED,
                        SupplierId.of("SUP-1"),
                        "Hanul Office Supply",
                        List.of(duplicate, duplicate)),
                aggregate.receipts());
        when(client.fetch(COMMAND.purchaseOrderId())).thenReturn(withDuplicate);

        assertThatThrownBy(() -> service.refresh(COMMAND))
                .isInstanceOf(InvalidExternalFactException.class);
        verify(store, never()).apply(any(), anyString(), anyString(), any());
    }

    private static PurchaseOrderAggregate aggregateWith(
            String purchaseOrderId, String supplierId, PurchaseOrderStatus status, ReceiptFacts... receipts) {
        return new PurchaseOrderAggregate(
                PurchaseOrderId.of(purchaseOrderId),
                1,
                new PurchaseOrderFacts(3, status, SupplierId.of(supplierId), "Supplier", List.of(line("POL-1"))),
                List.of(receipts));
    }

    private static PurchaseOrderLineFacts line(String id) {
        return new PurchaseOrderLineFacts(id, "ITEM-1", "Item 1", Quantity.of(100), Money.of(2500));
    }

    private static ReceiptFacts confirmedReceipt(String purchaseOrderLineId) {
        return new ReceiptFacts(
                "RCV-1",
                ReceiptStatus.CONFIRMED,
                LocalDate.of(2026, 1, 5),
                2,
                List.of(new ReceiptLineFacts("RCL-1", 2, purchaseOrderLineId, ConfirmedQuantity.of(60))));
    }
}
