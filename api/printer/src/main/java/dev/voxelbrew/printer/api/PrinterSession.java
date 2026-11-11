package dev.voxelbrew.printer.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.DoubleConsumer;

/** Blocking operations; hosts invoke these on their device worker, never the UI thread. */
public interface PrinterSession extends AutoCloseable {
    enum Command {
        PAUSE,
        RESUME,
        CANCEL
    }

    record Upload(String filename, String sha256) {}

    PrinterStatus status() throws IOException;

    Upload upload(Path artifact, DoubleConsumer progress) throws IOException;

    void start(Upload upload) throws IOException;

    void command(Command command) throws IOException;

    @Override
    void close();
}
