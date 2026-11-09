package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.exporter.api.*;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Implementation helper for built-in mesh formats; all entry points use Exporter. */
public abstract class MeshExporter implements Exporter<Path> {
    private final Executor worker;
    private final Path plugins;

    protected MeshExporter() {
        this(Runnable::run, ExporterRegistry.defaultDirectory());
    }

    protected MeshExporter(Executor worker, Path plugins) {
        this.worker = Objects.requireNonNull(worker);
        this.plugins = Objects.requireNonNull(plugins);
    }

    public abstract MeshFormat format();

    public abstract void write(ModelView view, MeshOptions mesh, Map<String, String> options, OutputStream output)
            throws IOException;

    @Override
    public final CompletableFuture<Path> export(CadDocument document, ExportWindow parent) {
        var dialog = new MeshExportDialog(format());
        if (parent.show(dialog).isEmpty()) return CompletableFuture.failedFuture(new CancellationException());
        var settings = dialog.getSettings();
        return CompletableFuture.supplyAsync(
                () -> {
                    try {
                        if (settings.snapshot() != null) {
                            var model = settings.snapshot();
                            if (!document.id().equals(model.documentId())
                                    || !DocumentCodec.fingerprint(document).equals(model.revision()))
                                throw new IllegalArgumentException(
                                        "Manufacturing snapshot does not match the document");
                            if (settings.selection().space() != ExportSelection.Space.BUILD_PLATE)
                                throw new IllegalArgumentException("Manufacturing snapshots require BUILD_PLATE space");
                            var view =
                                    ModelViews.from(model, settings.selection().contents());
                            if (settings.selection().body() != null)
                                view = ModelViews.select(
                                        view, settings.selection().body());
                            if (settings.selection().instance() != null)
                                view = ModelViews.selectInstance(
                                        view, settings.selection().instance());
                            writeFile(view, settings);
                        } else {
                            try (var evaluation = Evaluator.evaluate(document, plugins)) {
                                writeFile(
                                        ExportSelections.view(
                                                settings.selection(), document, evaluation, settings.mesh()),
                                        settings);
                            }
                        }
                        return settings.destination().path().toAbsolutePath().normalize();
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                },
                worker);
    }

    /** Serialization helper for format-level tests; application callers use export(). */
    public final void writeFile(ModelView view, MeshExportDialog.Settings settings) throws IOException {
        if (view.instances().isEmpty()) throw new IllegalArgumentException("Nothing to export");
        if (!format().multipleInstances() && view.instances().size() != 1)
            throw new IllegalArgumentException("Exporter requires one selected body/instance");
        var values = new LinkedHashMap<String, String>();
        for (var option : format().options()) values.put(option.key(), option.defaultValue());
        for (var option : settings.options().entrySet()) {
            var spec = format().options().stream()
                    .filter(o -> o.key().equals(option.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown option '" + option.getKey() + "'"));
            spec.validate(option.getValue());
            values.put(option.getKey(), option.getValue());
        }
        AtomicExport.write(settings.destination(), stream -> write(view, settings.mesh(), Map.copyOf(values), stream));
    }
}
