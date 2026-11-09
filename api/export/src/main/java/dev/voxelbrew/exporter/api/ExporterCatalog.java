package dev.voxelbrew.exporter.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface ExporterCatalog extends AutoCloseable {
    @FunctionalInterface
    interface Factory {
        ExporterCatalog open(Path directory) throws IOException;
    }

    record Entry(ExporterDescriptor descriptor, ExporterProvider provider) {}

    Entry require(String id);

    List<ExporterDescriptor> descriptors();

    List<String> diagnostics();

    @Override
    void close() throws IOException;

    Path directory();

    void refresh() throws IOException;

    boolean contains(String id);
}
