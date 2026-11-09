package dev.voxelbrew.slicer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BackendConfigurationTest {
    @TempDir
    Path directory;

    @Test
    void registryKeepsProviderSettingsSeparateAndReportsInvalidOptions() throws Exception {
        try (var registry = new SlicerRegistry(
                directory,
                Map.of(
                        "slicer.prusa-slicer.executable", "prusa custom.exe",
                        "slicer.cura.executable", "cura custom.exe",
                        "slicer.cura.definition", "machine.json"))) {
            assertEquals(
                    Path.of("prusa custom.exe"),
                    registry.require("prusa-slicer").executable());
            var cura = registry.require("cura");
            assertEquals(Path.of("cura custom.exe"), cura.executable());
            assertEquals(
                    Path.of("cura custom.exe"),
                    cura.configure(Map.of("settings", "process.properties")).executable());
            assertEquals(
                    Path.of("override"),
                    cura.configure(Map.of("executable", "override")).executable());
            assertEquals(Path.of("cura custom.exe"), cura.executable());
            assertTrue(registry.diagnostics().isEmpty());
        }
        try (var registry = new SlicerRegistry(directory, Map.of("slicer.cura.typo", "value"))) {
            assertNotNull(registry.require("prusa-slicer"));
            assertThrows(IllegalArgumentException.class, () -> registry.require("cura"));
            assertFalse(registry.diagnostics().isEmpty());
        }
    }
}
