package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpTokenStoreTest {
    @TempDir
    Path directory;

    @Test
    void secretSurvivesRestartAndCanBeRotated() throws Exception {
        var file = directory.resolve("mcp-token");
        String token = "a".repeat(43);
        assertEquals(token, McpTokenStore.load(file, () -> token));
        assertEquals(token, McpTokenStore.load(file, () -> "b".repeat(43)));
        McpTokenStore.save(file, "c".repeat(43));
        assertEquals("c".repeat(43), McpTokenStore.load(file, () -> token));
        if (Files.getFileStore(file).supportsFileAttributeView("posix"))
            assertEquals(
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"),
                    Files.getPosixFilePermissions(file));
    }
}
