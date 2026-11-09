package dev.voxelbrew.cli;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.exporter.api.*;
import dev.voxelbrew.importer.api.*;
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

    public FileIoModule() {
        this(ImportRuntime.load(), ExportRuntime.load());
    }

    private FileIoModule(ImportRuntime imports, ExportRuntime exports) {
        this(imports.documents(), exports.documents(), imports.gcode(), imports, exports);
    }

    public FileIoModule(DocumentSource source, DocumentOutput sink, GcodeSource gcode) {
        this(source, sink, gcode, ImportRuntime.load(), ExportRuntime.load());
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
        return exports.slicers();
    }

    @Provides
    ExportRuntime.DocumentExporterFactory documentExporters() {
        return exports::documentExporter;
    }

    @Provides
    Slicing slicing() {
        return exports.slicing();
    }

    @Provides
    @javax.inject.Named("slicerExecutable")
    java.nio.file.Path slicerExecutable() {
        return exports.defaultSlicerExecutable();
    }
}
