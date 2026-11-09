package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.util.concurrent.Executor;

public final class GcodeImporterProvider implements ImporterProvider {
    public GcodeImporterProvider() {}

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor("gcode", "G-code toolpath", API_VERSION, ImportResult.Kind.GCODE);
    }

    @Override
    public Importer<ImportResult> create(Executor worker) {
        return new GcodeImporter(worker);
    }
}
