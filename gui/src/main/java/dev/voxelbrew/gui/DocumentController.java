package dev.voxelbrew.gui;

import dev.voxelbrew.engine.export.*;
import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentStore;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;

/** UI-neutral document controller. UI dispatch confines all state changes to its owner thread. */
public final class DocumentController implements AutoCloseable {
    public record Body(String id, Feature.BodyRef output, MeshData mesh, double volume) {}

    public record Build(List<Body> bodies, List<Evaluation.Diagnostic> diagnostics, boolean valid) {
        public Build {
            bodies = List.copyOf(bodies);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private record Request(
            long sequence, long generation, long revision, CadDocument document, boolean preview) {}

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("voxelbrew-rebuild").factory());
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("voxelbrew-file-io").factory());
    private final Consumer<Runnable> dispatch;
    private final Function<CadDocument, Build> evaluator;
    private final Object queueLock = new Object();
    private Request pending;
    private boolean running;
    private volatile boolean closed;
    private volatile long sequence;
    private long documentGeneration;
    private ModelSession session = new ModelSession(CadDocument.empty());
    private CadDocument saved = session.document();
    private Path path;
    private Feature.Extrude preview;
    private Build build = new Build(List.of(), List.of(), true);
    private boolean rebuilding, ioBusy, displayedPreview;
    private String error = "";
    private Runnable changed = () -> {};

    public DocumentController(Consumer<Runnable> dispatch) {
        this(dispatch, DocumentController::evaluate);
    }

    DocumentController(Consumer<Runnable> dispatch, Function<CadDocument, Build> evaluator) {
        this.dispatch = dispatch;
        this.evaluator = evaluator;
    }

    public void onChanged(Runnable listener) {
        changed = listener;
    }

    public ModelSession.Snapshot snapshot() {
        return session.snapshot();
    }

    public Build build() {
        return build;
    }

    public Path path() {
        return path;
    }

    public boolean dirty() {
        return !session.document().equals(saved);
    }

    public boolean rebuilding() {
        return rebuilding;
    }

    public boolean ioBusy() {
        return ioBusy;
    }

    public boolean hasPreview() {
        return preview != null;
    }

    public boolean displayedPreview() {
        return displayedPreview;
    }

    public String error() {
        return error;
    }

    public boolean canExport() {
        var d = session.document();
        return !closed
                && !rebuilding
                && !ioBusy
                && !hasPreview()
                && !displayedPreview
                && build.valid()
                && !build.bodies().isEmpty()
                && d.cursor() == d.features().size();
    }

    public void newDocument() {
        replace(CadDocument.empty(), null);
    }

    private void replace(CadDocument document, Path source) {
        if (ioBusy) throw new IllegalStateException("Wait for the current file operation");
        session = new ModelSession(document);
        saved = document;
        path = source;
        preview = null;
        documentGeneration++;
        build = new Build(List.of(), List.of(), true);
        schedule(document, false);
    }

    public void add(Feature feature) {
        session.add(feature);
        preview = null;
        schedule(session.document(), false);
    }

    public void edit(String id, String field, double value) {
        session.editDimension(id, field, value);
        preview = null;
        schedule(session.document(), false);
    }

    public void replaceFeature(Feature feature) {
        session.replace(feature);
        preview = null;
        schedule(session.document(), false);
    }

    public void printSetup(dev.voxelbrew.engine.printing.PrintSetup setup) {
        session.setPrintSetup(setup);
        preview = null;
        schedule(session.document(), false);
    }

    public void cursor(int position) {
        preview = null;
        session.setCursor(position);
        schedule(session.document(), false);
    }

    public void undo() {
        preview = null;
        session.undo();
        schedule(session.document(), false);
    }

    public void redo() {
        preview = null;
        session.redo();
        schedule(session.document(), false);
    }

    public void previewExtrusion(Feature.Sketch sketch, double distance) {
        String id = preview == null ? "extrude-" + UUID.randomUUID() : preview.id();
        var candidate =
                new Feature.Extrude(
                        id, new Feature.ProfileRef(sketch.id(), sketch.profileId()), distance);
        var temporary = new ModelSession(session.document());
        temporary.add(candidate);
        preview = candidate;
        schedule(temporary.document(), true);
    }

    public void commitPreview() {
        if (preview == null) return;
        var feature = preview;
        add(feature);
    }

    public void cancelPreview() {
        if (preview != null || displayedPreview) {
            preview = null;
            schedule(session.document(), false);
        }
    }

