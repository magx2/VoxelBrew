package dev.voxelbrew.gui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Copies committed starting documents so each scenario can edit and save independently. */
final class PreparedDocuments {
    private PreparedDocuments() {}

    static Path copy(String name, Path directory) throws IOException {
        try (var input = PreparedDocuments.class.getResourceAsStream("documents/" + name)) {
            if (input == null) throw new IOException("Missing committed E2E document: " + name);
            Path destination = directory.resolve(name);
            Files.copy(input, destination);
            return destination;
        }
    }
}
