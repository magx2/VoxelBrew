package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.Executor;

public final class JsonImporter extends FileImporter {
    public JsonImporter(Executor worker) {
        super(worker, "VoxelBrew JSON document", List.of("json"));
    }

    @Override
    protected ImportResult read(InputStream input) throws IOException {
        return new ImportResult.Document(
                dev.voxelbrew.engine.persistence.DocumentCodec.decodeJson(java.nio.charset.StandardCharsets.UTF_8
                        .newDecoder()
                        .decode(java.nio.ByteBuffer.wrap(input.readAllBytes()))
                        .toString()));
    }
}
