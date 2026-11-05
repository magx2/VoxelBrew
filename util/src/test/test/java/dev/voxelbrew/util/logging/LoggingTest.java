package dev.voxelbrew.util.logging;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.logging.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoggingTest {
    @TempDir Path directory;

    @Test
    void configurationWritesUtf8RotatingLogsWithoutChangingHostHandlers() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.update(
                Map.of(
                        "logging.level",
                        "FINE",
                        "logging.console.level",
                        "OFF",
                        "logging.file.limit",
                        "1024",
                        "logging.file.count",
                        "2"),
                Set.of());
        Handler[] rootHandlers = Logger.getLogger("").getHandlers();
        Path logs = directory.resolve("logs with % spaces");
        try {
            Logging.configure(config, logs, "cli");
            Logger log = Logger.getLogger("dev.voxelbrew.util.logging.test");
            for (int i = 0; i < 100; i++) log.fine("entry " + i + " ".repeat(20));
            log.info("Zażółć ☕ final marker");
        } finally {
            Logging.close();
        }
        assertArrayEquals(rootHandlers, Logger.getLogger("").getHandlers());
        try (var files = Files.list(logs)) {
            var paths = files.filter(path -> path.toString().endsWith(".log")).toList();
            assertEquals(2, paths.size());
            boolean found = false;
            for (Path path : paths)
                found |= Files.readString(path).contains("Zażółć ☕ final marker");
            assertTrue(found);
        }
    }

    @Test
    void rejectsInvalidConfigurationBeforeOpeningLogFiles() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("logging.file.count", "0");
        assertThrows(
                IllegalArgumentException.class,
                () -> Logging.configure(config, directory.resolve("logs"), "cli"));
        assertFalse(Files.exists(directory.resolve("logs")));
    }
}
