package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.exporter.MeshExportDialog;
import dev.voxelbrew.exporter.api.ExportRuntime;
import dev.voxelbrew.exporter.api.ExporterCatalog;
import dev.voxelbrew.importer.api.ImportRuntime;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

/** UI-neutral document controller. UI dispatch confines all state changes to its owner thread. */
@javax.inject.Singleton
@lombok.extern.slf4j.Slf4j
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class DocumentController implements AutoCloseable {
    public record Body(
            String id,
            Feature.BodyRef output,
            MeshData mesh,
            double volume,
            List<dev.voxelbrew.engine.geometry.PlanarFace> planarFaces,
            TechnicalEdges edges,
            int[] faceEdges,
            double[] edgeSamples) {
        public Body(String id, Feature.BodyRef output, MeshData mesh, double volume) {
            this(id, output, mesh, volume, List.of());
        }

        public Body(
                String id,
                Feature.BodyRef output,
                MeshData mesh,
                double volume,
                List<dev.voxelbrew.engine.geometry.PlanarFace> planarFaces) {
            this(id, output, mesh, volume, planarFaces, TechnicalEdges.from(mesh), new int[0], new double[0]);
        }

        public Body(
                String id,
                Feature.BodyRef output,
                MeshData mesh,
                double volume,
                List<dev.voxelbrew.engine.geometry.PlanarFace> planarFaces,
                int[] faceEdges,
                double[] edgeSamples) {
            this(id, output, mesh, volume, planarFaces, TechnicalEdges.from(mesh), faceEdges, edgeSamples);
        }

        public Body {
            planarFaces = List.copyOf(planarFaces);
            faceEdges = faceEdges.clone();
            edgeSamples = edgeSamples.clone();
        }

        @Override
        public int[] faceEdges() {
            return faceEdges.clone();
        }

        @Override
        public double[] edgeSamples() {
            return edgeSamples.clone();
        }

        public List<Integer> edgesOnFace(int face) {
            var result = new java.util.TreeSet<Integer>();
            for (int i = 0; i < faceEdges.length; i += 2) if (faceEdges[i] == face) result.add(faceEdges[i + 1]);
            return List.copyOf(result);
        }
    }

    public record Build(
            List<Body> bodies,
            List<Evaluation.Diagnostic> diagnostics,
            boolean valid,
            List<Feature> features,
            Map<String, dev.voxelbrew.engine.construction.ConstructionGeometry.Datum> construction,
            List<ConstructionFeature.Input> constructionInputs) {
        public Build(
                List<Body> bodies, List<Evaluation.Diagnostic> diagnostics, boolean valid, List<Feature> features) {
            this(bodies, diagnostics, valid, features, Map.of(), List.of());
        }

        public Build(List<Body> bodies, List<Evaluation.Diagnostic> diagnostics, boolean valid) {
            this(bodies, diagnostics, valid, List.of());
        }

        public Build {
            bodies = List.copyOf(bodies);
            diagnostics = List.copyOf(diagnostics);
            features = List.copyOf(features);
            construction = Map.copyOf(construction);
            constructionInputs = List.copyOf(constructionInputs);
        }
    }

    private record Request(long sequence, long generation, long revision, CadDocument document, boolean preview) {}

    @NonNull
    private final Consumer<Runnable> dispatch;

    @NonNull
    private final Function<CadDocument, Build> evaluator;

    @NonNull
    private final Path pluginDirectory;

    @NonNull
    private final ExecutorService worker;

    @NonNull
    private final ExecutorService io;

    @NonNull
    private final dev.voxelbrew.importer.api.DocumentSource documents;

    @NonNull
    private final dev.voxelbrew.exporter.DocumentOutput documentWriter;

    @NonNull
    private final ExportRuntime.DocumentExporterFactory documentExporters;

    private final Object queueLock = new Object();
    private Request pending;
    private boolean running;
    private volatile boolean closed;
    private volatile long sequence;
    private long documentGeneration;
    private ModelSession session = new ModelSession(newDesktopDocument());
    private CadDocument saved = session.document();
    private boolean unsavedImport;

    @Getter
    @Accessors(fluent = true)
    private Path path;

    private Feature.Extrude preview;
    private String previewExpression = "";

    @Getter
    @Accessors(fluent = true)
    private Build build = new Build(List.of(), List.of(), true);

    @Getter
    @Accessors(fluent = true)
    private boolean rebuilding;

    @Getter
    @Accessors(fluent = true)
    private boolean ioBusy;

    @Getter
    @Accessors(fluent = true)
    private boolean displayedPreview;

    @Getter
    @Accessors(fluent = true)
    private String error = "";

    @Setter
    @Accessors(fluent = true, chain = false)
    private Runnable onChanged = () -> {};

    @Setter
    @Accessors(fluent = true, chain = false)
    private Consumer<Path> onFileUsed = ignored -> {};

    public DocumentController(Consumer<Runnable> dispatch) {
        this(dispatch, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    public DocumentController(Consumer<Runnable> dispatch, Path pluginDirectory) {
        this(dispatch, document -> evaluate(document, pluginDirectory), pluginDirectory);
    }

    DocumentController(Consumer<Runnable> dispatch, Function<CadDocument, Build> evaluator) {
        this(dispatch, evaluator, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    private DocumentController(
            Consumer<Runnable> dispatch, Function<CadDocument, Build> evaluator, Path pluginDirectory) {
        this(
                dispatch,
                evaluator,
                pluginDirectory,
                WindowResources.worker("rebuild"),
                WindowResources.worker("file-io"),
                ImportRuntime.load().documents(),
                ExportRuntime.load().documents());
    }

    DocumentController(
            Consumer<Runnable> dispatch,
            Function<CadDocument, Build> evaluator,
            Path pluginDirectory,
            ExecutorService worker,
            ExecutorService io,
            dev.voxelbrew.importer.api.DocumentSource documents,
            dev.voxelbrew.exporter.DocumentOutput documentWriter) {
        this(
                dispatch,
                evaluator,
                pluginDirectory,
                worker,
                io,
                documents,
                documentWriter,
                ExportRuntime.load()::documentExporter);
    }

    @javax.inject.Inject
    DocumentController(
            @javax.inject.Named("ui") Consumer<Runnable> dispatch,
            @javax.inject.Named("plugins") Path pluginDirectory,
            @javax.inject.Named("rebuild") ExecutorService worker,
            @javax.inject.Named("files") ExecutorService io,
            dev.voxelbrew.importer.api.DocumentSource documents,
            dev.voxelbrew.exporter.DocumentOutput documentWriter,
            ExportRuntime.DocumentExporterFactory documentExporters) {
        this(
                dispatch,
                document -> evaluate(document, pluginDirectory),
                pluginDirectory,
                worker,
                io,
                documents,
                documentWriter,
                documentExporters);
    }

    public ModelSession.Snapshot snapshot() {
        return session.snapshot();
    }

    public List<Feature> effectiveFeatures() {
        return !rebuilding
                        && build.features().size()
                                == session.document().features().size()
                ? build.features()
                : session.document().features();
    }

    public void parameters(dev.voxelbrew.engine.parameters.ParameterSet parameters) {
        session.setParameters(parameters);
        preview = null;
        schedule(session.document(), false);
    }

    public void configuration(String nameOrId) {
        session.selectConfiguration(nameOrId);
        preview = null;
        schedule(session.document(), false);
    }

    public void expression(String id, String field, String expression) {
        session.bindExpression(id, field, expression);
        preview = null;
        schedule(session.document(), false);
    }

    public void sketch(Feature.Sketch sketch, dev.voxelbrew.engine.parameters.ParameterSet parameters, boolean create) {
        if (create) session.add(sketch, parameters);
        else session.replace(sketch, parameters);
        preview = null;
        schedule(session.document(), false);
    }

    private java.util.function.Supplier<java.util.Map<String, String>> printWorkspace =
            () -> session.document().printSetup().workspaceSettings();

    void printWorkspace(java.util.function.Supplier<java.util.Map<String, String>> capture) {
        printWorkspace = capture;
    }

    private void capturePrintWorkspace() {
        var setup = session.document().printSetup();
        var settings = printWorkspace.get();
        if (!settings.equals(setup.workspaceSettings())) {
            var jobs = session.document().gcode();
            var artifacts = session.document().printArtifacts();
            session.setPrintSetup(setup.withWorkspaceSettings(settings));
            session.setGcode(jobs);
            session.setPrintArtifacts(artifacts);
        }
    }

    public boolean dirty() {
        return unsavedImport
                || !session.document().equals(saved)
                || !printWorkspace.get().equals(session.document().printSetup().workspaceSettings());
    }

    public boolean hasPreview() {
        return preview != null;
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
        replace(newDesktopDocument(), null);
    }

    static CadDocument newDesktopDocument() {
        var document = CadDocument.empty();
        return new CadDocument(
                document.id(),
                document.features(),
                document.cursor(),
                document.printSetup().withSupportsDisabled(true),
                document.parameters());
    }

    private void replace(CadDocument document, Path source) {
        if (ioBusy) throw new IllegalStateException("Wait for the current file operation");
        session = new ModelSession(document);
        saved = document;
        unsavedImport = false;
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

    public void add(Feature feature, dev.voxelbrew.engine.parameters.ParameterSet parameters) {
        session.add(feature, parameters);
        preview = null;
        schedule(session.document(), false);
    }

    public void edit(String id, String field, double value) {
        session.editDimension(id, field, value, pluginDirectory);
        preview = null;
        schedule(session.document(), false);
    }

    public void replaceFeature(Feature feature) {
        session.replace(feature);
        preview = null;
        schedule(session.document(), false);
    }

    public void deleteFeatures(int from, int to) {
        if (ioBusy || rebuilding || hasPreview()) throw new IllegalStateException("Wait for the current operation");
        session.deleteFeatures(from, to);
        schedule(session.document(), false);
    }

    public void rebuild() {
        session.invalidateGcode();
        preview = null;
        schedule(session.document(), false);
    }

    public void printSetup(dev.voxelbrew.engine.printing.PrintSetup setup) {
        session.setPrintSetup(setup);
        preview = null;
        schedule(session.document(), false);
    }

    void gcode(java.util.Map<String, java.util.List<String>> jobs) {
        capturePrintWorkspace();
        session.setGcode(jobs);
        onChanged.run();
    }

    void printArtifacts(java.util.Map<String, dev.voxelbrew.engine.printing.PrintArtifact> artifacts) {
        session.setPrintArtifacts(artifacts);
        onChanged.run();
    }

    void invalidateGcode() {
        if (session.document().gcode().isEmpty()) return;
        session.invalidateGcode();
        onChanged.run();
    }

    void captureSlicerWorkspace() {
        capturePrintWorkspace();
    }

    public void bodyStyle(String bodyId, BodyStyle style) {
        if (!canExport() || build.bodies().stream().noneMatch(b -> b.id().equals(bodyId))) {
            throw new IllegalStateException("Select a current body at the end of a successful rebuild");
        }
        session.setBodyStyle(bodyId, style);
        schedule(session.document(), false);
    }

    public void renameBody(String bodyId, String name) {
        if (!canExport() || build.bodies().stream().noneMatch(b -> b.id().equals(bodyId)))
            throw new IllegalStateException("Select a current body at the end of a successful rebuild");
        long before = session.revision();
        session.renameBody(bodyId, name);
        if (session.revision() != before) {
            log.info(
                    "Renamed body {} in document {}", bodyId, session.document().id());
            schedule(session.document(), false);
        }
    }

    public void renameFeature(String featureId, String name) {
        if (ioBusy || rebuilding || hasPreview()) throw new IllegalStateException("Wait for the current operation");
        session.renameFeature(featureId, name);
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
        previewExtrusion(sketch, sketch.profileId(), distance);
    }

    public void previewExtrusion(Feature.Sketch sketch, String profileId, double distance) {
        previewExpression = "";
        String id = preview == null
                ? "extrude-" + session.document().uuidGenerator().next()
                : preview.id();
        var candidate = new Feature.Extrude(id, new Feature.ProfileRef(sketch.id(), profileId), distance);
        var temporary = new ModelSession(session.document());
        temporary.add(candidate);
        preview = candidate;
        schedule(temporary.document(), true);
    }

    public void previewExtrusion(Feature.Sketch sketch, String expression) {
        previewExtrusion(sketch, sketch.profileId(), expression);
    }

    public void previewExtrusion(Feature.Sketch sketch, String profileId, String expression) {
        double value =
                session.document().parameters().value(expression, dev.voxelbrew.engine.parameters.ParameterUnit.MM);
        previewExtrusion(sketch, profileId, value);
        if (!expression.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?"))
            previewExpression = expression;
    }

    public void commitPreview() {
        if (preview == null) return;
        var feature = preview;
        if (previewExpression.isEmpty()) add(feature);
        else {
            var parameters = session.document()
                    .parameters()
                    .bind(
                            new dev.voxelbrew.engine.parameters.ParameterSet.Target(feature.id(), "distance"),
                            previewExpression);
            session.add(feature, parameters);
            preview = null;
            previewExpression = "";
            schedule(session.document(), false);
        }
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
        onChanged.run();
        var result = new CompletableFuture<Void>();
        io.execute(() -> {
            try {
                var document = documents.load(source);
                dispatch.accept(() -> {
                    if (closed) {
                        result.cancel(false);
                        return;
                    }
                    ioBusy = false;
                    replace(document, source.toAbsolutePath());
                    onFileUsed.accept(source);
                    log.info("Opened document {} from {}", document.id(), source);
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
        capturePrintWorkspace();
        var captured = session.document();
        long generation = documentGeneration;
        ioBusy = true;
        onChanged.run();
        var result = new CompletableFuture<Void>();
        io.execute(() -> {
            try {
                documentWriter.save(captured, destination, overwrite);
                dispatch.accept(() -> {
                    if (closed) {
                        result.cancel(false);
                        return;
                    }
                    ioBusy = false;
                    if (documentGeneration == generation) {
                        saved = captured;
                        unsavedImport = false;
                        path = destination.toAbsolutePath();
                    }
                    onFileUsed.accept(destination);
                    onChanged.run();
                    result.complete(null);
                });
            } catch (Exception failure) {
                finishIo(result, failure);
            }
        });
        return result;
    }

    /** Exporters select destinations on the UI thread and write on this window's file worker. */
    public CompletableFuture<Void> saveAs(dev.voxelbrew.exporter.api.ExportWindow parent) {
        return exportDocument(documentExporters.create(io, documentWriter), true, parent);
    }

    public CompletableFuture<Void> exportDocument(
            dev.voxelbrew.exporter.api.ExporterProvider provider, dev.voxelbrew.exporter.api.ExportWindow parent) {
        return exportDocument(
                provider.create(io, pluginDirectory), provider.descriptor().id().equals("vxb"), parent);
    }

    private CompletableFuture<Void> exportDocument(
            dev.voxelbrew.exporter.api.Exporter<?> exporter,
            boolean savesDocument,
            dev.voxelbrew.exporter.api.ExportWindow parent) {
        requireIdleIo();

        capturePrintWorkspace();
        var captured = session.document();
        long generation = documentGeneration;
        ioBusy = true;
        onChanged.run();
        var result = new CompletableFuture<Void>();
        try {
            exporter.export(captured, parent).whenComplete((destination, failure) -> {
                if (failure != null) {
                    if (failure instanceof CancellationException) {
                        dispatch.accept(() -> {
                            ioBusy = false;
                            onChanged.run();
                            result.cancel(false);
                        });
                    } else
                        finishIo(
                                result,
                                failure instanceof Exception exception ? exception : new RuntimeException(failure));
                    return;
                }
                dispatch.accept(() -> {
                    if (closed) {
                        result.cancel(false);
                        return;
                    }
                    ioBusy = false;
                    if (savesDocument && destination instanceof Path savedPath && documentGeneration == generation) {
                        saved = captured;
                        unsavedImport = false;
                        path = savedPath;
                        onFileUsed.accept(savedPath);
                    }
                    onChanged.run();
                    result.complete(null);
                });
            });
        } catch (RuntimeException failure) {
            finishIo(result, failure);
        }
        return result;
    }

    /** Headless convenience path; the loader stays alive through asynchronous completion. */
    public CompletableFuture<Void> importFile(
            dev.voxelbrew.importer.api.ImporterCatalog.Factory importers,
            String format,
            Path source,
            Map<String, String> options) {
        try {
            var registry = importers.open(pluginDirectory);
            try {
                return importDocument(
                                registry.require(format),
                                dev.voxelbrew.importer.api.FileImportDialog.source(source, options))
                        .whenComplete((value, failure) -> {
                            try {
                                registry.close();
                            } catch (java.io.IOException closeFailure) {
                                log.warn("Closing importer registry", closeFailure);
                            }
                        });
            } catch (RuntimeException failure) {
                registry.close();
                throw failure;
            }
        } catch (java.io.IOException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /** Collects provider settings on the UI thread and reads on the window-owned file worker. */
    public CompletableFuture<Void> importDocument(
            dev.voxelbrew.importer.api.ImporterCatalog.Entry entry, dev.voxelbrew.importer.api.ImportWindow parent) {
        requireIdleIo();
        long generation = documentGeneration;
        ioBusy = true;
        onChanged.run();
        var result = new CompletableFuture<Void>();
        dev.voxelbrew.importer.api.ImportInvocation.importFrom(entry, io, parent)
                .whenComplete((imported, failure) -> {
                    if (failure != null) {
                        while (failure instanceof CompletionException && failure.getCause() != null)
                            failure = failure.getCause();
                        if (failure instanceof CancellationException) {
                            dispatch.accept(() -> {
                                ioBusy = false;
                                onChanged.run();
                                result.cancel(false);
                            });
                        } else
                            finishIo(
                                    result,
                                    failure instanceof Exception exception ? exception : new RuntimeException(failure));
                        return;
                    }
                    dispatch.accept(() -> {
                        if (closed || documentGeneration != generation) {
                            result.cancel(false);
                            return;
                        }
                        ioBusy = false;
                        if (imported instanceof dev.voxelbrew.importer.api.ImportResult.Document loaded) {
                            replace(loaded.document(), null);
                        }
                        unsavedImport = true;
                        onChanged.run();
                        result.complete(null);
                    });
                });
        return result;
    }

    public CompletableFuture<Void> export(
            ExporterCatalog registry,
            String format,
            String body,
            MeshOptions options,
            Map<String, String> values,
            Path destination,
            boolean overwrite) {
        return export(registry, format, ExportSelection.design(body), options, values, destination, overwrite);
    }

    public CompletableFuture<Void> export(
            ExporterCatalog registry,
            String format,
            ExportSelection selection,
            MeshOptions options,
            Map<String, String> values,
            Path destination,
            boolean overwrite) {
        if (!canExport())
            throw new IllegalStateException("Export requires the current complete, successfully rebuilt document");
        return exportDocument(
                registry.require(format).provider(),
                new dev.voxelbrew.exporter.api.ExportDialogs()
                        .register(
                                MeshExportDialog.class,
                                request -> Optional.of(new MeshExportDialog.Settings(
                                        selection,
                                        options,
                                        values,
                                        new dev.voxelbrew.exporter.api.FileExportDialog.Destination(
                                                destination, overwrite)))));
    }

    private void finishIo(CompletableFuture<Void> future, Exception failure) {
        if (failure != null) log.error("Document file operation failed", failure);
        dispatch.accept(() -> {
            if (closed) {
                future.cancel(false);
                return;
            }
            ioBusy = false;
            if (failure != null) error = failure.getMessage();
            onChanged.run();
            if (failure == null) future.complete(null);
            else future.completeExceptionally(failure);
        });
    }

    private void requireIdleIo() {
        if (closed || ioBusy) throw new IllegalStateException("Wait for the current file operation");
        error = "";
    }

    private void schedule(CadDocument document, boolean isPreview) {
        if (closed) return;
        long ticket = ++sequence;
        log.debug("Queued rebuild {} for document {} (preview={})", ticket, document.id(), isPreview);
        rebuilding = true;
        error = "";
        synchronized (queueLock) {
            pending = new Request(ticket, documentGeneration, session.revision(), document, isPreview);
            if (!running) {
                running = true;
                worker.execute(this::drain);
            }
        }
        onChanged.run();
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
                log.error(
                        "Rebuild {} failed for document {}",
                        request.sequence(),
                        request.document().id(),
                        failure);
                result = new Build(List.of(), List.of(), false);
                message = failure.getMessage();
            }
            Build complete = result;
            String failureMessage = message;
            dispatch.accept(() -> {
                var current = session.snapshot();
                if (closed
                        || request.sequence() != sequence
                        || request.generation() != documentGeneration
                        || request.revision() != current.revision()
                        || !request.document().id().equals(current.document().id())) {
                    log.debug("Discarding obsolete rebuild {}", request.sequence());
                    return;
                }
                if (!complete.valid() && !request.preview())
                    log.warn(
                            "Document {} revision {} has rebuild diagnostics; geometry is unavailable",
                            request.document().id(),
                            request.revision());
                log.debug(
                        "Published rebuild {} with {} bodies",
                        request.sequence(),
                        complete.bodies().size());
                build = complete;
                rebuilding = false;
                displayedPreview = request.preview();
                error = failureMessage;
                onChanged.run();
            });
        }
    }

    static Build evaluate(CadDocument document) {
        return evaluate(document, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    static Build evaluate(CadDocument document, Path pluginDirectory) {
        try (var evaluation = Evaluator.evaluate(document, pluginDirectory)) {
            if (!evaluation.valid())
                return new Build(List.of(), evaluation.diagnostics(), false, evaluation.resolvedFeatures());
            var bodies = dev.voxelbrew.util.concurrent.CpuWork.map(
                    evaluation.bodies(),
                    b -> new Body(
                            b.id(),
                            b.output(),
                            b.solid().tessellate(MeshOptions.DEFAULT),
                            b.solid().volume(),
                            b.solid().planarFaces(),
                            b.solid().faceEdges(),
                            b.solid().edgeSamples()));
            var inputs = new ArrayList<ConstructionFeature.Input>();
            var zero = new dev.voxelbrew.engine.construction.ConstructionGeometry.Vector(0, 0, 0);
            for (var body : evaluation.bodies()) {
                for (int kind = 0; kind < 3; kind++) {
                    int count = (int) body.solid().constructionInput(kind, -1, .5)[0];
                    var selection = switch (kind) {
                        case 0 -> ConstructionFeature.Selection.FACE;
                        case 1 -> ConstructionFeature.Selection.EDGE;
                        default -> ConstructionFeature.Selection.VERTEX;
                    };
                    for (int index = 0; index < count; index++)
                        inputs.add(new ConstructionFeature.Input(
                                selection,
                                body.output().featureId(),
                                body.output().outputName(),
                                index,
                                zero));
                }
            }
            return new Build(
                    bodies,
                    evaluation.diagnostics(),
                    true,
                    evaluation.resolvedFeatures(),
                    evaluation.construction(),
                    inputs);
        }
    }

    public CompletableFuture<dev.voxelbrew.engine.construction.ConstructionGeometry.Datum> previewConstruction(
            ConstructionFeature feature) {
        var captured = session.document();
        return CompletableFuture.supplyAsync(
                () -> {
                    var temporary = new ModelSession(captured);
                    if (captured.features().stream().anyMatch(f -> f.id().equals(feature.id())))
                        temporary.replace(feature);
                    else temporary.add(feature);
                    try (var evaluation = Evaluator.evaluate(temporary.document(), pluginDirectory)) {
                        if (!evaluation.valid())
                            throw new IllegalArgumentException(evaluation.diagnostics().stream()
                                    .filter(d -> d.state() == Evaluation.State.FAILED
                                            || d.state() == Evaluation.State.BLOCKED)
                                    .map(Evaluation.Diagnostic::message)
                                    .collect(java.util.stream.Collectors.joining("; ")));
                        return evaluation.construction().get(feature.id());
                    }
                },
                worker);
    }

    @Override
    public void close() {
        log.debug("Closing document controller for {}", session.document().id());
        closed = true;
        ++sequence;
        synchronized (queueLock) {
            pending = null;
        }
        worker.shutdown();
        io.shutdown();
    }
}
