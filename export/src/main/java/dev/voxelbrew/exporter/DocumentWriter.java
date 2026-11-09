package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.DocumentReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Atomic publication of editable .vxb documents, preserving unsupported existing files. */
@lombok.extern.slf4j.Slf4j
public final class DocumentWriter {
    private DocumentWriter() {}

    /**
     * Existing destinations require explicit overwrite; unsupported files are never overwritten.
     */
    public static void save(CadDocument document, Path path, boolean overwrite) throws IOException {
        synchronized (document.parameters().builtins()) {
            Path target = path.toAbsolutePath().normalize();
            log.debug("Saving document {} to {} (overwrite={})", document.id(), target, overwrite);
            if (Files.exists(target)) {
                if (!overwrite) {
                    throw new IOException("Destination exists; use explicit overwrite: " + target);
                }
                // Protect newer schemas, unknown data and damaged originals from replacement.
                DocumentReader.load(target);
            }
            long previousVersion = document.parameters().builtins().fileVersion();
            long nextVersion = Math.incrementExact(previousVersion);
            ByteBuffer bytes = ByteBuffer.wrap(DocumentCodec.encode(document, nextVersion));
            Path temporary = Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
            try {
                try (FileChannel channel =
                        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
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
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } else {
                    // Atomic create-if-absent: unlike ATOMIC_MOVE, a concurrent destination cannot be
                    // replaced.
                    Files.createLink(target, temporary);
                }
                document.parameters().builtins().fileVersion(nextVersion);
                document.parameters().builtins().projectPath(target);
                log.info(
                        "Saved document {} with {} features to {}",
                        document.id(),
                        document.features().size(),
                        target);
            } catch (IOException failure) {
                log.error("Document save failed for {}", target, failure);
                throw failure;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
