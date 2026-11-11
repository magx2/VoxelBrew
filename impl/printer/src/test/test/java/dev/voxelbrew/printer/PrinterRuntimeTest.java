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
        var device = new PrinterDevice(
                "test", "Workshop", PrinterDevice.Kind.MOONRAKER, "http://localhost:7125", "", "", "secret-key");
        runtime.save(directory, device);
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
                () -> new PrinterDevice(
                        "test", "test", PrinterDevice.Kind.MOONRAKER, "http://user:pass@host", "", "", ""));
    }

    @Test
    void runtimeIsDiscoverable() {
        assertInstanceOf(DefaultPrinterRuntime.class, PrinterRuntime.load());
    }
}
