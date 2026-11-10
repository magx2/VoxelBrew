package dev.voxelbrew.util.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationSectionTest {
    @TempDir
    Path directory;

    @Test
    void migratesOnlyThisProviderAndKeepsDestinationValuesAndUnknownKeys() throws Exception {
        var main = new UserConfiguration(directory.resolve("custom.properties"));
        main.update(
                Map.of(
                        "slicer.prusa-slicer.executable",
                        "old",
                        "slicer.prusa-slicer.custom",
                        "kept",
                        "slicer.cura.executable",
                        "cura",
                        "appearance",
                        "dark"),
                java.util.Set.of());
        var separate = new UserConfiguration(directory.resolve("config-slicer-prusa-slicer.properties"));
        separate.set("executable", "new");
        assertEquals(
                Map.of("executable", "new", "custom", "kept"),
                main.section("slicer", "prusa-slicer").read());
        assertEquals(Map.of("slicer.cura.executable", "cura", "appearance", "dark"), main.read());
        assertEquals(separate.read(), main.section("slicer", "prusa-slicer").read());
    }

    @Test
    void failedMigrationPreservesLegacyValues() throws Exception {
        var main = new UserConfiguration(directory.resolve("config.properties"));
        main.set("slicer.cura.executable", "kept");
        Files.createDirectory(directory.resolve("config-slicer-cura.properties"));
        assertThrows(IOException.class, () -> main.section("slicer", "cura"));
        assertEquals("kept", main.get("slicer.cura.executable", ""));
    }

    @Test
    void rejectsTraversalAndDoesNotCreateUnusedFiles() throws Exception {
        var main = new UserConfiguration(directory.resolve("config.properties"));
        assertThrows(IllegalArgumentException.class, () -> main.section("slicer", "../outside"));
        assertTrue(main.section("slicer", "cura").read().isEmpty());
        assertFalse(Files.exists(directory.resolve("config-slicer-cura.properties")));
        assertFalse(Files.exists(main.path()));
    }
}
