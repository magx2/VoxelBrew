package dev.voxelbrew.gui;

import dev.voxelbrew.engine.printing.Cancellation;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.util.i18n.Translations;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableMap;

/** Window-owned runtime checks shared by settings and print actions. */
@javax.inject.Singleton
final class SlicerAvailability {
    record Status(boolean working, String detail) {}

    private final SlicerCatalog catalog;
    private final SlicerSettings settings;
    private final ExecutorService worker;
    private final Translations translations;
    private long generation;
    final ObservableMap<String, Status> statuses = FXCollections.observableHashMap();

    @javax.inject.Inject
    SlicerAvailability(
            SlicerCatalog catalog,
            SlicerSettings settings,
            @javax.inject.Named("appearance") ExecutorService worker,
            Translations translations) {
        this.catalog = catalog;
        this.settings = settings;
        this.worker = worker;
        this.translations = translations;
    }

    boolean working(String id) {
        var status = statuses.get(id);
        return status != null && status.working();
    }

    String text(String id) {
        var status = statuses.get(id);
        return status == null
                ? translations.text("slicer.status.checking")
                : translations.text(
                        status.working() ? "slicer.status.configured" : "slicer.status.failed", status.detail());
    }

    void refresh() {
        long ticket = ++generation;
        statuses.clear();
        for (var descriptor : catalog.descriptors()) {
            worker.execute(() -> {
                Status result;
                try {
                    result = check(settings.backend(descriptor.id()));
                } catch (Exception | LinkageError failure) {
                    result = new Status(false, java.util.Objects.toString(failure.getMessage(), failure.toString()));
                }
                var completed = result;
                Platform.runLater(() -> {
                    if (ticket == generation) statuses.put(descriptor.id(), completed);
                });
            });
        }
    }

    static Status check(SlicerBackend backend) throws java.io.IOException {
        var cancellation = new Cancellation();
        var timeout = new CompletableFuture<Void>();
        timeout.orTimeout(10, TimeUnit.SECONDS).exceptionally(failure -> {
            cancellation.cancel();
            return null;
        });
        try {
            var probe = backend.probe(backend.executable(), cancellation);
            cancellation.check();
            if (probe.actualVersion() == null || probe.actualVersion().isBlank())
                throw new java.io.IOException("Slicer did not report a version");
            return new Status(true, probe.actualVersion());
        } finally {
            timeout.complete(null);
        }
    }
}
