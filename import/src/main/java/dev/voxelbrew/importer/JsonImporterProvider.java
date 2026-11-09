package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.util.concurrent.Executor;

public final class JsonImporterProvider implements ImporterProvider {
    public JsonImporterProvider() {}

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor("json", "VoxelBrew JSON document", API_VERSION, ImportResult.Kind.DOCUMENT);
    }

    @Override
    public Importer<ImportResult> create(Executor worker) {
        return new JsonImporter(worker);
    }
}
