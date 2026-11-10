package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.GcodeJob;
import dev.voxelbrew.importer.api.GcodeSource;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmbeddedPreviewTest {
    private final Queue<Runnable> work = new ArrayDeque<>(), ui = new ArrayDeque<>();
    private final AtomicReference<CadDocument> current =
            new AtomicReference<>(CadDocument.empty().withGcode(Map.of("plate-1", List.of("G90"))));
    private boolean failRead;
    private final ExecutorService worker = new AbstractExecutorService() {
        public void execute(Runnable task) {
            work.add(task);
        }

        public void shutdown() {}

        public List<Runnable> shutdownNow() {
            return List.of();
        }

        public boolean isShutdown() {
            return false;
        }

        public boolean isTerminated() {
            return false;
        }

        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }
    };

    private PrintController controller() {
        return new PrintController(ui::add, current::get, Path.of("."), worker, null, null, new GcodeSource() {
            public GcodeJob read(Path path) {
                throw new AssertionError("Embedded jobs require no files");
            }

            public GcodeJob readLines(List<String> lines) throws IOException {
                if (failRead) throw new IOException("broken job");
                return new GcodeJob(List.of(), List.of(), OptionalDouble.empty(), Optional.empty(), Map.of(), lines);
            }
        });
    }

    private void finish() {
        work.remove().run();
        ui.remove().run();
    }

    @Test
    void cleanAndGeometryInvalidationDiscardQueuedRestores() {
        try (var controller = controller()) {
            controller.restoreEmbedded(current.get());
            work.remove().run();
            controller.clear();
            ui.remove().run();
            assertEquals(PrintController.State.IDLE, controller.state());
            assertNull(controller.result());
            controller.restoreEmbedded(current.get());
            finish();
            controller.clear();
            controller.restoreEmbedded(current.get());
            work.remove().run();
            controller.invalidateGeometry();
            ui.remove().run();
            assertEquals(PrintController.State.OUTDATED, controller.state());
            assertNull(controller.result());
        }
    }

    @Test
    void identicalJobsOnDifferentPlatesRestoreAfterSelectionChanges() {
        var setup = current.get().printSetup().addPlate("second").selectPlate("plate-1");
        var document = current.get();
        current.set(new CadDocument(document.id(), List.of(), 0, setup)
                .withGcode(Map.of("plate-1", List.of("G90"), "second", List.of("G90"))));
        try (var controller = controller()) {
            controller.restoreEmbedded(current.get());
            finish();
            document = current.get();
            current.set(new CadDocument(document.id(), List.of(), 0, setup.selectPlate("second"))
                    .withGcode(document.gcode()));
            controller.documentChanged(current.get());
            controller.restoreEmbedded(current.get());
            finish();
            assertEquals(PrintController.State.SUCCEEDED, controller.state());
            assertNotNull(controller.result());
        }
    }

    @Test
    void replacingEmbeddedLinesAtTheSameRevisionRefreshesPreview() {
        try (var controller = controller()) {
            controller.restoreEmbedded(current.get());
            finish();
            current.set(current.get().withGcode(Map.of("plate-1", List.of("G21", "G90"))));
            controller.restoreEmbedded(current.get());
            finish();
            assertEquals(List.of("G21", "G90"), controller.result().job().lines());
        }
    }

    @Test
    void failedReadsCanRetryAndOldFailuresCannotOverwriteCleanState() {
        try (var controller = controller()) {
            failRead = true;
            controller.restoreEmbedded(current.get());
            finish();
            assertTrue(controller.message().contains("broken job"));
            failRead = false;
            controller.restoreEmbedded(current.get());
            finish();
            assertEquals(PrintController.State.SUCCEEDED, controller.state());
            controller.clear();
            failRead = true;
            controller.restoreEmbedded(current.get());
            work.remove().run();
            controller.clear();
            ui.remove().run();
            assertEquals("Ready to slice", controller.message());
        }
    }
}
