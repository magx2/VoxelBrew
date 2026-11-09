package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.*;

/** Compatibility entry points for standalone implementation consumers. */
public final class ImportService {
    public static final long MAX_INPUT_BYTES = ImportInput.MAX_INPUT_BYTES;

    private ImportService() {}

    public static CompletableFuture<ImportResult> importFrom(
            ImporterCatalog.Entry entry, Executor worker, ImportWindow parent) {
        return ImportInvocation.importFrom(entry, worker, parent);
    }

    public static ImportResult read(ImporterCatalog registry, String format, Path source, Map<String, String> supplied)
            throws IOException {
        return ImportInvocation.read(registry, format, source, supplied);
    }
}
