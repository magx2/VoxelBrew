package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.FileExportDialog;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** Durable atomic publication shared by built-in format serializers. */
public final class AtomicExport {
    private AtomicExport() {}

    @FunctionalInterface
    public interface Writer {
        void write(OutputStream output) throws IOException;
    }

    public static void write(FileExportDialog.Destination destination, Writer writer) throws IOException {
        Path target = destination.path().toAbsolutePath().normalize();
        if (!destination.overwrite() && Files.exists(target)) throw new IOException("Destination exists: " + target);
        Path temporary = Files.createTempFile(target.getParent(), ".voxelbrew-export-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                    var stream = Channels.newOutputStream(channel)) {
                var borrowed = new FilterOutputStream(stream) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                    }
                };
                writer.write(borrowed);
                borrowed.flush();
                channel.force(true);
            }
            if (destination.overwrite())
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.createLink(target, temporary);
        } catch (RuntimeException failure) {
            throw new IOException("Export serialization failed: " + failure.getMessage(), failure);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
