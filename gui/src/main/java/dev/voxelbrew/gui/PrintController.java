package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.api.Slicing;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import lombok.experimental.Accessors;

/** Owner-thread state with cancellable worker execution and revision-guarded publication. */
@javax.inject.Singleton
@lombok.extern.slf4j.Slf4j
public final class PrintController implements AutoCloseable {
    public enum State {
        IDLE,
        SLICING,
        SUCCEEDED,
        CANCELLED,
        FAILED,
        OUTDATED
    }

    private final ExecutorService worker;
    private final Consumer<Runnable> dispatch;
    private final Supplier<CadDocument> current;
    private final Path pluginDirectory;
    private final Slicing slicing;
    private final dev.voxelbrew.importer.api.GcodeSource gcodeReader;
    private Map<String, List<String>> completedGcode = Map.of();
    private List<String> restoredLines = List.of();

    public Map<String, List<String>> completedGcode() {
        return completedGcode;
    }

    private final dev.voxelbrew.exporter.api.ExporterCatalog.Factory exporters;

    @Getter
    @Accessors(fluent = true)
    private State state = State.IDLE;

    private Cancellation cancellation;
    private String revision = "";

    @Getter
    @Accessors(fluent = true)
    private String message = "Ready to slice";

    private final List<String> diagnostics = new ArrayList<>();

    @Getter
    @Accessors(fluent = true)
    private Slicing.Result result;

    @Setter
    @Accessors(fluent = true, chain = false)
    private Runnable onChanged = () -> {};

    private long generation;
    private boolean closed;

    @Getter
    @Accessors(fluent = true)
    private boolean running;

