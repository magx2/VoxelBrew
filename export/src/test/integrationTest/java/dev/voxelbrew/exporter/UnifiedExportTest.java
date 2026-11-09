package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnifiedExportTest {
    @TempDir
    Path directory;

    @Test
    void builtInGeometryUsesTheSameProviderContractAndRejectsRollback() throws Exception {
        var document = ExportTest.reference();
        try (var registry = new ExporterRegistry(directory)) {
            for (String id : List.of("stl", "3mf", "obj", "step")) {
                Path target = directory.resolve("unified." + id);
                var parent = new ExportDialogs()
                        .register(
                                FileExportDialog.class,
                                request -> Optional.of(new FileExportDialog.Destination(target, true)))
                        .register(
                                MeshExportDialog.class,
                                request -> Optional.of(new MeshExportDialog.Settings(
                                        ExportSelection.design(null),
                                        MeshOptions.DEFAULT,
                                        Map.of(),
                                        new FileExportDialog.Destination(target, true))));
                var exporter = registry.require(id).provider().create(Runnable::run, directory);
                assertEquals(target, exporter.export(document, parent).get());
                byte[] original = Files.readAllBytes(target);
                assertTrue(original.length > 100);
                var rollback = new CadDocument(document.id(), document.features(), document.cursor() - 1);
                assertThrows(
                        ExecutionException.class,
                        () -> exporter.export(rollback, parent).get());
                assertArrayEquals(original, Files.readAllBytes(target));
            }
        }
    }

    @Test
    void cancelledMeshDialogDoesNotScheduleOrEvaluateAnything() throws Exception {
        try (var registry = new ExporterRegistry(directory)) {
            for (String id : List.of("stl", "3mf", "obj", "prusa-project")) {
                var exporter = registry.require(id)
                        .provider()
                        .create(task -> fail("Cancelled export scheduled work"), directory);
                var parent = new ExportDialogs().register(MeshExportDialog.class, request -> Optional.empty());
                assertThrows(
                        CancellationException.class,
                        () -> exporter.export(CadDocument.empty(), parent).join());
            }
        }
    }
}
