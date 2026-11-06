package dev.voxelbrew.importer;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.util.*;

/** Built-in editable document provider; validation is shared with the strict document reader. */
public final class JcadImporter implements FileImporter {
    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor(
                "jcad",
                "VoxelBrew document",
                API_VERSION,
                List.of("jcad"),
                ImportResult.Kind.DOCUMENT,
                List.of());
    }

    @Override
    public ImportResult read(InputStream input, Map<String, String> options) throws IOException {
        return new ImportResult.Document(
                DocumentCodec.decode(input.readNBytes(DocumentCodec.MAX_ENCODED_BYTES + 1)));
    }
}
