package dev.voxelbrew.exporter.di;

import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.exporter.api.ExporterProvider;
import dev.voxelbrew.exporter.printing.SlicerRegistry;
import dev.voxelbrew.importer.*;
import dev.voxelbrew.importer.api.DocumentSource;
import dev.voxelbrew.importer.api.GcodeSource;
import javax.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** Production adapters, with explicit replacements for isolated application tests. */
@Module
@RequiredArgsConstructor
public final class FileIoModule {
    @NonNull
    private final DocumentSource source;

    @NonNull
    private final DocumentOutput sink;

    @NonNull
    private final GcodeSource gcode;

    public FileIoModule() {
        this(DocumentReader::load, DocumentWriter::save, GcodeReader::read);
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
    static ExporterRegistry.Factory exporters(java.util.Set<ExporterProvider> builtins) {
        return directory -> new ExporterRegistry(directory, builtins);
    }

    @Provides
    @IntoSet
    static ExporterProvider vxb(JcadExporterProvider provider) {
        return provider;
    }

    @Provides
    @IntoSet
    static ExporterProvider json(JsonExporterProvider provider) {
        return provider;
    }

    @Provides
    @IntoSet
    static ExporterProvider step(StepExporterProvider provider) {
        return provider;
    }

    @Provides
    @IntoSet
    static ExporterProvider stl() {
        return new GeometryExporterProvider(
                new dev.voxelbrew.exporter.api.ExporterDescriptor("stl", "Binary STL", ExporterProvider.API_VERSION),
                BinaryStlExporter::new);
    }

    @Provides
    @IntoSet
    static ExporterProvider threeMf() {
        return new GeometryExporterProvider(
                new dev.voxelbrew.exporter.api.ExporterDescriptor(
                        "3mf", "Portable 3MF Core", ExporterProvider.API_VERSION),
                ThreeMfExporter::new);
    }

    @Provides
    @IntoSet
    static ExporterProvider obj() {
        return new GeometryExporterProvider(
                new dev.voxelbrew.exporter.api.ExporterDescriptor("obj", "Wavefront OBJ", ExporterProvider.API_VERSION),
                ObjExporter::new);
    }

    @Provides
    @IntoSet
    static ExporterProvider prusaProject() {
        return new GeometryExporterProvider(
                new dev.voxelbrew.exporter.api.ExporterDescriptor(
                        "prusa-project", "PrusaSlicer project (3MF)", ExporterProvider.API_VERSION),
                dev.voxelbrew.exporter.printing.PrusaProjectExporter::new);
    }

    @Provides
    static ImporterRegistry.Factory importers() {
        return ImporterRegistry::new;
    }

    @Provides
    static SlicerRegistry.Factory slicers() {
        return SlicerRegistry::new;
    }
}
