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
    static ExporterProvider vxb(VoxelBrewExporterProvider provider) {
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
                new dev.voxelbrew.exporter.api.ExporterDescriptor("stl", "Binary STL"), BinaryStlExporter::new);
    }

    @Provides
    @IntoSet
    static ExporterProvider threeMf() {
        return new GeometryExporterProvider(
                new dev.voxelbrew.exporter.api.ExporterDescriptor("3mf", "Portable 3MF Core"), ThreeMfExporter::new);
    }

    @Provides
    @IntoSet
    static ExporterProvider obj() {
        return new GeometryExporterProvider(
                new dev.voxelbrew.exporter.api.ExporterDescriptor("obj", "Wavefront OBJ"), ObjExporter::new);
    }

    @Provides
    @IntoSet
    static ExporterProvider prusaProject() {
        return new GeometryExporterProvider(
                new dev.voxelbrew.exporter.api.ExporterDescriptor("prusa-project", "PrusaSlicer project (3MF)"),
                dev.voxelbrew.exporter.printing.PrusaProjectExporter::new);
    }

    @Provides
    static ImporterRegistry.Factory importers(java.util.Set<dev.voxelbrew.importer.api.ImporterProvider> builtins) {
        return directory -> new ImporterRegistry(directory, builtins);
    }

    @Provides
    @IntoSet
    static dev.voxelbrew.importer.api.ImporterProvider importVxb() {
        return new VoxelBrewImporterProvider();
    }

    @Provides
    @IntoSet
    static dev.voxelbrew.importer.api.ImporterProvider importJson() {
        return new JsonImporterProvider();
    }

    @Provides
    @IntoSet
    static dev.voxelbrew.importer.api.ImporterProvider importGcode() {
        return new GcodeImporterProvider();
    }

    @Provides
    static SlicerRegistry.Factory slicers() {
        return SlicerRegistry::new;
    }
}
