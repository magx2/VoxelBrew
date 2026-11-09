package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.util.concurrent.Executor;

public final class VoxelBrewImporterProvider implements ImporterProvider {
    public VoxelBrewImporterProvider() {}

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor("vxb", "VoxelBrew document", API_VERSION, ImportResult.Kind.DOCUMENT);
    }

    @Override
    public Importer<ImportResult> create(Executor worker) {
        return new VoxelBrewImporter(worker);
    }
}
