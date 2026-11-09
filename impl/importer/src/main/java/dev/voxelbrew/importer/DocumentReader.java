package dev.voxelbrew.importer;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded .vxb loading; unknown schemas and corrupt data remain protected from writes. */
@lombok.extern.slf4j.Slf4j
public final class DocumentReader {
    private DocumentReader() {}

    public static CadDocument load(Path path) throws IOException {
        log.debug("Reading document {}", path);
        try (var input = Files.newInputStream(path)) {
            var document = DocumentCodec.decode(input.readNBytes(DocumentCodec.MAX_ENCODED_BYTES + 1));
            document.parameters().builtins().projectPath(path);
            log.debug(
                    "Read document {} with {} features at cursor {}",
                    document.id(),
                    document.features().size(),
                    document.cursor());
            return document;
        }
    }
}