    public PrintController(Consumer<Runnable> dispatch, Supplier<CadDocument> current) {
        this(dispatch, current, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    public PrintController(Consumer<Runnable> dispatch, Supplier<CadDocument> current, Path pluginDirectory) {
        this(
                dispatch,
                current,
                pluginDirectory,
                WindowResources.worker("slicer"),
                dev.voxelbrew.exporter.api.ExportRuntime.load().exporters(),
                dev.voxelbrew.slicer.api.SlicerRuntime.load().slicing());
    }

    PrintController(
            @NonNull @javax.inject.Named("ui") Consumer<Runnable> dispatch,
            @NonNull Supplier<CadDocument> current,
            @NonNull @javax.inject.Named("plugins") Path pluginDirectory,
            @NonNull @javax.inject.Named("slicing") ExecutorService worker,
            dev.voxelbrew.exporter.api.ExporterCatalog.Factory exporters,
            Slicing slicing) {
        this(
                dispatch,
                current,
                pluginDirectory,
                worker,
                exporters,
                slicing,
                dev.voxelbrew.importer.api.ImportRuntime.load().gcode());
    }

    @javax.inject.Inject
    PrintController(
            @javax.inject.Named("ui") Consumer<Runnable> dispatch,
            Supplier<CadDocument> current,
            @javax.inject.Named("plugins") Path pluginDirectory,
            @javax.inject.Named("slicing") ExecutorService worker,
            dev.voxelbrew.exporter.api.ExporterCatalog.Factory exporters,
            Slicing slicing,
            dev.voxelbrew.importer.api.GcodeSource gcodeReader) {
        this.dispatch = dispatch;
        this.current = current;
        this.pluginDirectory = pluginDirectory;
        this.worker = worker;
        this.exporters = exporters;
        this.slicing = slicing;
        this.gcodeReader = gcodeReader;
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
    }

    public void clear() {
        if (running) throw new IllegalStateException("Wait for slicing to finish before clearing the job");
        generation++;
        result = null;
        completedGcode = Map.of();
        restoredLines = List.of();
        revision = "";
        diagnostics.clear();
        state = State.IDLE;
        message = "Ready to slice";
        onChanged.run();
    }

    public void documentChanged(CadDocument document) {
        if (revision.isEmpty() || revision.equals(DocumentCodec.fingerprint(document))) return;
        outdated("Model or print settings changed. Slice again to update the job.");
    }

    /** A provider reload can change evaluated geometry without changing persistent bytes. */
    public void invalidateGeometry() {
        if (revision.isEmpty() || state == State.OUTDATED) return;
        outdated("Geometry is being rebuilt. Slice again to update the job.");
    }

    private void outdated(String reason) {
        if (cancellation != null) cancellation.cancel();
        result = null;
        completedGcode = Map.of();
        restoredLines = List.of();
        state = State.OUTDATED;
        message = reason;
        onChanged.run();
    }

    public CompletableFuture<Slicing.Result> start(
            CadDocument document, SlicerBackend backend, Path executable, Path destination, boolean overwrite) {
        return startJobs(document, backend, executable, List.of(new PlateJob(document, destination)), overwrite);
    }

    private record PlateJob(CadDocument document, Path destination) {}

    /** Scratch output is private to slicing; only File > Export publishes a user job. */
    public CompletableFuture<Slicing.Result> startEmbedded(
            CadDocument document, SlicerBackend backend, boolean allPlates) throws IOException {
        Path directory = java.nio.file.Files.createTempDirectory("voxelbrew-job-");
        var destinations = new LinkedHashMap<String, Path>();
        for (var plate : document.printSetup().plates())
            destinations.put(plate.id(), directory.resolve(plate.id() + ".gcode"));
        try {
            var future = allPlates
                    ? startAll(document, backend, null, destinations, false)
                    : start(
                            document,
                            backend,
                            null,
                            destinations.get(document.printSetup().activePlateId()),
                            false);
            future.whenComplete((value, failure) -> cleanup(directory, destinations.values()));
            return future;
        } catch (RuntimeException failure) {
            cleanup(directory, destinations.values());
            throw failure;
        }
    }

    private static void cleanup(Path directory, Collection<Path> paths) {
        try {
            for (Path path : paths) java.nio.file.Files.deleteIfExists(path);
            java.nio.file.Files.deleteIfExists(directory);
        } catch (IOException failure) {
            log.warn("Cannot remove temporary slicer output {}", directory, failure);
        }
    }

    public void restoreEmbedded(CadDocument document) {
        var lines = document.activeGcode();
        if (running || lines.isEmpty() || lines.equals(restoredLines)) return;
        restoredLines = lines;
        String captured = DocumentCodec.fingerprint(document);
        worker.execute(() -> {
            try {
                var job = gcodeReader.readLines(lines);
                dispatch.accept(() -> {
                    if (closed
                            || running
                            || !captured.equals(DocumentCodec.fingerprint(current.get()))
                            || !lines.equals(current.get().activeGcode())) return;
                    revision = captured;
                    result = new Slicing.Result(null, job, "");
                    state = State.SUCCEEDED;
                    message = "Stored G-code ready to export";
                    onChanged.run();
                });
            } catch (IOException failure) {
                dispatch.accept(() -> {
                    if (closed || !lines.equals(current.get().activeGcode())) return;
                    message = "Cannot preview stored G-code: " + failure.getMessage();
                    onChanged.run();
                });
            }
        });
    }

    /** Slice detached plate snapshots without changing the active plate or document history. */
    public CompletableFuture<Slicing.Result> startAll(
            CadDocument document,
            SlicerBackend backend,
            Path executable,
            Map<String, Path> destinations,
            boolean overwrite) {
        var requests = new ArrayList<PlateJob>();
        var plates = document.printSetup().plates();
        for (int i = 0; i < plates.size(); i++) {
            var setup = document.printSetup().selectPlate(plates.get(i).id());
            if (setup.instances().isEmpty()) continue;
            var selected = new CadDocument(
                    document.id(),
                    document.features(),
                    document.cursor(),
                    setup,
                    document.parameters(),
                    document.bodyStyles(),
                    document.bodyNames(),
                    document.featureNames());
            requests.add(new PlateJob(
                    selected,
                    Objects.requireNonNull(destinations.get(plates.get(i).id()), "Missing plate destination")));
        }
        if (requests.isEmpty()) throw new IllegalArgumentException("Add objects to at least one plate");
        var unique = new HashSet<Path>();
        for (var request : requests)
            if (!unique.add(request.destination().toAbsolutePath().normalize()))
                throw new IllegalArgumentException("Each plate requires a separate output file");
        return startJobs(document, backend, executable, List.copyOf(requests), overwrite);
    }

    private CompletableFuture<Slicing.Result> startJobs(
            CadDocument document, SlicerBackend backend, Path executable, List<PlateJob> requests, boolean overwrite) {
        if (closed || running) throw new IllegalStateException("Wait for the current slicer task to finish");
        running = true;
        state = State.SLICING;
        result = null;
        completedGcode = Map.of();
        diagnostics.clear();
        revision = DocumentCodec.fingerprint(document);
        message = "Validating print setup";
        var token = new Cancellation();
        cancellation = token;
        long ticket = ++generation;
        var future = new CompletableFuture<Slicing.Result>();
        onChanged.run();
        worker.execute(() -> {
            try (var exportRegistry = exporters.open(pluginDirectory)) {
                var models = new ArrayList<ManufacturingModel>();
                // Preflight every plate before publishing any output.
                for (var request : requests) {
                    token.check();
                    if (!overwrite && java.nio.file.Files.exists(request.destination()))
                        throw new IOException("Output already exists: " + request.destination());
                    var model = ManufacturingModels.evaluate(request.document(), MeshOptions.DEFAULT, pluginDirectory);
                    PrintValidation.validate(model).requireValid();
                    models.add(model);
                }
                Slicing.Result selectedResult = null;
                Slicing.Result lastResult = null;
                var jobs = new LinkedHashMap<String, List<String>>();
                for (int i = 0; i < requests.size(); i++) {
                    token.check();
                    var request = requests.get(i);
                    String label = "Plate " + (i + 1) + "/" + requests.size() + " ("
                            + request.document().printSetup().activePlate().name() + "): ";
                    var plateToken = new Cancellation();
                    var unlink = token.onCancel(plateToken::cancel);
                    Slicing.Result completed;
                    try {
                        completed = slicing.slice(
                                backend,
                                request.document(),
                                models.get(i),
                                exportRegistry,
                                executable,
                                request.destination(),
                                overwrite,
                                plateToken,
                                line -> dispatch.accept(() -> {
                                    if (closed || ticket != generation || state != State.SLICING) return;
                                    if (diagnostics.size() == 100) diagnostics.removeFirst();
                                    diagnostics.add(label + line);
                                    message = label + line;
                                    onChanged.run();
                                }),
                                (captured, commit) -> {
                                    if (!captured.equals(DocumentCodec.fingerprint(request.document())))
                                        throw new Slicing.OutdatedException();
                                    publish(ticket, DocumentCodec.fingerprint(document), token, commit);
                                });
                    } finally {
                        unlink.run();
                    }
                    lastResult = completed;
                    jobs.put(
                            request.document().printSetup().activePlateId(),
                            completed.job().lines());
                    if (request.document()
                            .printSetup()
                            .activePlateId()
                            .equals(document.printSetup().activePlateId())) selectedResult = completed;
                }
                token.check();
                var completed = lastResult;
                var preview = selectedResult;
                dispatch.accept(() -> {
                    if (closed || ticket != generation) {
                        future.cancel(false);
                        return;
                    }
                    running = false;
                    if (state == State.OUTDATED || !revision.equals(DocumentCodec.fingerprint(current.get()))) {
                        state = State.OUTDATED;
                        message = "Job is outdated; slice the current revision again.";
                        result = null;
                        future.completeExceptionally(new Slicing.OutdatedException());
                    } else if (token.cancelled()) {
                        state = State.CANCELLED;
                        result = null;
                        message = "Slicing cancelled; completed files were kept.";
                        future.completeExceptionally(new CancellationException(message));
                    } else {
                        state = State.SUCCEEDED;
                        completedGcode = Map.copyOf(jobs);
                        result = preview;
                        message = "Sliced " + requests.size() + " plate(s). G-code ready to export.";
                        future.complete(completed);
                    }
                    onChanged.run();
                });
            } catch (Exception | LinkageError failure) {
                if (failure instanceof CancellationException || failure instanceof Slicing.OutdatedException)
                    log.debug("Print job cancelled or outdated", failure);
                else log.error("Print job failed", failure);
                dispatch.accept(() -> {
                    if (closed || ticket != generation) {
                        future.cancel(false);
                        return;
                    }
                    running = false;
                    result = null;
                    if (state != State.OUTDATED) {
                        state = failure instanceof CancellationException
                                ? State.CANCELLED
                                : failure instanceof Slicing.OutdatedException ? State.OUTDATED : State.FAILED;
                        message = Objects.toString(failure.getMessage(), failure.toString());
                    }
                    onChanged.run();
                    future.completeExceptionally(failure);
                });
            }
        });
        return future;
    }

    private void publish(long ticket, String captured, Cancellation token, Slicing.Commit commit) throws IOException {
        var published = new CompletableFuture<Void>();
        dispatch.accept(() -> {
            try {
                if (closed || ticket != generation || !captured.equals(DocumentCodec.fingerprint(current.get())))
                    throw new Slicing.OutdatedException();
                token.check();
                commit.run();
                published.complete(null);
            } catch (Exception failure) {
                published.completeExceptionally(failure);
            }
        });
        try {
            published.get(30, TimeUnit.SECONDS);
        } catch (ExecutionException failure) {
            if (failure.getCause() instanceof IOException io) throw io;
            if (failure.getCause() instanceof CancellationException cancelled) throw cancelled;
            throw new IOException("Job publication failed", failure.getCause());
        } catch (InterruptedException failure) {
            token.cancel();
            Thread.currentThread().interrupt();
            throw new IOException("Job publication interrupted", failure);
        } catch (TimeoutException failure) {
            token.cancel();
            throw new IOException("Job publication timed out", failure);
        }
    }

    public void cancel() {
        if (cancellation != null && running && cancellation.cancel()) {
            log.info("Print cancellation requested");
            state = State.CANCELLED;
            result = null;
            message = "Cancelling slicer";
            onChanged.run();
        }
    }

    @Override
    public void close() {
        closed = true;
        generation++;
        if (cancellation != null) cancellation.cancel();
        worker.shutdown();
    }
}
