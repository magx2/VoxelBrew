package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.slicer.SlicerRegistry;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WindowResourcesTest {
    @TempDir
    Path directory;

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void windowLoadsExportersFromDefaultUserPluginDirectory() throws Exception {
        String home = System.getProperty("user.home");
        String override = System.getProperty("voxelbrew.home");
        try {
            System.setProperty("user.home", directory.toString());
            System.clearProperty("voxelbrew.home");
            Path plugins = java.nio.file.Files.createDirectories(directory.resolve(".voxelbrew/plugins"));
            java.nio.file.Files.copy(
                    Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
            var resources = new WindowResources(
                    ExporterRegistry.defaultDirectory(), ExporterRegistry::new, SlicerRegistry::new);
            try {
                assertTrue(resources.exporters.contains("document-probe"));
            } finally {
                resources.close();
                resources.whenClosed().toCompletableFuture().get(10, TimeUnit.SECONDS);
            }
        } finally {
            System.setProperty("user.home", home);
            if (override == null) System.clearProperty("voxelbrew.home");
            else System.setProperty("voxelbrew.home", override);
        }
    }

    @Test
    void shutdownSignalsCompletionOnlyAfterCapturedWorkHasDrained() throws Exception {
        var resources = new WindowResources(directory, ExporterRegistry::new, SlicerRegistry::new);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var disposal = resources.whenClosed().toCompletableFuture();
        try {
            var write = resources.files.submit(() -> {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
                return "published";
            });
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            assertTimeoutPreemptively(
                    Duration.ofSeconds(2), resources::close, "Window close must not block the JavaFX thread");
            resources.close();
            assertFalse(disposal.isDone(), "A draining writer still needs its plugin resources");
            release.countDown();
            disposal.get(10, TimeUnit.SECONDS);
            assertEquals("published", write.get());
            assertTrue(List.of(
                            resources.rebuild,
                            resources.files,
                            resources.slicing,
                            resources.validation,
                            resources.appearance)
                    .stream()
                    .allMatch(ExecutorService::isTerminated));
        } finally {
            release.countDown();
            resources.close();
            disposal.get(10, TimeUnit.SECONDS);
        }
    }
}
