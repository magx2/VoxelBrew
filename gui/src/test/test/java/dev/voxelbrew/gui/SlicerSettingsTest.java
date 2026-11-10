package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.slicer.api.SlicerRuntime;
import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerSettingsTest {
    @TempDir
    Path directory;

    @Test
    void savesProvidersSeparatelyAndAppliesUpdatesWithoutChangingCapturedBackend() throws Exception {
        var configuration = new UserConfiguration(directory.resolve("settings.properties"));
        configuration.set("external.setting", "preserved");
        try (var catalog = SlicerRuntime.load().slicers().open(directory)) {
            var settings = new SlicerSettings(catalog, configuration);
            settings.save(Map.of(
                    "prusa-slicer",
                    Map.of("executable", "first-prusa"),
                    "cura",
                    Map.of(
                            "executable",
                            "cura-engine",
                            "definition",
                            "machine.json",
                            "settings",
                            "process.properties")));
            var captured = settings.backend("prusa-slicer");
            settings.save(Map.of("prusa-slicer", Map.of("executable", "second-prusa")));
            assertEquals(Path.of("first-prusa"), captured.executable());
            assertEquals(
                    Path.of("second-prusa"), settings.backend("prusa-slicer").executable());
            assertEquals(Path.of("cura-engine"), settings.backend("cura").executable());
            assertEquals("machine.json", settings.options("cura").get("definition"));
            assertEquals("preserved", configuration.get("external.setting", ""));
            assertEquals(Map.of("external.setting", "preserved"), configuration.read());
            assertEquals(
                    "second-prusa",
                    new UserConfiguration(directory.resolve("slicers/prusa-slicer.properties")).get("executable", ""));
            assertEquals(
                    "cura-engine",
                    new UserConfiguration(directory.resolve("slicers/cura.properties")).get("executable", ""));
            assertEquals(settings.options("cura"), new SlicerSettings(catalog, configuration).options("cura"));
            var before = configuration.read();
            assertThrows(
                    IllegalArgumentException.class,
                    () -> settings.save(Map.of("cura", Map.of("unknown-option", "bad"))));
            assertEquals(before, configuration.read());
        }
    }
}
