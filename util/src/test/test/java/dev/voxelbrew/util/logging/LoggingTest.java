package dev.voxelbrew.util.logging;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Map;
import java.util.Set;
import java.util.logging.*;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Slf4j
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
            for (int i = 0; i < 100; i++) log.debug("entry {} {}", i, " ".repeat(20));
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
    void slf4jRespectsConfiguredLevelsAndRoutesExceptionsOnlyToStderrAndFiles() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.update(Map.of("logging.level", "DEBUG", "logging.console.level", "WARN"), Set.of());
        Path logs = directory.resolve("logs");
        var stdout = new ByteArrayOutputStream();
        var stderr = new ByteArrayOutputStream();
        PrintStream originalOut = System.out, originalErr = System.err;
        Handler[] rootHandlers = Logger.getLogger("").getHandlers();
        try (var out = new PrintStream(stdout, true, StandardCharsets.UTF_8);
                var err = new PrintStream(stderr, true, StandardCharsets.UTF_8)) {
            System.setOut(out);
            System.setErr(err);
            try {
                Logging.configure(config, logs, "gui");
                assertTrue(log.isDebugEnabled());
                assertFalse(log.isTraceEnabled());
                log.trace("hidden trace");
                log.debug("revision {}", 42);
                log.warn("Cannot read {}", "plate.jcad", new IOException("file unavailable"));
            } finally {
                Logging.close();
                System.setOut(originalOut);
                System.setErr(originalErr);
            }
        }
        assertEquals("", stdout.toString(StandardCharsets.UTF_8));
        String console = stderr.toString(StandardCharsets.UTF_8);
        assertFalse(console.contains("revision"));
        assertFalse(console.contains("hidden trace"));
        assertEquals(1, console.split("Cannot read plate.jcad", -1).length - 1);
        assertTrue(console.contains("java.io.IOException: file unavailable"));
        assertArrayEquals(rootHandlers, Logger.getLogger("").getHandlers());
        try (var files = Files.list(logs)) {
            var paths = files.filter(path -> path.toString().endsWith(".log")).toList();
            assertEquals(1, paths.size());
            String content = Files.readString(paths.getFirst());
            assertTrue(content.contains("revision 42"));
            assertFalse(content.contains("hidden trace"));
            assertEquals(1, content.split("Cannot read plate.jcad", -1).length - 1);
            assertTrue(content.contains("java.io.IOException: file unavailable"));
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
