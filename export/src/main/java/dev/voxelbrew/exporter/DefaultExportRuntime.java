package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.*;
import dev.voxelbrew.exporter.printing.*;
import java.nio.file.Path;
import java.util.concurrent.Executor;

public final class DefaultExportRuntime implements ExportRuntime {
    public DocumentOutput documents() {
        return DocumentWriter::save;
    }

    public ExporterCatalog.Factory exporters() {
        return ExporterRegistry::new;
    }

    public Exporter<Path> documentExporter(Executor worker, DocumentOutput output) {
        return new VoxelBrewExporter(worker, output);
    }
}
