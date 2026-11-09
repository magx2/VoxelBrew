package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.Executor;

public final class VoxelBrewImporter extends FileImporter {
    public VoxelBrewImporter(Executor worker) {
        super(worker, "VoxelBrew document", List.of("vxb", "jcad"));
    }

    @Override
    protected ImportResult read(InputStream input) throws IOException {
        return new ImportResult.Document(dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                input.readNBytes(dev.voxelbrew.engine.persistence.DocumentCodec.MAX_ENCODED_BYTES + 1)));
    }
}
