package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Files;
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
        for (int i = 0; i < 12; i++) {
            Files.createFile(directory.resolve("part " + i + ".vxb"));
            recent.remember(directory.resolve("part " + i + ".vxb"));
        }
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

    @Test
    void loadingSkipsDeletedFilesAndDirectories() throws Exception {
        var configuration = new UserConfiguration(directory.resolve("config.properties"));
        Path existing = Files.createFile(directory.resolve("existing.vxb"));
        Path deleted = Files.createFile(directory.resolve("deleted.vxb"));
        Path folder = Files.createDirectory(directory.resolve("folder"));
        var recent = new RecentFiles(configuration);
        recent.remember(existing);
        recent.remember(deleted);
        recent.remember(folder);
        recent.save();
        Files.delete(deleted);

        assertEquals(java.util.List.of(existing), new RecentFiles(configuration).paths());
    }
}
