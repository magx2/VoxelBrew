package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.sketch.*;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

/** One isolated sketch-edit transaction. Solves are coalesced and never run on the FX thread. */
@lombok.extern.slf4j.Slf4j
final class SketchController implements AutoCloseable {
    @dagger.assisted.AssistedFactory
    interface Factory {
        SketchController create(SketchGeometry geometry, ParameterSet parameters, String featureId);
    }

    private record Request(
            long sequence,
            SketchGeometry geometry,
            ParameterSet parameters,
            SketchGeometry.Ref point,
            SketchGeometry.Vec target,
            boolean commitDrag) {}

    private final ExecutorService worker;
    private final Consumer<Runnable> dispatch;
    private final Function<SketchGeometry, SketchSolver.Result> solver;
    private final Object lock = new Object();

    private record State(SketchGeometry geometry, ParameterSet parameters) {}

    private final Deque<State> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

    @Getter(AccessLevel.PACKAGE)
    @Accessors(fluent = true)
    private ParameterSet parameters = ParameterSet.empty();

    private String featureId = "sketch";

    @Getter(AccessLevel.PACKAGE)
    @Accessors(fluent = true)
    private SketchGeometry definition;

    @Getter(AccessLevel.PACKAGE)
    @Accessors(fluent = true)
    private SketchGeometry displayed;

    @Getter(AccessLevel.PACKAGE)
    @Accessors(fluent = true)
    private SketchSolver.Result result;

    @Setter(AccessLevel.PACKAGE)
    @Accessors(fluent = true, chain = false)
    private Runnable onChanged = () -> {};

    private Request pending;
    private boolean running, dragging;

    @Getter(AccessLevel.PACKAGE)
    @Accessors(fluent = true)
    private boolean busy;

    @Getter(AccessLevel.PACKAGE)
    @Accessors(fluent = true)
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
        this(
                geometry,
                parameters,
                featureId,
                dispatch,
                SketchSolver::solve,
                WindowResources.worker("sketch-solver"));
    }

    @dagger.assisted.AssistedInject
    SketchController(
            @dagger.assisted.Assisted SketchGeometry geometry,
            @dagger.assisted.Assisted ParameterSet parameters,
            @dagger.assisted.Assisted String featureId,
            @javax.inject.Named("ui") Consumer<Runnable> dispatch,
            Function<SketchGeometry, SketchSolver.Result> solver,
            @javax.inject.Named("sketchWorkers")
                    java.util.function.Supplier<ExecutorService> workers) {
        this(geometry, parameters, featureId, dispatch, solver, workers.get());
    }

    private SketchController(
            SketchGeometry geometry,
            ParameterSet parameters,
            String featureId,
            Consumer<Runnable> dispatch,
            Function<SketchGeometry, SketchSolver.Result> solver,
            ExecutorService worker) {
        definition = displayed = geometry;
        this.parameters = parameters;
        this.featureId = featureId;
        this.dispatch = dispatch;
        this.solver = solver;
        this.worker = worker;
        submit(null, null, false);
    }

    SketchController(
            SketchGeometry geometry,
            Consumer<Runnable> dispatch,
            Function<SketchGeometry, SketchSolver.Result> solver) {
        this(
                geometry,
                ParameterSet.empty(),
                "sketch",
                dispatch,
                solver,
                WindowResources.worker("sketch-solver"));
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
        onChanged.run();
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
                log.debug(
                        "Sketch solve failed for feature {} request {}",
                        featureId,
                        request.sequence(),
                        failure);
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
                        onChanged.run();
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
