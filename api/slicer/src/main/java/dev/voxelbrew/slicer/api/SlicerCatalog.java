package dev.voxelbrew.slicer.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

public interface SlicerCatalog extends AutoCloseable {
    @FunctionalInterface
    interface Factory {
        SlicerCatalog open(Path directory) throws IOException;
    }

    SlicerBackend require(String id);

    List<SlicerBackend.Descriptor> descriptors();

    List<String> diagnostics();

    @Override
    void close() throws IOException;
}
