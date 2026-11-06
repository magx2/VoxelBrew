package dev.voxelbrew.exporter.di;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.api.DocumentSink;
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
    @NonNull private final DocumentSource source;
    @NonNull private final DocumentSink sink;
    @NonNull private final GcodeSource gcode;

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
    DocumentSink sink() {
        return sink;
    }

    @Provides
    @Singleton
    GcodeSource gcode() {
        return gcode;
    }

    @Provides
    static ExporterRegistry.Factory exporters() {
        return ExporterRegistry::new;
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
