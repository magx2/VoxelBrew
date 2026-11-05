package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.sketch.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SketchControllerTest {
    @Test
    void textFormulaDraftsRebuildAndKeepBindingsInUndoHistory() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var text = SketchText.create("text", "Width: 5", "SansSerif", 0, 0, 0, 5, 0, 0, "", false);
        var geometry = new SketchGeometry(List.of(text), List.of(), List.of("text"));
        var parameters =
                dev.voxelbrew.engine.parameters.ParameterSet.empty()
                        .put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "5", "")
                        .bind(
                                new dev.voxelbrew.engine.parameters.ParameterSet.Target(
                                        "sketch", "text:text:content"),
                                "=\"Width: \"+$width");
        try (var controller = new SketchController(geometry, parameters, "sketch", queue::add)) {
            await(controller, queue);
            controller.edit(
                    controller.definition(),
                    parameters.put(
                            "width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "8", ""));
            await(controller, queue);
            assertTrue(controller.canFinish());
            assertEquals("Width: 8", controller.definition().entity("text").text().content());
            controller.undo();
            await(controller, queue);
            assertEquals("Width: 5", controller.definition().entity("text").text().content());
            controller.redo();
            await(controller, queue);
            assertEquals("Width: 8", controller.definition().entity("text").text().content());
            controller.edit(SketchGeometry.empty());
            await(controller, queue);
            assertTrue(controller.parameters().bindings().isEmpty());
            controller.undo();
            await(controller, queue);
            assertEquals(parameters.bindings(), controller.parameters().bindings());
        }
    }

    private static void await(SketchController controller, ConcurrentLinkedQueue<Runnable> queue)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (controller.busy() && System.nanoTime() < deadline) {
            Runnable event;
            while ((event = queue.poll()) != null) event.run();
            Thread.sleep(5);
        }
        assertFalse(controller.busy(), "Sketch worker completed");
    }

    @Test
    void parameterChangesAndBindingsShareLocalHistoryAndSolveOnTheWorker() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var p =
                dev.voxelbrew.engine.parameters.ParameterSet.empty()
                        .put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "60", "")
                        .bind(
                                new dev.voxelbrew.engine.parameters.ParameterSet.Target(
                                        "sketch", "constraint:width"),
                                "width");
        try (var controller =
                new SketchController(
                        Sketches.rectangle("r", 0, 0, 60, 40, true), p, "sketch", queue::add)) {
            await(controller, queue);
            controller.edit(
                    controller.definition(),
                    p.put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "80", ""));
            await(controller, queue);
            assertTrue(controller.canFinish());
            assertEquals(
                    80,
                    controller
                            .definition()
                            .entity("r-bottom")
                            .at(0)
                            .distance(controller.definition().entity("r-bottom").at(1)),
                    1e-5);
            controller.undo();
            await(controller, queue);
            assertEquals("60", controller.parameters().parameter("width").expression());
            controller.redo();
            await(controller, queue);
            assertEquals("80", controller.parameters().parameter("width").expression());
            controller.edit(
                    controller.definition(),
                    controller
                            .parameters()
                            .put(
                                    "width",
                                    dev.voxelbrew.engine.parameters.ParameterUnit.MM,
                                    "missing",
                                    ""));
            await(controller, queue);
            assertFalse(controller.canFinish());
            controller.undo();
            await(controller, queue);
            assertTrue(controller.canFinish());
        }
    }

    @Test
    void coalescesRequestsAndDiscardsOlderResults() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var a =
                new SketchGeometry(
                        List.of(Sketches.entity("circle", Kind.CIRCLE, false, 0, 0, 5)),
                        List.of(),
                        List.of("circle"));
        try (var controller =
                new SketchController(
                        a,
                        queue::add,
                        g -> {
                            if (calls.getAndIncrement() == 0) {
                                entered.countDown();
                                try {
                                    assertTrue(release.await(5, TimeUnit.SECONDS));
                                } catch (InterruptedException e) {
                                    throw new CancellationException();
                                }
                            }
                            return SketchSolver.solve(g);
                        })) {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            controller.edit(a.replace(Sketches.entity("circle", Kind.CIRCLE, false, 0, 0, 6)));
            var latest = a.replace(Sketches.entity("circle", Kind.CIRCLE, false, 0, 0, 7));
            controller.edit(latest);
            release.countDown();
            await(controller, queue);
            assertEquals(2, calls.get());
            assertEquals(latest, controller.definition());
            assertTrue(controller.canFinish());
            controller.undo();
            await(controller, queue);
            assertEquals(6, controller.definition().entity("circle").value(2));
            controller.redo();
            await(controller, queue);
            assertEquals(latest, controller.definition());
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidConstraintsStayEditableAndUndoRestoresTheValidSketch() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var original = Sketches.rectangle("r", 0, 0, 60, 40, true);
        try (var controller = new SketchController(original, queue::add)) {
            await(controller, queue);
            assertFalse(controller.canUndo());
            var conflict =
                    SketchConstraints.create(
                            original,
                            "bad",
                            Type.LENGTH,
                            List.of(original.entities().getFirst().ref()),
                            80);
            controller.edit(original.add(conflict));
            await(controller, queue);
            assertFalse(controller.canFinish());
            assertTrue(controller.definition().constraints().contains(conflict));
            controller.undo();
            await(controller, queue);
            assertTrue(controller.canFinish());
            assertEquals(original, controller.definition());
            controller.redo();
            await(controller, queue);
            assertFalse(controller.canFinish());
        }
    }

    @Test
    void dragPreviewCancelsWithoutHistoryAndReleaseCommitsOnlyOnce() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var original = Sketches.rectangle("r", 0, 0, 60, 40, false);
        try (var controller = new SketchController(original, queue::add)) {
            await(controller, queue);
            var point = original.entities().getFirst().ref(Part.START);
            controller.drag(point, new Vec(4, 5), false);
            await(controller, queue);
            assertEquals(original, controller.definition());
            assertFalse(controller.canUndo());
            assertFalse(controller.canFinish());
            controller.cancelDrag();
            await(controller, queue);
            assertEquals(original, controller.displayed());
            controller.drag(point, new Vec(4, 5), true);
            await(controller, queue);
            assertTrue(controller.canUndo());
            assertTrue(controller.canFinish());
            assertEquals(4, controller.definition().point(point).x(), 1e-6);
            controller.undo();
            await(controller, queue);
            assertEquals(original, controller.definition());
            assertFalse(controller.canUndo());
        }
    }

    @Test
    void fixedPointDragDoesNotChangeHistoryOrLeaveAFailedDraft() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var original = Sketches.rectangle("r", 0, 0, 60, 40, true);
        try (var controller = new SketchController(original, queue::add)) {
            await(controller, queue);
            controller.drag(original.entities().getFirst().ref(Part.START), new Vec(4, 5), true);
            await(controller, queue);
            assertEquals(original, controller.definition());
            assertFalse(controller.canUndo());
            assertTrue(controller.canFinish());
            assertFalse(controller.interactionError().isEmpty());
        }
    }

    @Test
    void closingRejectsQueuedCompletions() throws Exception {
        var queue = new ConcurrentLinkedQueue<Runnable>();
        var controller = new SketchController(SketchGeometry.empty(), queue::add);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (queue.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5);
        controller.close();
        Runnable event;
        while ((event = queue.poll()) != null) event.run();
        assertFalse(controller.canFinish());
        assertNull(controller.result());
    }
}
