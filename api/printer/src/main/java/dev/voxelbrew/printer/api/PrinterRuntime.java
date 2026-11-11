package dev.voxelbrew.printer.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/** Application runtime entry point; implementations remain off frontend compile classpaths. */
public interface PrinterRuntime {
    List<PrinterDevice> devices(Path directory) throws IOException;

    void save(Path directory, PrinterDevice device) throws IOException;

    void remove(Path directory, String id) throws IOException;

    PrinterSession connect(PrinterDevice device) throws IOException;

    static PrinterRuntime load() {
        return ServiceLoader.load(PrinterRuntime.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No printer runtime installed"));
    }
}
