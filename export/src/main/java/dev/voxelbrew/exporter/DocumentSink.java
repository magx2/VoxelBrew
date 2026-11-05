package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.model.CadDocument;
import java.io.IOException;
import java.nio.file.Path;

/** Output port; implementations must retain explicit overwrite and atomic publication. */
@FunctionalInterface
public interface DocumentSink {
    void save(CadDocument document, Path path, boolean overwrite) throws IOException;
}
