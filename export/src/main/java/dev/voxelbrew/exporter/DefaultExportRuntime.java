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

    public SlicerCatalog.Factory slicers() {
        return SlicerRegistry::new;
    }

    public Slicing slicing() {
        return SliceService::slice;
    }

    public Path defaultSlicerExecutable() {
        return PrusaSlicerBackend.defaultExecutable();
    }

    public Exporter<Path> documentExporter(Executor worker, DocumentOutput output) {
        return new VoxelBrewExporter(worker, output);
    }
}
