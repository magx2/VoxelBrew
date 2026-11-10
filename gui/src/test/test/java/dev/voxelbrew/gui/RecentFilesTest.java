package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecentFilesTest {
    @TempDir
    Path directory;

    @Test
    void persistsMostRecentTenNormalizesAndMovesDuplicatesToFront() throws Exception {
        var configuration = new UserConfiguration(directory.resolve("config.properties"));
        configuration.set("unrelated", "preserved");
        var recent = new RecentFiles(configuration);
        assertTrue(recent.paths().isEmpty());
        for (int i = 0; i < 12; i++) recent.remember(directory.resolve("part " + i + ".vxb"));
        recent.remember(directory.resolve("folder/../part 5.vxb"));
        recent.save();
        var reopened = new RecentFiles(configuration).paths();
        assertEquals(recent.paths(), reopened);
        assertEquals(10, reopened.size());
        assertEquals(directory.resolve("part 5.vxb"), reopened.getFirst());
        assertEquals(directory.resolve("part 11.vxb"), reopened.get(1));
        assertFalse(reopened.contains(directory.resolve("part 0.vxb")));
        assertEquals("preserved", configuration.get("unrelated", ""));
        assertThrows(UnsupportedOperationException.class, () -> reopened.clear());
    }
}
