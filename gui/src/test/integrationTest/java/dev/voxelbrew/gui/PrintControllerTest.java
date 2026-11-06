package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.printing.SlicerBackend;
import dev.voxelbrew.exporter.printing.PrusaSlicerBackend;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintControllerTest {
    @TempDir
    Path directory;

    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

    private void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (Runnable r; (r = callbacks.poll()) != null; ) r.run();
            if (done.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Controller timed out");
    }

    private static ModelSession document() {
        var s = new ModelSession(CadDocument.empty());
        s.add(new Feature.Sketch("plate", "p", new Feature.Plane("xy", 0), new Feature.Rectangle(0, 0, 20, 20)));
        s.add(new Feature.Extrude("height", new Feature.ProfileRef("plate", "p"), 8));
        s.setPrintSetup(s.document()
                .printSetup()
                .withInstances(List.of(new BodyInstance("part", "height.body", Transform.translation(10, 10, 0)))));
        return s;
    }

    private static final class HeldBackend implements SlicerBackend {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);

        public Descriptor descriptor() {
            return new PrusaSlicerBackend().descriptor();
        }

        public Probe probe(Path exe, Cancellation cancellation) {
            return new Probe("failure-lifecycle-fixture", Set.of());
        }

        public RunResult slice(Request request) throws IOException {
            request.diagnostics().accept("Fixture process entered");
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Latch timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            request.cancellation().check();
            Files.writeString(request.output(), "partial output");
            throw new IOException("forced failure");
        }
    }

    @Test
    void editProfileOrCancelInvalidatesRunningJobAndFailureRemainsDistinct() throws Exception {
        var session = document();
        Path output = directory.resolve("existing.gcode");
        Files.writeString(output, "original");
        try (var controller = new PrintController(callbacks::add, session::document)) {
            for (var expected : List.of(
                    PrintController.State.CANCELLED, PrintController.State.OUTDATED, PrintController.State.FAILED)) {
                var backend = new HeldBackend();
                var future = controller.start(session.document(), backend, Path.of("unused"), output, true);
                assertTrue(backend.entered.await(5, TimeUnit.SECONDS));
                if (expected == PrintController.State.CANCELLED) controller.cancel();
                if (expected == PrintController.State.OUTDATED) {
                    session.setPrintSetup(session.document()
                            .printSetup()
                            .withProfile("prusa-slicer", "generic-marlin-200-0.4", "PETG", ProcessSettings.DEFAULT));
                    controller.documentChanged(session.document());
                }
                backend.release.countDown();
                await(() -> !controller.running());
                assertEquals(expected, controller.state());
                assertNull(controller.result());
                assertTrue(future.isCompletedExceptionally());
                assertEquals("original", Files.readString(output));
            }
        }
    }

    @Test
    void delayedPublicationCannotBecomeCurrentAfterDocumentChange() throws Exception {
        var session = document();
        Path output = directory.resolve("existing.gcode");
        Files.writeString(output, "original");
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.start(
                    session.document(), new PrusaSlicerBackend(), PrusaSlicerBackend.defaultExecutable(), output, true);
            // Hold owner-thread callbacks while the real slicer works; edit before publication.
            session.editDimension("height", "distance", 5);
            controller.documentChanged(session.document());
            await(() -> !controller.running());
            assertEquals(PrintController.State.OUTDATED, controller.state());
            assertNull(controller.result());
            assertEquals("original", Files.readString(output));
            assertTrue(future.isCompletedExceptionally());
        }
    }

    @Test
    void rebuildingUnchangedDefinitionsInvalidatesCompletedAndRunningJobs() throws Exception {
        var session = document();
        var unchanged = session.document();
        Path output = directory.resolve("reload.gcode");
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var completed = controller.start(
                    unchanged, new PrusaSlicerBackend(), PrusaSlicerBackend.defaultExecutable(), output, false);
            await(() -> !controller.running());
            assertEquals(PrintController.State.SUCCEEDED, controller.state());
            assertNotNull(completed.join());
            byte[] published = Files.readAllBytes(output);
            controller.invalidateGeometry();
            assertEquals(PrintController.State.OUTDATED, controller.state());
            assertNull(controller.result());
            assertEquals(unchanged, session.document());
            var held = new HeldBackend();
            var future = controller.start(unchanged, held, Path.of("unused"), output, true);
            assertTrue(held.entered.await(5, TimeUnit.SECONDS));
            controller.invalidateGeometry();
            held.release.countDown();
            await(() -> !controller.running());
            assertEquals(PrintController.State.OUTDATED, controller.state());
            assertNull(controller.result());
            assertTrue(future.isCompletedExceptionally());
            assertArrayEquals(published, Files.readAllBytes(output));
        }
    }
}
