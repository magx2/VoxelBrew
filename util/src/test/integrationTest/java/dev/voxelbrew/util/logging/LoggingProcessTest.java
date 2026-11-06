package dev.voxelbrew.util.logging;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class LoggingProcessTest {
    @TempDir Path directory;

    @Test
    @Timeout(60)
    void liveApplicationsShareTheActiveFileAcrossStartupAndSizeRotation() throws Exception {
        Path home = directory.resolve("user home with % spaces");
        new UserConfiguration(home.resolve("config.properties"))
                .update(
                        Map.of(
                                "logging.level",
                                "DEBUG",
                                "logging.console.level",
                                "OFF",
                                "logging.file.limit",
                                "4096",
                                "logging.file.count",
                                "20"),
                        Set.of());
        var processes = new ArrayList<Process>();
        try {
            processes.add(start(home, "gui"));
            awaitReady(home, "gui", processes.getFirst());
            assertTrue(Files.exists(home.resolve("log/app.log")));
            try (var files = Files.list(home.resolve("log"))) {
                assertEquals(
                        0,
                        files.filter(p -> p.getFileName().toString().startsWith("app-")).count(),
                        "initialize twice must not rotate twice");
            }
            processes.add(start(home, "cli"));
            awaitReady(home, "cli", processes.getLast());
            try (var files = Files.list(home.resolve("log"))) {
                assertEquals(
                        1,
                        files.filter(p -> p.getFileName().toString().startsWith("app-")).count());
            }
            for (var process : processes) {
                process.getOutputStream().write('\n');
                process.getOutputStream().flush();
            }
            for (var process : processes) {
                assertTrue(process.waitFor(30, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue());
            }
            var content = new StringBuilder();
            try (var files = Files.list(home.resolve("log"))) {
                var logs = files.filter(p -> p.toString().endsWith(".log")).toList();
                assertTrue(logs.size() > 2, "Concurrent writes must exercise size rotation");
                for (var path : logs) {
                    assertTrue(Files.size(path) <= 4096);
                    content.append(Files.readString(path));
                }
            }
            String text = content.toString();
            for (String component : List.of("gui", "cli")) {
                assertEquals("", Files.readString(directory.resolve(component + ".out")));
                assertEquals("", Files.readString(directory.resolve(component + ".err")));
                assertTrue(text.contains("[" + component + ":"));
                assertTrue(text.contains("uncaught-worker-" + component));
                for (int i = 0; i < 100; i++) {
                    String marker = "event-" + component + "-" + i + ";";
                    assertEquals(
                            1,
                            text.split(java.util.regex.Pattern.quote(marker), -1).length - 1,
                            marker);
                }
            }
        } finally {
            for (var process : processes) {
                if (process.isAlive()) process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @Timeout(45)
    void unavailableLogDirectoryFallsBackToConsoleWithoutPreventingStartup() throws Exception {
        Path home = Files.createDirectory(directory.resolve("blocked home"));
        Files.writeString(home.resolve("log"), "existing regular file");
        var process = start(home, "cli");
        try {
            awaitReady(home, "cli", process);
            process.getOutputStream().write('\n');
            process.getOutputStream().flush();
            assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            assertEquals(0, process.exitValue());
            assertEquals("", Files.readString(directory.resolve("cli.out")));
            String errors = Files.readString(directory.resolve("cli.err"));
            assertTrue(errors.contains("Cannot configure file logging"));
            assertTrue(errors.contains("uncaught-worker-cli Zażółć ☕"));
            assertFalse(errors.contains("Starting cli version"));
            assertEquals("existing regular file", Files.readString(home.resolve("log")));
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private Process start(Path home, String component) throws Exception {
        var locations = new ArrayList<String>();
        for (var type :
                List.of(
                        Logging.class,
                        Writer.class,
                        org.slf4j.LoggerFactory.class,
                        Class.forName("org.slf4j.jul.JULServiceProvider"))) {
            locations.add(
                    Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI())
                            .toString());
        }
        return new ProcessBuilder(
                        Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                        "-Dvoxelbrew.home=" + home,
                        "-cp",
                        String.join(java.io.File.pathSeparator, locations),
                        Writer.class.getName(),
                        component)
                .redirectOutput(directory.resolve(component + ".out").toFile())
                .redirectError(directory.resolve(component + ".err").toFile())
                .start();
    }

    private void awaitReady(Path home, String component, Process process) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(home.resolve(component + ".ready"))) {
            assertTrue(process.isAlive(), Files.readString(directory.resolve(component + ".err")));
            assertTrue(System.nanoTime() < deadline, "Logging process did not initialize");
            Thread.sleep(20);
        }
    }

    @lombok.extern.slf4j.Slf4j
    public static final class Writer {
        public static void main(String[] args) throws Exception {
            Logging.initialize(args[0]);
            Logging.initialize(args[0]);
            try {
                Files.writeString(
                        dev.voxelbrew.util.config.ApplicationPaths.home()
                                .resolve(args[0] + ".ready"),
                        "ready");
                if (System.in.read() < 0) throw new IllegalStateException("Missing start signal");
                for (int i = 0; i < 100; i++) log.debug("event-{}-{}; Zażółć ☕", args[0], i);
                var failed =
                        Thread.ofPlatform()
                                .start(
                                        () -> {
                                            throw new IllegalStateException(
                                                    "uncaught-worker-" + args[0] + " Zażółć ☕");
                                        });
                failed.join();
            } finally {
                Logging.close();
            }
        }
    }
}
