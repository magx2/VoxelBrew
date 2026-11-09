package dev.voxelbrew.exporter.api;

import dev.voxelbrew.exporter.DocumentOutput;
import java.nio.file.Path;
import java.util.concurrent.Executor;

public interface ExportRuntime {
    @FunctionalInterface
    interface DocumentExporterFactory {
        Exporter<Path> create(Executor worker, DocumentOutput output);
    }

    DocumentOutput documents();

    ExporterCatalog.Factory exporters();

    SlicerCatalog.Factory slicers();

    Slicing slicing();

    Path defaultSlicerExecutable();

    Exporter<Path> documentExporter(Executor worker, DocumentOutput output);

    static ExportRuntime load() {
        return java.util.ServiceLoader.load(ExportRuntime.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No export runtime installed"));
    }
}
