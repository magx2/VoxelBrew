package dev.voxelbrew.importer.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;

/** Provider invocation and result validation shared by desktop and headless hosts. */
public final class ImportInvocation {

    private ImportInvocation() {}

    public static CompletableFuture<ImportResult> importFrom(
            ImporterCatalog.Entry entry, Executor worker, ImportWindow parent) {
        try {
            return entry.provider().create(worker).importFrom(parent).thenApply(result -> {
                Objects.requireNonNull(result, "Importer returned no result");
                if (result.kind() != entry.descriptor().resultKind())
                    throw new IllegalArgumentException("Importer returned a different result kind than its descriptor");
                return result;
            });
        } catch (RuntimeException | LinkageError | java.util.ServiceConfigurationError | AssertionError failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    public static ImportResult read(ImporterCatalog registry, String format, Path source, Map<String, String> supplied)
            throws IOException {
        try {
            return importFrom(registry.require(format), Runnable::run, FileImportDialog.source(source, supplied))
                    .join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof IllegalArgumentException invalid) throw invalid;
            throw new IOException(
                    "Importer '" + format + "' failed: " + failure.getCause().getMessage(), failure.getCause());
        }
    }
}
