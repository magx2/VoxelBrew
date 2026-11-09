package dev.voxelbrew.importer.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ImporterCatalog extends AutoCloseable {
    @FunctionalInterface
    interface Factory {
        ImporterCatalog open(Path directory) throws IOException;
    }

    record Entry(ImporterDescriptor descriptor, ImporterProvider provider) {}

    Entry require(String id);

    List<ImporterDescriptor> descriptors();

    List<String> diagnostics();

    @Override
    void close() throws IOException;
}
