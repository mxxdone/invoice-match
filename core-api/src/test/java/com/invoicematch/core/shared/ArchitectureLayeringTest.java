package com.invoicematch.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
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
    void documentPersistenceDoesNotDependOnApplicationOrApiTypes() throws IOException {
        assertNoApplicationImport("document/persistence/DocumentStore.java");
    }

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

    /**
     * Persistence owns SQL/JPA and persistence/domain data only. Every
     * persistence type must not reach back into application, api or webhook.
     * The one legitimate shape is an adapter that implements an application
     * port ({@code *Adapter.java}); it is not a reverse dependency shortcut.
     */
    @Test
    void persistenceMustNotDependOnApplicationApiOrWebhook() throws IOException {
        assertImportsDoNotMatch(
                "persistence",
                file -> !file.getFileName().toString().endsWith("Adapter.java"),
                line -> line.contains(".application.") || line.contains(".api.") || line.contains(".webhook."),
                "persistence (except application-port adapters) must not import application/api/webhook");
    }

    /**
     * Domain types must not depend on any outer layer. JPA annotations are the
     * established domain-entity style and are not an outer-layer import; api,
     * application, webhook and our own infrastructure packages are.
     */
    @Test
    void domainMustNotDependOnOuterLayers() throws IOException {
        assertImportsDoNotMatch(
                "domain",
                file -> true,
                line -> line.contains("com.invoicematch.core.")
                        && (line.contains(".application.") || line.contains(".api.")
                                || line.contains(".webhook.") || line.contains(".infrastructure.")),
                "domain must not import application/api/webhook/infrastructure");
    }

    /**
     * API controllers are HTTP DTO/validation/error mapping only. They must not
     * reach persistence, SQL or storage directly; that is application's
     * orchestration job.
     */
    @Test
    void apiMustNotReachPersistenceSqlOrStorageDirectly() throws IOException {
        assertImportsDoNotMatch(
                "api",
                file -> true,
                line -> line.contains(".persistence.") || line.contains("org.springframework.jdbc")
                        || line.contains("javax.sql") || line.contains("java.sql") || line.contains(".infrastructure."),
                "api must not import persistence/JDBC/storage directly");
    }

    private static void assertImportsDoNotMatch(
            String packageSegment, Predicate<Path> include, Predicate<String> forbidden, String description)
            throws IOException {
        List<String> offending = new ArrayList<>();
        for (Path file : filesInDirectPackage(packageSegment)) {
            if (!include.test(file)) {
                continue;
            }
            try (Stream<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8).stream()) {
                lines.filter(line -> line.startsWith("import "))
                        .filter(forbidden)
                        .forEach(line -> offending.add(packageSegment + "/" + file.getFileName() + ": " + line));
            }
        }
        assertThat(offending).as(description).isEmpty();
    }

    private static List<Path> filesInDirectPackage(String packageSegment) throws IOException {
        try (Stream<Path> walk = Files.walk(MAIN)) {
            return walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.getParent() != null
                            && path.getParent().getFileName().toString().equals(packageSegment))
                    .toList();
        }
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
