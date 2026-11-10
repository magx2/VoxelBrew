package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.PrinterCatalog;
import dev.voxelbrew.util.config.PrinterResources;
import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterVisibilityTest {
    @TempDir
    Path directory;

    @Test
    void visibilityPersistsCanBeRestoredAndPreservesOtherPreferences() throws Exception {
        var configuration = new UserConfiguration(directory.resolve("config.properties"));
        configuration.set("language", "pl");
        var visibility = new PrinterVisibility(configuration);
        var catalog = new PrinterCatalog(new PrinterResources(directory.resolve("printers")));
        catalog.exportDefaults(Set.of("bambu-lab-a1-mini"));
        var entries = catalog.load().entries();
        assertTrue(visibility.hidden().isEmpty());
        var visible = entries.stream()
                .filter(PrinterCatalog.Entry::bundled)
                .map(e -> e.definition().printer().id())
                .collect(Collectors.toSet());
        visible.remove("bambu-lab-a1-mini");
        visibility.saveVisible(entries, visible);
        var reopened = new PrinterVisibility(new UserConfiguration(configuration.path()));
        assertEquals(Set.of("bambu-lab-a1-mini"), reopened.hidden());
        assertEquals("pl", configuration.get("language", ""));
        assertNotNull(catalog.load().require("bambu-lab-a1-mini"));
        visibility.saveVisible(entries, Set.of());
        assertEquals(62, reopened.hidden().size());
        assertFalse(reopened.hidden().contains("custom-bambu-lab-a1-mini"));
        visible.add("bambu-lab-a1-mini");
        reopened.saveVisible(entries, visible);
        assertTrue(visibility.hidden().isEmpty());
    }
}
