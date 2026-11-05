package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.DocumentReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Atomic publication of editable .jcad documents, preserving unsupported existing files. */
public final class DocumentWriter {
    private DocumentWriter() {}

    /**
     * Existing destinations require explicit overwrite; unsupported files are never overwritten.
     */
    public static void save(CadDocument document, Path path, boolean overwrite) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        if (Files.exists(target)) {
            if (!overwrite) {
                throw new IOException("Destination exists; use explicit overwrite: " + target);
            }
            // Protect newer schemas, unknown data and damaged originals from replacement.
            DocumentReader.load(target);
        }
        ByteBuffer bytes = ByteBuffer.wrap(DocumentCodec.encode(document));
        Path temporary =
                Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary,
                            StandardOpenOption.WRITE,
                            StandardOpenOption.TRUNCATE_EXISTING)) {
                while (bytes.hasRemaining()) {
                    channel.write(bytes);
                }
                channel.force(true);
            }
            // Validate serialized definitions before publishing. Invalid references remain
            // repairable.
            DocumentReader.load(temporary);
            if (overwrite) {
                // Fail safely if this filesystem cannot atomically replace a sibling file.
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                // Atomic create-if-absent: unlike ATOMIC_MOVE, a concurrent destination cannot be
                // replaced.
                Files.createLink(target, temporary);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
