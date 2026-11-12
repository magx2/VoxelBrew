package dev.voxelbrew.printer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.printer.api.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void configurationRoundTripDoesNotExposeSecrets() throws Exception {
        var runtime = new DefaultPrinterRuntime();
        var device = new Printer(
                "test",
                "Workshop",
                Printer.Kind.MOONRAKER,
                "generic-marlin-200-0.4",
                "http://localhost:7125",
                "",
                "",
                "secret-key");
        runtime.save(directory, device);
        assertEquals(device, runtime.devices(directory).getFirst());
        var file = directory.resolve("test.properties");
        assertFalse(Files.readString(file).contains("nozzle_mm"));
        Files.writeString(file, "extra=ignored\n", StandardOpenOption.APPEND);
        assertEquals(device, runtime.devices(directory).getFirst());
        assertFalse(device.toString().contains("secret-key"));
        runtime.remove(directory, device.id());
        assertTrue(runtime.devices(directory).isEmpty());
    }

    @Test
    void rejectsTraversalAndEmbeddedCredentials() {
        var runtime = new DefaultPrinterRuntime();
        assertThrows(IllegalArgumentException.class, () -> runtime.remove(directory, "../other"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new Printer(
                        "test",
                        "test",
                        Printer.Kind.MOONRAKER,
                        "generic-marlin-200-0.4",
                        "http://user:pass@host",
                        "",
                        "",
                        ""));
    }

    @Test
    void runtimeIsDiscoverable() {
        assertInstanceOf(DefaultPrinterRuntime.class, PrinterRuntime.load());
    }
}
