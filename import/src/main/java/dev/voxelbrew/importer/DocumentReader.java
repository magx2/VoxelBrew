package dev.voxelbrew.importer;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded .jcad loading; unknown schemas and corrupt data remain protected from writes. */
public final class DocumentReader {
    private DocumentReader() {}

    public static CadDocument load(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return DocumentCodec.decode(input.readNBytes(DocumentCodec.MAX_ENCODED_BYTES + 1));
        }
    }
}
