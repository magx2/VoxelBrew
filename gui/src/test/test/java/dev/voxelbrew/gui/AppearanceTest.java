package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppearanceTest {
    @TempDir
    Path directory;

    @Test
    void migratesExistingPreferenceOnceAndPreservesUnrelatedConfiguration() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("language", "pl");
        var appearance = new Appearance(config, () -> "DARK");
        assertEquals(Appearance.Choice.DARK, appearance.load());
        assertEquals("DARK", config.get("appearance", ""));
        appearance.save(Appearance.Choice.LIGHT);
        var reopened = new Appearance(new UserConfiguration(config.path()), () -> {
            fail("Legacy preference should not be consulted again");
            return null;
        });
        assertEquals(Appearance.Choice.LIGHT, reopened.load());
        assertEquals("pl", config.get("language", ""));
    }

    @Test
    void missingOrInvalidPreferenceUsesSystemAndAllowsRepair() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        var appearance = new Appearance(config, () -> null);
        assertEquals(Appearance.Choice.SYSTEM, appearance.load());
        config.set("appearance", "UNKNOWN");
        assertEquals(Appearance.Choice.SYSTEM, appearance.load());
        appearance.save(Appearance.Choice.DARK);
        assertEquals(Appearance.Choice.DARK, appearance.load());
    }
}
