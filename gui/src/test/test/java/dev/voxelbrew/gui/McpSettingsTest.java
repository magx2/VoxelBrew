package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpSettingsTest {
    @TempDir
    Path directory;

    @Test
    void configurationRoundTripsWithoutLosingOtherPreferences() throws Exception {
        var store = new UserConfiguration(directory.resolve("config.properties"));
        assertEquals(new McpSettings(false, 0, 4, 30, 100), McpSettings.load(store));
        store.set("language", "pl");
        var settings = new McpSettings(true, 8042, 8, 15, 200);
        settings.save(store);
        assertEquals(settings, McpSettings.load(store));
        assertEquals("pl", store.get("language", "en"));
    }

    @Test
    void invalidValuesDoNotReplaceSavedConfiguration() throws Exception {
        var store = new UserConfiguration(directory.resolve("config.properties"));
        new McpSettings(false, 8042, 4, 30, 100).save(store);
        assertThrows(IllegalArgumentException.class, () -> new McpSettings(true, 80, 4, 30, 100));
        assertThrows(IllegalArgumentException.class, () -> new McpSettings(true, 8042, 0, 30, 100));
        assertEquals(8042, McpSettings.load(store).port());
        store.set("mcp.autoStart", "perhaps");
        assertThrows(IllegalArgumentException.class, () -> McpSettings.load(store));
    }
}
