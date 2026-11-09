package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.Slicing;
import dev.voxelbrew.exporter.printing.SlicerBackend;
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
                dev.voxelbrew.exporter.api.ExportRuntime.load().slicing());
    }

    @javax.inject.Inject
    PrintController(
            @NonNull @javax.inject.Named("ui") Consumer<Runnable> dispatch,
            @NonNull Supplier<CadDocument> current,
            @NonNull @javax.inject.Named("plugins") Path pluginDirectory,
            @NonNull @javax.inject.Named("slicing") ExecutorService worker,
            dev.voxelbrew.exporter.api.ExporterCatalog.Factory exporters,
            Slicing slicing) {
        this.dispatch = dispatch;
        this.current = current;
        this.pluginDirectory = pluginDirectory;
        this.worker = worker;
        this.exporters = exporters;
        this.slicing = slicing;
    }

    public List<String> diagnostics() {
        return List.copyOf(diagnostics);
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
        state = State.OUTDATED;
        message = reason;
        onChanged.run();
    }

    public CompletableFuture<Slicing.Result> start(
            CadDocument document, SlicerBackend backend, Path executable, Path destination, boolean overwrite) {
        if (closed || running) throw new IllegalStateException("Wait for the current slicer task to finish");
        running = true;
        state = State.SLICING;
        result = null;
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
                var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                        document, MeshOptions.DEFAULT, pluginDirectory);
                token.check();
                var completed = slicing.slice(
                        backend,
                        document,
                        model,
                        exportRegistry,
                        executable,
                        destination,
                        overwrite,
                        token,
                        line -> dispatch.accept(() -> {
                            if (closed || ticket != generation || state != State.SLICING) return;
                            if (diagnostics.size() == 100) diagnostics.removeFirst();
                            diagnostics.add(line);
                            message = line;
                            onChanged.run();
                        }),
                        (captured, commit) -> publish(ticket, captured, token, commit));
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
                    } else {
                        state = State.SUCCEEDED;
                        result = completed;
                        message = "Job ready: " + completed.output();
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
