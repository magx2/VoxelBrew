package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
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

    @Test
    void embeddedRealSliceSurvivesReopenWithoutPublishingDestination() throws Exception {
        var session = document();
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.startEmbedded(session.document(), new PrusaSlicerBackend(), false);
            await(future::isDone);
            var sliced = future.get();
            assertFalse(Files.exists(sliced.output()));
            assertFalse(sliced.job().lines().isEmpty());
            session.setGcode(controller.completedGcode());
        }
        var reopened = dev.voxelbrew.engine.persistence.DocumentCodec.decode(
                dev.voxelbrew.engine.persistence.DocumentCodec.encode(session.document()));
        try (var controller = new PrintController(callbacks::add, () -> reopened)) {
            controller.restoreEmbedded(reopened);
            await(() -> controller.state() == PrintController.State.SUCCEEDED);
            assertEquals(reopened.activeGcode(), controller.result().job().lines());
            assertEquals(40, controller.result().job().modelLayers().size());
        }
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

    private Map<String, Path> destinations(CadDocument document, String stem) {
        var result = new LinkedHashMap<String, Path>();
        for (int i = 0; i < document.printSetup().plates().size(); i++)
            result.put(
                    document.printSetup().plates().get(i).id(),
                    directory.resolve(stem + "-plate-" + (i + 1) + ".gcode"));
        return result;
    }

    @Test
    void batchSlicesNonEmptyPlatesAndKeepsSelectedPreview() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("second")
                .withInstances(List.of(new BodyInstance("other", "height.body", Transform.translation(80, 80, 0))))
                .addPlate("empty")
                .selectPlate("plate-1"));
        var original = session.document();
        try (var controller = new PrintController(callbacks::add, session::document)) {
            controller.startAll(
                    original,
                    new PrusaSlicerBackend(),
                    PrusaSlicerBackend.defaultExecutable(),
                    destinations(session.document(), "batch"),
                    false);
            await(() -> !controller.running());
            assertEquals(PrintController.State.SUCCEEDED, controller.state(), controller.message());
            assertTrue(Files.exists(directory.resolve("batch-plate-1.gcode")));
            assertTrue(Files.exists(directory.resolve("batch-plate-2.gcode")));
            assertFalse(Files.exists(directory.resolve("batch-plate-3.gcode")));
            assertEquals(
                    directory.resolve("batch-plate-1.gcode"),
                    controller.result().output());
            assertEquals(original, session.document());
        }
    }

    @Test
    void batchPreflightsLaterPlatesBeforePublishingAnyFile() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("invalid")
                .withInstances(List.of(new BodyInstance("other", "height.body", Transform.translation(500, 500, 0))))
                .selectPlate("plate-1"));
        try (var controller = new PrintController(callbacks::add, session::document)) {
            controller.startAll(
                    session.document(),
                    new PrusaSlicerBackend(),
                    PrusaSlicerBackend.defaultExecutable(),
                    destinations(session.document(), "bad"),
                    false);
            await(() -> !controller.running());
            assertEquals(PrintController.State.FAILED, controller.state());
            assertFalse(Files.exists(directory.resolve("bad-plate-1.gcode")));
        }
    }

    @Test
    void batchCanCancelAfterFirstPlateWasPublished() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("second")
                .withInstances(List.of(new BodyInstance("other", "height.body", Transform.translation(80, 80, 0))))
                .selectPlate("plate-1"));
        var held = new HeldBackend();
        var real = new PrusaSlicerBackend();
        var backend = new SlicerBackend() {
            int calls;

            public Descriptor descriptor() {
                return real.descriptor();
            }

            public Probe probe(Path path, Cancellation token) throws IOException {
                return real.probe(path, token);
            }

            public RunResult slice(Request request) throws IOException {
                return ++calls == 1 ? real.slice(request) : held.slice(request);
            }
        };
        try (var controller = new PrintController(callbacks::add, session::document)) {
            controller.startAll(
                    session.document(),
                    backend,
                    PrusaSlicerBackend.defaultExecutable(),
                    destinations(session.document(), "cancel"),
                    false);
            await(() -> held.entered.getCount() == 0);
            assertTrue(Files.exists(directory.resolve("cancel-plate-1.gcode")));
            controller.cancel();
            held.release.countDown();
            await(() -> !controller.running());
            assertEquals(PrintController.State.CANCELLED, controller.state());
            assertFalse(Files.exists(directory.resolve("cancel-plate-2.gcode")));
        }
    }

    @Test
    void realSliceUsesOnlySelectedPlateAndSwitchingInvalidatesPreview() throws Exception {
        var session = document();
        session.setPrintSetup(session.document()
                .printSetup()
                .addPlate("off-bed")
                .withInstances(List.of(new BodyInstance("outside", "height.body", Transform.translation(500, 500, 0))))
                .selectPlate("plate-1"));
        try (var controller = new PrintController(callbacks::add, session::document)) {
            var future = controller.start(
                    session.document(),
                    new PrusaSlicerBackend(),
                    PrusaSlicerBackend.defaultExecutable(),
                    directory.resolve("selected-plate.gcode"),
                    false);
            await(() -> !controller.running());
            assertEquals(PrintController.State.SUCCEEDED, controller.state(), controller.message());
            assertFalse(future.get().job().segments().isEmpty());
            assertTrue(future.get().job().segments().stream()
                    .filter(GcodeJob.Segment::deposits)
                    .allMatch(segment -> segment.x1() < 200 && segment.y1() < 200));
            session.setPrintSetup(session.document().printSetup().selectPlate("off-bed"));
            controller.documentChanged(session.document());
            assertEquals(PrintController.State.OUTDATED, controller.state());
            assertNull(controller.result());
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
