package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.printing.SlicerRegistry;
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
