package dev.voxelbrew.cli;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.exporter.api.*;
import dev.voxelbrew.importer.api.*;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.slicer.api.SlicerRuntime;
import dev.voxelbrew.slicer.api.Slicing;
import javax.inject.Singleton;

@Module
@lombok.RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
public final class FileIoModule {
    @lombok.NonNull
    private final DocumentSource source;

    @lombok.NonNull
    private final DocumentOutput sink;

    @lombok.NonNull
    private final GcodeSource gcode;

    @lombok.NonNull
    private final ImportRuntime imports;

    @lombok.NonNull
    private final ExportRuntime exports;

    @lombok.NonNull
    private final SlicerRuntime slicerRuntime;

    public FileIoModule() {
        this(ImportRuntime.load(), ExportRuntime.load(), SlicerRuntime.load());
    }

    private FileIoModule(ImportRuntime imports, ExportRuntime exports, SlicerRuntime slicerRuntime) {
        this(imports.documents(), exports.documents(), imports.gcode(), imports, exports, slicerRuntime);
    }

    public FileIoModule(DocumentSource source, DocumentOutput sink, GcodeSource gcode) {
        this(source, sink, gcode, ImportRuntime.load(), ExportRuntime.load(), SlicerRuntime.load());
    }

    @Provides
    @Singleton
    DocumentSource source() {
        return source;
    }

    @Provides
    @Singleton
    DocumentOutput sink() {
        return sink;
    }

    @Provides
    @Singleton
    GcodeSource gcode() {
        return gcode;
    }

    @Provides
    ExporterCatalog.Factory exporters() {
        return exports.exporters();
    }

    @Provides
    ImporterCatalog.Factory importers() {
        return imports.importers();
    }

    @Provides
    SlicerCatalog.Factory slicers() {
        return slicerRuntime.slicers();
    }

    @Provides
    ExportRuntime.DocumentExporterFactory documentExporters() {
        return exports::documentExporter;
    }

    @Provides
    dev.voxelbrew.slicer.api.PrinterCatalog printers() {
        return slicerRuntime.printers(dev.voxelbrew.util.config.ApplicationPaths.printers());
    }

    @Provides
    Slicing slicing() {
        return slicerRuntime.slicing();
    }

    @Provides
    @javax.inject.Named("slicerExecutable")
    java.nio.file.Path slicerExecutable() {
        return slicerRuntime.defaultSlicerExecutable();
    }
}
