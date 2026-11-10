package dev.voxelbrew.importer.api;

import dev.voxelbrew.engine.printing.GcodeJob;
import java.io.IOException;
import java.nio.file.Path;

@FunctionalInterface
public interface GcodeSource {
    GcodeJob read(Path path) throws IOException;

    default GcodeJob readLines(java.util.List<String> lines) throws IOException {
        throw new IOException("This G-code reader does not support embedded jobs");
    }
}
