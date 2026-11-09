package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Atomic UTF-8 JSON publication; does not change the active project or save version. */
public final class JsonDocumentWriter {
    private JsonDocumentWriter() {}

    public static void save(CadDocument document, Path destination, boolean overwrite) throws IOException {
        String json = DocumentCodec.encodeJson(document);
        Path target = destination.toAbsolutePath().normalize();
        if (!overwrite && Files.exists(target)) throw new IOException("Destination exists: " + target);
        Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName(), ".tmp");
        try {
            Files.writeString(temporary, json + "\n", StandardCharsets.UTF_8);
            if (overwrite) {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.createLink(target, temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
