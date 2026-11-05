package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.*;
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
            ParameterSet parameters,
            SketchGeometry.Ref point,
            SketchGeometry.Vec target,
            boolean commitDrag) {}

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("voxelbrew-sketch-solver").factory());
    private final Consumer<Runnable> dispatch;
    private final Function<SketchGeometry, SketchSolver.Result> solver;
    private final Object lock = new Object();

    private record State(SketchGeometry geometry, ParameterSet parameters) {}

    private final Deque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private ParameterSet parameters = ParameterSet.empty();
    private String featureId = "sketch";
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
            ParameterSet parameters,
            String featureId,
            Consumer<Runnable> dispatch) {
        definition = displayed = geometry;
        this.parameters = parameters;
        this.featureId = featureId;
        this.dispatch = dispatch;
        this.solver = SketchSolver::solve;
        submit(null, null, false);
    }

    ParameterSet parameters() {
        return parameters;
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
        edit(next, FeatureDimensions.retainSketchBindings(parameters, featureId, next));
    }

    void edit(SketchGeometry next, ParameterSet nextParameters) {
        if (next.equals(definition) && nextParameters.equals(parameters)) return;
        interactionError = "";
        remember();
        definition = displayed = next;
        parameters = nextParameters;
        submit(null, null, false);
    }

    private void remember() {
        undo.push(new State(definition, parameters));
        while (undo.size() > 100) undo.removeLast();
        redo.clear();
    }

    void undo() {
        if (undo.isEmpty()) return;
        interactionError = "";
        redo.push(new State(definition, parameters));
        var previous = undo.pop();
        definition = displayed = previous.geometry();
        parameters = previous.parameters();
        submit(null, null, false);
    }

    void redo() {
        if (redo.isEmpty()) return;
        interactionError = "";
        undo.push(new State(definition, parameters));
        var next = redo.pop();
        definition = displayed = next.geometry();
        parameters = next.parameters();
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
        var request = new Request(++sequence, definition, parameters, point, target, commit);
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
                var geometry =
                        FeatureDimensions.resolveSketch(
                                request.geometry(), featureId, request.parameters());
                solved =
                        request.point() == null
                                ? solver.apply(geometry)
                                : Sketches.drag(geometry, request.point(), request.target());
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
