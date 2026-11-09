package dev.voxelbrew.importer.api;

import dev.voxelbrew.engine.printing.GcodeJob;
import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface GcodeSource {
    GcodeJob read(Path path) throws IOException;
}
