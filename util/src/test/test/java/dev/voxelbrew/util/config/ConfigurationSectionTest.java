package dev.voxelbrew.util.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationSectionTest {
    @Test
    void migratesPreviousFileWithDestinationTakingPrecedence() throws Exception {
        var main = new UserConfiguration(directory.resolve("config.properties"));
        main.set("slicer.cura.executable", "oldest");
        var previous = new UserConfiguration(directory.resolve("config-slicer-cura.properties"));
        previous.update(Map.of("executable", "previous", "definition", "machine.json"), java.util.Set.of());
        var target = new UserConfiguration(directory.resolve("slicers/cura.properties"));
        target.set("executable", "current");
        assertEquals(
                Map.of("executable", "current", "definition", "machine.json"),
                main.section("slicer", "cura").read());
        assertFalse(Files.exists(previous.path()));
        assertTrue(main.read().isEmpty());
        assertEquals(target.read(), main.section("slicer", "cura").read());
    }

    @Test
    void failedFileMigrationKeepsPreviousFile() throws Exception {
        var main = new UserConfiguration(directory.resolve("config.properties"));
        var previous = new UserConfiguration(directory.resolve("config-slicer-cura.properties"));
        previous.set("executable", "kept");
        Files.createDirectories(directory.resolve("slicers/cura.properties"));
        assertThrows(IOException.class, () -> main.section("slicer", "cura"));
        assertEquals("kept", previous.get("executable", ""));
    }

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
        var separate = new UserConfiguration(directory.resolve("slicers/prusa-slicer.properties"));
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
        Files.createDirectories(directory.resolve("slicers/cura.properties"));
        assertThrows(IOException.class, () -> main.section("slicer", "cura"));
        assertEquals("kept", main.get("slicer.cura.executable", ""));
    }

    @Test
    void rejectsTraversalAndDoesNotCreateUnusedFiles() throws Exception {
        var main = new UserConfiguration(directory.resolve("config.properties"));
        assertThrows(IllegalArgumentException.class, () -> main.section("slicer", "../outside"));
        assertTrue(main.section("slicer", "cura").read().isEmpty());
        assertFalse(Files.exists(directory.resolve("slicers/cura.properties")));
        assertFalse(Files.exists(main.path()));
    }
}
