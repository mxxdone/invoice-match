package com.invoicematch.core.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Dependency-free layering guards for the three-layer boundary. They read the
 * owned source files and fail if persistence starts to import an application
 * business type or a webhook/API type, if a domain type depends on an outer
 * layer, or if an API controller reaches persistence, JDBC or storage directly.
 * No ArchUnit dependency is introduced.
 *
 * <p>The one legitimate reverse dependency is an adapter that implements an
 * application port: exactly
 * {@code approval/persistence/ReceiptAllocationCommitmentAdapter.java} may
 * import {@code purchasingreference.application.ReceiptAllocationCommitmentReader}.
 * Every other application/api/webhook import in that same file is still
 * inspected. The scanners are pure functions over source text so synthetic
 * negative fixtures can prove they detect a violation instead of passing
 * vacuously.
 */
class ArchitectureLayeringTest {

    private static final Path MAIN = Path.of("src", "main", "java", "com", "invoicematch", "core");
    private static final String ALLOWED_PORT_ADAPTER =
            "approval/persistence/ReceiptAllocationCommitmentAdapter.java";
    private static final String ALLOWED_PORT_IMPORT =
            "import com.invoicematch.core.purchasingreference.application.ReceiptAllocationCommitmentReader;";

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

    @Test
    void persistenceMustNotDependOnApplicationApiOrWebhook() throws IOException {
        assertThat(persistenceViolations(loadSources()))
                .as("persistence must only allow the declared application port adapter import")
                .isEmpty();
    }

    @Test
    void domainMustNotDependOnOuterLayers() throws IOException {
        assertThat(domainViolations(loadSources()))
                .as("domain must not import application/api/webhook/infrastructure")
                .isEmpty();
    }

    @Test
    void apiMustNotReachPersistenceSqlOrStorageDirectly() throws IOException {
        assertThat(apiViolations(loadSources()))
                .as("api must not import persistence/JDBC/storage directly")
                .isEmpty();
    }

    @Test
    void layeringScannersDetectSyntheticViolations() {
        // A fake Adapter name cannot smuggle in an application dependency.
        assertThat(persistenceViolations(List.of(source(
                "review/persistence/PretendAdapter.java",
                "import com.invoicematch.core.review.application.ReviewService;")))).hasSize(1);
        // Nested persistence packages are scanned, not only the direct child.
        assertThat(persistenceViolations(List.of(source(
                "account/persistence/query/QueryRow.java",
                "import com.invoicematch.core.review.application.ReviewService;")))).hasSize(1);
        // Only the exact declared port import in the exact adapter is allowed.
        assertThat(persistenceViolations(List.of(source(ALLOWED_PORT_ADAPTER, ALLOWED_PORT_IMPORT)))).isEmpty();
        assertThat(persistenceViolations(List.of(source(ALLOWED_PORT_ADAPTER,
                ALLOWED_PORT_IMPORT,
                "import com.invoicematch.core.review.application.ReviewService;")))).hasSize(1);
        // A path that only ends with the adapter name must not inherit the allowance.
        assertThat(persistenceViolations(List.of(source(
                "another/approval/persistence/ReceiptAllocationCommitmentAdapter.java",
                ALLOWED_PORT_IMPORT)))).hasSize(1);
        // Indented and static imports are inspected too.
        assertThat(persistenceViolations(List.of(source(
                "foo/persistence/Foo.java",
                "    import com.invoicematch.core.review.api.ReviewController;")))).hasSize(1);
        assertThat(persistenceViolations(List.of(source(
                "foo/persistence/Foo.java",
                "import static com.invoicematch.core.review.application.ReviewService.run;")))).hasSize(1);
        // Domain and api scanners reject their own outer-layer shortcuts.
        assertThat(domainViolations(List.of(source(
                "invoicecase/domain/Foo.java",
                "import com.invoicematch.core.invoicecase.application.InvoiceCaseQueryService;")))).hasSize(1);
        assertThat(apiViolations(List.of(source(
                "document/api/DocumentController.java",
                "import com.invoicematch.core.document.application.DocumentStorage;")))).hasSize(1);
        assertThat(apiViolations(List.of(source(
                "document/api/DocumentController.java",
                "import com.invoicematch.core.document.persistence.DocumentStore;")))).hasSize(1);
    }

    private static List<String> persistenceViolations(List<SourceFile> sources) {
        return scanSources("persistence", sources, (path, line) ->
                containsAny(line, ".application.", ".api.", ".webhook.") && !isAllowedPortImport(path, line));
    }

    private static List<String> domainViolations(List<SourceFile> sources) {
        return scanSources("domain", sources, (path, line) ->
                line.contains("com.invoicematch.core.")
                        && containsAny(line, ".application.", ".api.", ".webhook.", ".infrastructure."));
    }

    private static List<String> apiViolations(List<SourceFile> sources) {
        return scanSources("api", sources, (path, line) -> containsAny(
                line, ".persistence.", "org.springframework.jdbc", "javax.sql", "java.sql", ".infrastructure.",
                "DocumentStorage", ".storage."));
    }

    private static List<String> scanSources(
            String packageSegment, List<SourceFile> sources, BiPredicate<String, String> isViolation) {
        List<String> offending = new ArrayList<>();
        for (SourceFile source : sources) {
            if (!inSegment(source.path(), packageSegment)) {
                continue;
            }
            for (String raw : source.lines()) {
                String line = raw.strip();
                if (!line.startsWith("import")) {
                    continue;
                }
                if (isViolation.test(source.path(), line)) {
                    offending.add(source.path() + ": " + line);
                }
            }
        }
        return offending;
    }

    private static boolean inSegment(String path, String segment) {
        String[] parts = path.replace('\\', '/').split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAllowedPortImport(String path, String importLine) {
        if (!path.replace('\\', '/').equals(ALLOWED_PORT_ADAPTER)) {
            return false;
        }
        return importLine.replaceAll("\\s+", " ").strip().equals(ALLOWED_PORT_IMPORT);
    }

    private static boolean containsAny(String line, String... needles) {
        for (String needle : needles) {
            if (line.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static List<SourceFile> loadSources() throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            paths = walk.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".java"))
                    .toList();
        }
        List<SourceFile> sources = new ArrayList<>(paths.size());
        for (Path path : paths) {
            sources.add(new SourceFile(
                    MAIN.relativize(path).toString().replace('\\', '/'),
                    Files.readAllLines(path, StandardCharsets.UTF_8)));
        }
        return sources;
    }

    private static SourceFile source(String path, String... lines) {
        return new SourceFile(path, List.of(lines));
    }

    private record SourceFile(String path, List<String> lines) {
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
