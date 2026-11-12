package dev.voxelbrew.printer.api;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.ServiceLoader;

/** Application runtime entry point; implementations remain off frontend compile classpaths. */
public interface PrinterRuntime {
    List<Printer> devices(Path directory) throws IOException;

    void save(Path directory, Printer device) throws IOException;

    void remove(Path directory, String id) throws IOException;

    PrinterSession connect(Printer device) throws IOException;

    static PrinterRuntime load() {
        return ServiceLoader.load(PrinterRuntime.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No printer runtime installed"));
    }
}
