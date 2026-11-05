package dev.voxelbrew.exporter.di;

import dagger.Module;
import dagger.Provides;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.printing.SlicerRegistry;
import dev.voxelbrew.importer.*;
import java.util.Objects;
import javax.inject.Singleton;

/** Production adapters, with explicit replacements for isolated application tests. */
@Module
public final class FileIoModule {
    private final DocumentSource source;
    private final DocumentSink sink;
    private final GcodeSource gcode;

    public FileIoModule() {
        this(DocumentReader::load, DocumentWriter::save, GcodeReader::read);
    }

    public FileIoModule(DocumentSource source, DocumentSink sink, GcodeSource gcode) {
        this.source = Objects.requireNonNull(source);
        this.sink = Objects.requireNonNull(sink);
        this.gcode = Objects.requireNonNull(gcode);
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
    static SlicerRegistry.Factory slicers() {
        return SlicerRegistry::new;
    }
}
