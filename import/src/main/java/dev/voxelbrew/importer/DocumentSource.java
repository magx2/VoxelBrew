package dev.voxelbrew.importer;

import dev.voxelbrew.engine.model.CadDocument;
import java.io.IOException;
import java.nio.file.Path;

/** Input port for editable documents. */
@FunctionalInterface
public interface DocumentSource {
    CadDocument load(Path path) throws IOException;
}