    public CompletableFuture<Void> open(Path source) {
        requireIdleIo();
        ioBusy = true;
        changed.run();
        var result = new CompletableFuture<Void>();
        io.execute(
                () -> {
                    try {
                        var document = DocumentStore.load(source);
                        dispatch.accept(
                                () -> {
                                    if (closed) {
                                        result.cancel(false);
                                        return;
                                    }
                                    ioBusy = false;
                                    replace(document, source.toAbsolutePath());
                                    result.complete(null);
                                });
                    } catch (Exception failure) {
                        finishIo(result, failure);
                    }
                });
        return result;
    }

    public CompletableFuture<Void> save(Path destination, boolean overwrite) {
        requireIdleIo();
        var captured = session.document();
        long generation = documentGeneration;
        ioBusy = true;
        changed.run();
        var result = new CompletableFuture<Void>();
        io.execute(
                () -> {
                    try {
                        DocumentStore.save(captured, destination, overwrite);
                        dispatch.accept(
                                () -> {
                                    if (closed) {
                                        result.cancel(false);
                                        return;
                                    }
                                    ioBusy = false;
                                    if (documentGeneration == generation) {
                                        saved = captured;
                                        path = destination.toAbsolutePath();
                                    }
                                    changed.run();
                                    result.complete(null);
                                });
                    } catch (Exception failure) {
                        finishIo(result, failure);
                    }
                });
        return result;
    }

    public CompletableFuture<Void> export(
            ExporterRegistry registry,
            String format,
            String body,
            MeshOptions options,
            Map<String, String> values,
            Path destination,
            boolean overwrite) {
        return export(
                registry,
                format,
                ExportSelection.design(body),
                options,
                values,
                destination,
                overwrite);
    }

    public CompletableFuture<Void> export(
            ExporterRegistry registry,
            String format,
            ExportSelection selection,
            MeshOptions options,
            Map<String, String> values,
            Path destination,
            boolean overwrite) {
        if (!canExport())
            throw new IllegalStateException(
                    "Export requires the current complete, successfully rebuilt document");
        var captured = session.document();
        ioBusy = true;
        changed.run();
        var result = new CompletableFuture<Void>();
        io.execute(
                () -> {
                    try (var evaluation = Evaluator.evaluate(captured)) {
                        ModelView view = selection.view(captured, evaluation, options);
                        ExportService.export(
                                registry, format, view, options, values, destination, overwrite);
                        finishIo(result, null);
                    } catch (Exception failure) {
                        finishIo(result, failure);
                    }
                });
        return result;
    }

    private void finishIo(CompletableFuture<Void> future, Exception failure) {
        dispatch.accept(
                () -> {
                    if (closed) {
                        future.cancel(false);
                        return;
                    }
                    ioBusy = false;
                    if (failure != null) error = failure.getMessage();
                    changed.run();
                    if (failure == null) future.complete(null);
                    else future.completeExceptionally(failure);
                });
    }

    private void requireIdleIo() {
        if (closed || ioBusy)
            throw new IllegalStateException("Wait for the current file operation");
        error = "";
    }

    private void schedule(CadDocument document, boolean isPreview) {
        if (closed) return;
        long ticket = ++sequence;
        rebuilding = true;
        error = "";
        synchronized (queueLock) {
            pending =
                    new Request(
                            ticket, documentGeneration, session.revision(), document, isPreview);
            if (!running) {
                running = true;
                worker.execute(this::drain);
            }
        }
        changed.run();
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (queueLock) {
                request = pending;
                pending = null;
                if (request == null || closed) {
                    running = false;
                    return;
                }
            }
            Build result;
            String message = "";
            try {
                result = evaluator.apply(request.document());
            } catch (RuntimeException | LinkageError failure) {
                result = new Build(List.of(), List.of(), false);
                message = failure.getMessage();
            }
            Build complete = result;
            String failureMessage = message;
            dispatch.accept(
                    () -> {
                        var current = session.snapshot();
                        if (closed
                                || request.sequence() != sequence
                                || request.generation() != documentGeneration
                                || request.revision() != current.revision()
                                || !request.document().id().equals(current.document().id())) return;
                        build = complete;
                        rebuilding = false;
                        displayedPreview = request.preview();
                        error = failureMessage;
                        changed.run();
                    });
        }
    }

    static Build evaluate(CadDocument document) {
        try (var evaluation = Evaluator.evaluate(document)) {
            if (!evaluation.valid()) return new Build(List.of(), evaluation.diagnostics(), false);
            var bodies =
                    evaluation.bodies().stream()
                            .map(
                                    b ->
                                            new Body(
                                                    b.id(),
                                                    b.output(),
                                                    b.solid().tessellate(MeshOptions.DEFAULT),
                                                    b.solid().volume()))
                            .toList();
            return new Build(bodies, evaluation.diagnostics(), true);
        }
    }

    @Override
    public void close() {
        closed = true;
        ++sequence;
        synchronized (queueLock) {
            pending = null;
        }
        worker.shutdown();
        io.shutdown();
    }
}
