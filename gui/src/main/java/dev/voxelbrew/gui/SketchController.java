package dev.voxelbrew.gui;

import dev.voxelbrew.engine.sketch.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** One isolated sketch-edit transaction. Solves are coalesced and never run on the FX thread. */
final class SketchController implements AutoCloseable {
    private record Request(
            long sequence,
            SketchGeometry geometry,
            SketchGeometry.Ref point,
            SketchGeometry.Vec target,
            boolean commitDrag) {}

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("voxelbrew-sketch-solver").factory());
    private final Consumer<Runnable> dispatch;
    private final Function<SketchGeometry, SketchSolver.Result> solver;
    private final Object lock = new Object();
    private final Deque<SketchGeometry> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private SketchGeometry definition, displayed;
    private SketchSolver.Result result;
    private Runnable changed = () -> {};
    private Request pending;
    private boolean running, busy, dragging;
    private String interactionError = "";
    private volatile boolean closed;
    private volatile long sequence;

    SketchController(SketchGeometry geometry, Consumer<Runnable> dispatch) {
        this(geometry, dispatch, SketchSolver::solve);
    }

    SketchController(
            SketchGeometry geometry,
            Consumer<Runnable> dispatch,
            Function<SketchGeometry, SketchSolver.Result> solver) {
        definition = displayed = geometry;
        this.dispatch = dispatch;
        this.solver = solver;
        submit(null, null, false);
    }

    void onChanged(Runnable listener) {
        changed = listener;
    }

    SketchGeometry definition() {
        return definition;
    }

    SketchGeometry displayed() {
        return displayed;
    }

    SketchSolver.Result result() {
        return result;
    }

    boolean busy() {
        return busy;
    }

    String interactionError() {
        return interactionError;
    }

    boolean canUndo() {
        return !undo.isEmpty();
    }

    boolean canRedo() {
        return !redo.isEmpty();
    }

    boolean canFinish() {
        return !closed && !busy && !dragging && result != null && result.solved();
    }

    void edit(SketchGeometry next) {
        if (next.equals(definition)) return;
        interactionError = "";
        remember();
        definition = displayed = next;
        submit(null, null, false);
    }

    private void remember() {
        undo.push(definition);
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
    }

    void undo() {
        if (undo.isEmpty()) return;
        interactionError = "";
        redo.push(definition);
        definition = displayed = undo.pop();
        submit(null, null, false);
    }

    void redo() {
        if (redo.isEmpty()) return;
        interactionError = "";
        undo.push(definition);
        definition = displayed = redo.pop();
        submit(null, null, false);
    }

    void drag(SketchGeometry.Ref point, SketchGeometry.Vec target, boolean commit) {
        interactionError = "";
        submit(point, target, commit);
    }

    void cancelDrag() {
        displayed = definition;
        submit(null, null, false);
    }

    private void submit(SketchGeometry.Ref point, SketchGeometry.Vec target, boolean commit) {
        if (closed) return;
        busy = true;
        dragging = point != null;
        var request = new Request(++sequence, definition, point, target, commit);
        synchronized (lock) {
            pending = request;
            if (!running) {
                running = true;
                worker.execute(this::drain);
            }
        }
        changed.run();
    }

    private void drain() {
        while (!closed) {
            Request request;
            synchronized (lock) {
                request = pending;
                pending = null;
                if (request == null) {
                    running = false;
                    return;
                }
            }
            SketchSolver.Result solved;
            try {
                solved =
                        request.point() == null
                                ? solver.apply(request.geometry())
                                : Sketches.drag(
                                        request.geometry(), request.point(), request.target());
            } catch (CancellationException cancelled) {
                if (closed) return;
                continue;
            } catch (RuntimeException failure) {
                solved =
                        new SketchSolver.Result(
                                request.geometry(),
                                -1,
                                0,
                                java.util.List.of(
                                        new SketchSolver.Problem(
                                                "", "Solver failed: " + failure.getMessage())));
            }
            var completed = solved;
            dispatch.accept(
                    () -> {
                        if (closed || request.sequence() != sequence) return;
                        busy = false;
                        result = completed;
                        displayed = completed.solved() ? completed.geometry() : definition;
                        if (request.point() == null && completed.solved())
                            definition = completed.geometry();
                        if (request.commitDrag()) {
                            dragging = false;
                            if (completed.solved()) {
                                remember();
                                definition = displayed = completed.geometry();
                                submit(null, null, false);
                                return;
                            }
                            interactionError =
                                    "That point cannot move to the requested position while its constraints are active.";
                            displayed = definition;
                            submit(null, null, false);
                            return;
                        }
                        changed.run();
                    });
        }
    }

    @Override
    public void close() {
        closed = true;
        sequence++;
        synchronized (lock) {
            pending = null;
        }
        worker.shutdownNow();
    }
}
