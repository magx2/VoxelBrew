package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.model.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;

/** Publishes validated exact geometry atomically without changing the editable project. */
public final class StepDocumentWriter {
    private StepDocumentWriter() {}

    public static void save(CadDocument document, Path destination, boolean overwrite, Path pluginDirectory)
            throws IOException {
        Path target = destination.toAbsolutePath().normalize();
        if (!overwrite && Files.exists(target)) throw new IOException("Destination exists: " + target);
        byte[] encoded;
        try (var evaluation = Evaluator.evaluate(document, pluginDirectory)) {
            encoded = Solid.encodeStep(evaluation.requireCompleteDocument().stream()
                    .map(Evaluation.Body::solid)
                    .toList());
        }
        Path temporary = Files.createTempFile(target.getParent(), ".voxelbrew-step-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(encoded);
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (overwrite)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.createLink(target, temporary);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
