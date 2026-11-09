package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InjectedDocumentIoTest {
    @TempDir
    Path directory;

    private final Queue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

    @Test
    void exportersKeepCancellationAndJsonSeparateFromSavingTheEditableDocument() throws Exception {
        var controller =
                new DocumentController(callbacks::add, doc -> new DocumentController.Build(List.of(), List.of(), true));
        try {
            var cancelled = controller.saveAs(new dev.voxelbrew.exporter.api.ExportDialogs()
                    .register(dev.voxelbrew.exporter.api.FileExportDialog.class, request -> Optional.empty()));
            await(cancelled::isDone);
            assertTrue(cancelled.isCancelled());
            assertFalse(controller.ioBusy());
            assertNull(controller.path());
            var json = controller.exportDocument(
                    new dev.voxelbrew.exporter.JsonExporterProvider(),
                    dev.voxelbrew.exporter.api.FileExportDialog.destination(directory.resolve("copy.json"), false));
            await(json::isDone);
            json.join();
            assertNull(controller.path());
            assertTrue(Files.exists(directory.resolve("copy.json")));
            Path saved = directory.resolve("saved.jcad");
            var jcad = controller.saveAs(dev.voxelbrew.exporter.api.FileExportDialog.destination(saved, false));
            await(jcad::isDone);
            jcad.join();
            assertEquals(saved, controller.path());
            assertFalse(controller.ioBusy());
        } finally {
            controller.close();
        }
    }

    @Test
    void injectedFileFailuresPreserveTheDocumentAndShutdownDrainsCapturedWrites() throws Exception {
        var rebuild = WindowResources.worker("test-rebuild");
        var files = WindowResources.worker("test-files");
        var loaded = CadDocument.empty();
        var written = new AtomicReference<CadDocument>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var controller = new DocumentController(
                callbacks::add,
                doc -> new DocumentController.Build(List.of(), List.of(), true),
                directory,
                rebuild,
                files,
                path -> {
                    if (path.getFileName().toString().equals("bad.jcad"))
                        throw new IOException("Injected read failure");
                    return loaded;
                },
                (doc, path, overwrite) -> {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Test write timed out");
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IOException(failure);
                    }
                    written.set(doc);
                });
        try {
            var open = controller.open(directory.resolve("virtual.jcad"));
            await(() -> open.isDone() && !controller.rebuilding());
            open.join();
            assertEquals(loaded, controller.snapshot().document());
            var failed = controller.open(directory.resolve("bad.jcad"));
            await(failed::isDone);
            assertTrue(failed.isCompletedExceptionally());
            assertEquals(loaded, controller.snapshot().document());
            var save = controller.save(directory.resolve("saved.jcad"), false);
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            controller.close();
            release.countDown();
            assertTrue(files.awaitTermination(10, TimeUnit.SECONDS));
            await(save::isDone);
            assertEquals(loaded, written.get());
            assertTrue(save.isCancelled(), "Closed window must not adopt the captured write as current state");
            assertTrue(rebuild.isShutdown());
            assertFalse(Files.exists(directory.resolve("saved.jcad")));
        } finally {
            release.countDown();
            controller.close();
        }
    }

    private void await(BooleanSupplier done) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            for (Runnable callback; (callback = callbacks.poll()) != null; ) callback.run();
            if (done.getAsBoolean()) return;
            Thread.sleep(5);
        }
        fail("Timed out waiting for owner-thread callbacks");
    }
}
