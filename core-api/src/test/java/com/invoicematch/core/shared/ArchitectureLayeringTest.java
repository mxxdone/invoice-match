package com.invoicematch.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Dependency-free layering guards for the three-layer cleanup. They read the
 * owned source files and fail if persistence starts to import an application
 * business type or if the outbox persistence moves back out of
 * {@code payment.persistence}. No ArchUnit dependency is introduced.
 */
class ArchitectureLayeringTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "invoicematch", "core");

    @Test
    void invoiceCaseListPersistenceDoesNotDependOnTheApplicationLayer() throws IOException {
        assertNoApplicationImport("invoicecase/persistence/InvoiceCaseListQueryStore.java");
        assertNoApplicationImport("invoicecase/persistence/InvoiceCaseListQuery.java");
        assertNoApplicationImport("invoicecase/persistence/InvoiceCaseListRow.java");
    }

    @Test
    void paymentResultPersistenceDoesNotDependOnWebhookOrApplicationTypes() throws IOException {
        assertNoApplicationImport("payment/persistence/PaymentResultEventStore.java");
        assertNoApplicationImport("payment/persistence/OutboxStore.java");
    }

    @Test
    void outboxPersistenceLivesInThePersistencePackage() throws IOException {
        Path outboxStore = MAIN.resolve("payment/persistence/OutboxStore.java");
        assertThat(outboxStore).exists();
        assertThat(read(outboxStore)).contains("package com.invoicematch.core.payment.persistence;");
        assertThat(MAIN.resolve("payment/application/OutboxStore.java")).doesNotExist();
    }

    private static void assertNoApplicationImport(String relativePath) throws IOException {
        Path source = MAIN.resolve(relativePath);
        assertThat(source).as(relativePath).exists();
        List<String> offending;
        try (Stream<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8).stream()) {
            offending = lines.filter(line -> line.startsWith("import "))
                    .filter(line -> line.contains(".application.")
                            || line.contains(".webhook.")
                            || line.contains(".api."))
                    .toList();
        }
        assertThat(offending)
                .as("%s must not import application/webhook/api types", relativePath)
                .isEmpty();
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
