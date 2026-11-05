package dev.voxelbrew.util.config;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UserConfigurationTest {
    @TempDir Path directory;

    @Test
    void utf8RoundTripMergesUnknownKeysAndSupportsRemovalAndMigration() throws Exception {
        var config =
                new UserConfiguration(directory.resolve("settings with spaces/config.properties"));
        assertEquals(Map.of(), config.read());
        assertFalse(Files.exists(config.path().getParent()));
        config.update(
                Map.of("language", "pl", "plugin.vendor", "Zażółć gęślą jaźń ☕\nnext=line"),
                Set.of());
        new UserConfiguration(config.path()).set("appearance", "DARK");
        config.setIfAbsent("appearance", "LIGHT");
        assertEquals("DARK", config.get("appearance", "SYSTEM"));
        assertEquals("Zażółć gęślą jaźń ☕\nnext=line", config.get("plugin.vendor", ""));
        assertThrows(UnsupportedOperationException.class, () -> config.read().put("key", "value"));
        config.remove("language");
        assertEquals("en", config.get("language", "en"));
        assertEquals(2, config.read().size());
    }

    @Test
    void malformedConfigurationAndInvalidEditsPreserveExistingBytes() throws Exception {
        Path file = directory.resolve("config.properties");
        Files.writeString(file, "bad=\\uZZZZ\n");
        byte[] before = Files.readAllBytes(file);
        var config = new UserConfiguration(file);
        assertThrows(IOException.class, config::read);
        assertThrows(IOException.class, () -> config.set("appearance", "DARK"));
        assertArrayEquals(before, Files.readAllBytes(file));
        Files.writeString(file, "unknown=keep\n");
        assertThrows(IllegalArgumentException.class, () -> config.set(" ", "value"));
        assertEquals("unknown=keep\n", Files.readString(file));
        try (var entries = Files.list(directory)) {
            assertTrue(entries.noneMatch(path -> path.toString().endsWith(".tmp")));
        }
    }

    @Test
    void separateInstancesMergeConcurrentEditsWithoutLostKeys() throws Exception {
        Path file = directory.resolve("config.properties");
        try (var workers = Executors.newFixedThreadPool(4)) {
            var futures = new ArrayList<Future<?>>();
            for (int index = 0; index < 40; index++) {
                String key = "key." + index;
                futures.add(
                        workers.submit(
                                () -> {
                                    new UserConfiguration(file).set(key, key);
                                    return null;
                                }));
            }
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
        }
        var values = new UserConfiguration(file).read();
        assertEquals(40, values.size());
        values.forEach((key, value) -> assertEquals(key, value));
    }
}
