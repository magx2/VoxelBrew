package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;

public final class DefaultImportRuntime implements ImportRuntime {
    public DocumentSource documents() {
        return DocumentReader::load;
    }

    public GcodeSource gcode() {
        return GcodeReader::read;
    }

    public ImporterCatalog.Factory importers() {
        return ImporterRegistry::new;
    }
}
