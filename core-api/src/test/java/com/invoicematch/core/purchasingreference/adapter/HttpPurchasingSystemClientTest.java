package com.invoicematch.core.purchasingreference.adapter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.invoicematch.core.purchasingreference.domain.InvalidExternalFactException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderAggregate;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderNotFoundException;
import com.invoicematch.core.purchasingreference.domain.PurchaseOrderStatus;
import com.invoicematch.core.purchasingreference.domain.PurchasingSystemMalformedPayloadException;
import com.invoicematch.core.purchasingreference.domain.PurchasingSystemUnavailableException;
import com.invoicematch.core.purchasingreference.domain.ReceiptStatus;
import com.invoicematch.core.shared.domain.ConfirmedQuantity;
import com.invoicematch.core.shared.domain.Money;
import com.invoicematch.core.shared.domain.PurchaseOrderId;
import com.invoicematch.core.shared.domain.Quantity;
import com.invoicematch.core.support.PurchasingPayloads;
import com.invoicematch.core.support.StubPurchasingServer;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

class HttpPurchasingSystemClientTest {

    private StubPurchasingServer stub;
    private HttpPurchasingSystemClient client;

    @BeforeEach
    void setUp() throws IOException {
        stub = new StubPurchasingServer();
        client = client(Duration.ofSeconds(1));
    }

    @AfterEach
    void tearDown() {
        stub.close();
    }

    private HttpPurchasingSystemClient client(Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(1));
        factory.setReadTimeout(readTimeout);
        RestClient restClient = RestClient.builder()
                .baseUrl(stub.baseUrl())
                .requestFactory(factory)
                .build();
        var objectMapper = JsonMapper.builder().addModule(new JavaTimeModule()).build();
        return new HttpPurchasingSystemClient(restClient, objectMapper);
    }

    @Test
    void mapsConfirmedAggregate() {
        stub.respond(200, PurchasingPayloads.confirmedPartialReceipt().toJson());

        PurchaseOrderAggregate aggregate = client.fetch(PurchaseOrderId.of("PO-1001"));

        assertThat(aggregate.snapshotVersion()).isEqualTo(5);
        assertThat(aggregate.purchaseOrder().status()).isEqualTo(PurchaseOrderStatus.CONFIRMED);
        assertThat(aggregate.purchaseOrder().supplierId().value()).isEqualTo("SUP-1");
        assertThat(aggregate.purchaseOrder().lines()).hasSize(2);
        assertThat(aggregate.purchaseOrder().lines().get(0).unitPrice()).isEqualTo(Money.of(2500));
        assertThat(aggregate.purchaseOrder().lines().get(0).orderedQuantity()).isEqualTo(Quantity.of(100));
        assertThat(aggregate.receipts()).hasSize(1);
        assertThat(aggregate.receipts().get(0).status()).isEqualTo(ReceiptStatus.CONFIRMED);
        assertThat(aggregate.receipts().get(0).lines().get(0).confirmedQuantity())
                .isEqualTo(ConfirmedQuantity.of(60));
    }

    @Test
    void mapsUnconfirmedStatusWithoutSilentlyDroppingIt() {
        stub.respond(200, PurchasingPayloads.confirmedPartialReceipt().status("UNCONFIRMED").toJson());

        PurchaseOrderAggregate aggregate = client.fetch(PurchaseOrderId.of("PO-1001"));

        assertThat(aggregate.purchaseOrder().status()).isEqualTo(PurchaseOrderStatus.UNCONFIRMED);
    }

    @Test
    void mapsNotFoundToExplicitError() {
        stub.respond(404, "{\"error\":\"PURCHASE_ORDER_NOT_FOUND\",\"purchaseOrderId\":\"PO-9999\"}");

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-9999")))
                .isInstanceOf(PurchaseOrderNotFoundException.class)
                .hasMessageContaining("PO-9999");
    }

    @Test
    void mapsServerErrorToUnavailable() {
        stub.respond(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(PurchasingSystemUnavailableException.class);
    }

    @Test
    void mapsTimeoutToUnavailable() {
        HttpPurchasingSystemClient shortTimeout = client(Duration.ofMillis(150));
        stub.respondAfter(PurchasingPayloads.confirmedPartialReceipt().toJson(), Duration.ofMillis(800));

        assertThatThrownBy(() -> shortTimeout.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(PurchasingSystemUnavailableException.class);
    }

    @Test
    void mapsMalformedJsonToMalformedPayload() {
        stub.respond(200, "{\"snapshotVersion\": ");

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(PurchasingSystemMalformedPayloadException.class);
    }

    @Test
    void rejectsInvalidOrderedQuantity() {
        stub.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .addLine("POL-BAD", "ITEM", "Item", 0, 1000)
                        .toJson());

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(InvalidExternalFactException.class);
    }

    @Test
    void rejectsNegativeUnitPrice() {
        stub.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .addLine("POL-BAD", "ITEM", "Item", 1, -1)
                        .toJson());

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(InvalidExternalFactException.class);
    }

    @Test
    void rejectsNegativeConfirmedQuantity() {
        stub.respond(
                200,
                PurchasingPayloads.confirmedPartialReceipt()
                        .addReceipt(
                                "RCV-BAD",
                                "CONFIRMED",
                                "2026-01-06",
                                1,
                                PurchasingPayloads.receiptLine("RCL-BAD", 1, "POL-1001-1", -1))
                        .toJson());

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(InvalidExternalFactException.class);
    }

    @Test
    void rejectsMissingSnapshotVersion() {
        stub.respond(200, "{\"purchaseOrder\":{\"purchaseOrderId\":\"PO-1001\"},\"receipts\":[]}");

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(InvalidExternalFactException.class);
    }

    @Test
    void rejectsNegativeSnapshotVersion() {
        stub.respond(200, PurchasingPayloads.confirmedPartialReceipt().snapshotVersion(-1).toJson());

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(InvalidExternalFactException.class);
    }

    @Test
    void rejectsUnknownStatus() {
        stub.respond(200, PurchasingPayloads.confirmedPartialReceipt().status("CANCELLED").toJson());

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(InvalidExternalFactException.class);
    }

    @Test
    void rejectsEmptyBody() {
        stub.respond(200, "");

        assertThatThrownBy(() -> client.fetch(PurchaseOrderId.of("PO-1001")))
                .isInstanceOf(PurchasingSystemMalformedPayloadException.class);
    }
}
