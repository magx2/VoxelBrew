package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentControllerTest {
    @TempDir Path directory;
    private final ConcurrentLinkedQueue<Runnable> callbacks = new ConcurrentLinkedQueue<>();

    @Test
    void bodyStylesDirtySaveUndoAndRejectStaleOrInactiveSelections() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            controller.add(sketch("plate", 0));
            controller.add(
                    new Feature.Extrude(
                            "solid", new Feature.ProfileRef("plate", "plate.profile"), 5));
            await(() -> !controller.rebuilding());
            Path file = directory.resolve("body style.jcad");
            controller.save(file, false);
            await(() -> !controller.ioBusy());
            assertFalse(controller.dirty());
            var red = new BodyStyle("PETG", "#FF0000");
            controller.bodyStyle("solid.body", red);
            assertTrue(controller.dirty());
            assertThrows(
                    IllegalStateException.class, () -> controller.bodyStyle("solid.body", red));
            await(() -> !controller.rebuilding());
            assertEquals(12000, controller.build().bodies().getFirst().volume(), .01);
            assertThrows(
                    IllegalStateException.class, () -> controller.bodyStyle("missing.body", red));
            controller.undo();
            await(() -> !controller.rebuilding());
            assertFalse(controller.dirty());
            controller.redo();
            await(() -> !controller.rebuilding());
            assertEquals(red, controller.snapshot().document().bodyStyle("solid.body"));
            controller.save(file, true);
            await(() -> !controller.ioBusy());
            assertEquals(red, DocumentReader.load(file).bodyStyle("solid.body"));
            controller.cursor(1);
            await(() -> !controller.rebuilding());
            assertThrows(
                    IllegalStateException.class, () -> controller.bodyStyle("solid.body", red));
        }
    }

    @Test
    void importedDocumentsAreUnsavedAndFailuresPreserveTheCurrentDocument() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("import providers"));
        Files.copy(
                Path.of(System.getProperty("voxelbrew.test.plugin")),
                plugins.resolve("provider.jar"));
        Path source = directory.resolve("plate.vbplate");
        Files.writeString(source, "60,40,4");
        try (var controller = new DocumentController(callbacks::add, plugins)) {
            var imported =
                    controller.importFile(
                            dev.voxelbrew.importer.ImporterRegistry::new,
                            "plate",
                            source,
                            java.util.Map.of("scale", "2"));
            assertTrue(controller.ioBusy());
            await(() -> imported.isDone() && !controller.rebuilding());
            imported.join();
            assertTrue(controller.dirty());
            assertNull(controller.path());
            assertEquals(76800, controller.build().bodies().getFirst().volume(), .01);
            Path saved = directory.resolve("saved.jcad");
            controller.save(saved, false);
            await(() -> !controller.ioBusy());
            assertFalse(controller.dirty());
            controller.edit("thickness", "distance", 9);
            await(() -> !controller.rebuilding());
            var before = controller.snapshot();
            var failed =
                    controller.importFile(
                            dev.voxelbrew.importer.ImporterRegistry::new,
                            "failing",
                            source,
                            java.util.Map.of());
            await(failed::isDone);
            assertTrue(failed.isCompletedExceptionally());
            assertEquals(before, controller.snapshot());
            assertEquals(saved.toAbsolutePath(), controller.path());
            assertTrue(controller.dirty());
            assertTrue(controller.error().contains("Deliberate importer failure"));
            assertEquals("60,40,4", Files.readString(source));
        }
    }

    private static Feature.Sketch sketch(String id, double z) {
        return new Feature.Sketch(
                id,
                id + ".profile",
                new Feature.Plane(z == 0 ? "origin-xy" : "offset", z),
                new Feature.Rectangle(0, 0, 60, 40));
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            for (Runnable callback; (callback = callbacks.poll()) != null; ) callback.run();
            if (condition.getAsBoolean()) return;
            Thread.sleep(10);
        }
        fail("Timed out waiting for controller");
    }

    @Test
    void configurationsInvalidateGeometryAndUndoPreservesFormulaExtrusions() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var sketch = sketch("plate", 0);
            controller.add(sketch);
            await(() -> !controller.rebuilding());
            var p =
                    dev.voxelbrew.engine.parameters.ParameterSet.empty()
                            .put(
                                    "thickness",
                                    dev.voxelbrew.engine.parameters.ParameterUnit.MM,
                                    "5",
                                    "")
                            .addConfiguration("Thick", false)
                            .override("thickness", "Thick", "8")
                            .addConfiguration("Invalid", false)
                            .override("thickness", "Invalid", "0");
            controller.parameters(p);
            await(() -> !controller.rebuilding());
            controller.previewExtrusion(sketch, "thickness");
            await(() -> !controller.rebuilding());
            controller.cancelPreview();
            await(() -> !controller.rebuilding());
            assertTrue(controller.snapshot().document().parameters().bindings().isEmpty());
            controller.previewExtrusion(sketch, "thickness");
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertEquals(12000, controller.build().bodies().getFirst().volume(), .01);
            controller.undo();
            await(() -> !controller.rebuilding());
            assertEquals(1, controller.snapshot().document().features().size());
            controller.redo();
            await(() -> !controller.rebuilding());
            assertEquals(1, controller.snapshot().document().parameters().bindings().size());
            controller.configuration("Thick");
            assertFalse(controller.canExport());
            await(() -> !controller.rebuilding());
            assertEquals(19200, controller.build().bodies().getFirst().volume(), .01);
            assertEquals(
                    8, ((Feature.Extrude) controller.effectiveFeatures().getLast()).distance());
            controller.configuration("Invalid");
            await(() -> !controller.rebuilding());
            assertFalse(controller.canExport());
            assertTrue(controller.build().bodies().isEmpty());
            controller.undo();
            await(() -> !controller.rebuilding());
            assertTrue(controller.canExport());
        }
    }

    @Test
    void customFeaturesRebuildOffThreadAndMissingPluginsNeverExposeStaleGeometry()
            throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("custom plugins"));
        Path jar = plugins.resolve("provider.jar");
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
        Feature.Custom custom;
        try (var registry = new dev.voxelbrew.engine.feature.FeatureRegistry(plugins)) {
            custom = registry.create("spacer", "example.spacer", "spacer", java.util.Map.of());
        }
        try (var controller = new DocumentController(callbacks::add, plugins)) {
            controller.add(custom);
            await(() -> !controller.rebuilding());
            assertTrue(controller.canExport());
            assertEquals(Math.PI * 96 * 8, controller.build().bodies().getFirst().volume(), 0.01);
            controller.edit("spacer", "height", 12);
            await(() -> !controller.rebuilding());
            assertEquals(Math.PI * 96 * 12, controller.build().bodies().getFirst().volume(), 0.01);
            var before = controller.snapshot();
            Files.delete(jar);
            controller.rebuild();
            assertFalse(controller.canExport());
            await(() -> !controller.rebuilding());
            assertFalse(controller.build().valid());
            assertTrue(controller.build().bodies().isEmpty());
            assertEquals(before, controller.snapshot());
            Path file = directory.resolve("missing plugin.jcad");
            controller.save(file, false);
            await(() -> !controller.ioBusy());
            assertEquals(before.document(), DocumentReader.load(file));
            Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
            controller.rebuild();
            await(() -> !controller.rebuilding());
            assertTrue(controller.canExport());
            assertEquals(before, controller.snapshot());
            controller.undo();
            await(() -> !controller.rebuilding());
            assertEquals(Math.PI * 96 * 8, controller.build().bodies().getFirst().volume(), 0.01);
        }
    }

    @Test
    void previewsCancelWithoutTransactionsAndCommitAsOneUndoStep() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var sketch = sketch("plate", 12);
            controller.add(sketch);
            await(() -> !controller.rebuilding());
            var saved = controller.snapshot();
            controller.previewExtrusion(sketch, -3);
            await(() -> !controller.rebuilding());
            assertEquals(1, controller.snapshot().document().features().size());
            assertFalse(controller.canExport());
            assertEquals(7200, controller.build().bodies().getFirst().volume(), 0.01);
            controller.cancelPreview();
            await(() -> !controller.rebuilding());
            assertEquals(saved, controller.snapshot());
            assertTrue(controller.build().bodies().isEmpty());
            controller.previewExtrusion(sketch, 5);
            controller.previewExtrusion(sketch, 8);
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertEquals(2, controller.snapshot().document().features().size());
            assertTrue(controller.snapshot().canUndo());
            assertTrue(controller.canExport());
            controller.undo();
            await(() -> !controller.rebuilding());
            assertEquals(1, controller.snapshot().document().features().size());
            assertTrue(controller.snapshot().canRedo());
            controller.redo();
            await(() -> !controller.rebuilding());
            assertEquals(19200, controller.build().bodies().getFirst().volume(), 0.01);
            long revision = controller.snapshot().revision();
            controller.cursor(1);
            await(() -> !controller.rebuilding());
            assertTrue(controller.snapshot().revision() > revision);
            assertFalse(controller.canExport());
            controller.cursor(2);
            controller.undo();
            await(() -> !controller.rebuilding());
            assertEquals(1, controller.snapshot().document().features().size());
        }
    }

    @Test
    void staleRebuildsCannotReplaceANewDocumentAndQueueCoalesces() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        try (var controller =
                new DocumentController(
                        callbacks::add,
                        document -> {
                            if (calls.incrementAndGet() == 1) {
                                entered.countDown();
                                try {
                                    if (!release.await(10, TimeUnit.SECONDS))
                                        throw new IllegalStateException("Test latch timeout");
                                } catch (InterruptedException e) {
                                    throw new IllegalStateException(e);
                                }
                            }
                            return DocumentController.evaluate(document);
                        })) {
            controller.add(sketch("old", 0));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            String oldId = controller.snapshot().document().id();
            controller.newDocument();
            controller.add(sketch("new", 0));
            controller.add(
                    new Feature.Extrude(
                            "new-solid", new Feature.ProfileRef("new", "new.profile"), 8));
            release.countDown();
            await(() -> !controller.rebuilding());
            assertNotEquals(oldId, controller.snapshot().document().id());
            assertEquals(2, calls.get());
            assertEquals("new-solid.body", controller.build().bodies().getFirst().id());
            assertTrue(
                    controller.build().diagnostics().stream()
                            .noneMatch(d -> d.featureId().equals("old")));
        } finally {
            release.countDown();
        }
    }

    @Test
    void savesReopenWithStableIdsAndFailedOpenPreservesCurrentDocument() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            var s = sketch("plate", 0);
            controller.add(s);
            controller.previewExtrusion(s, 8);
            controller.commitPreview();
            await(() -> !controller.rebuilding());
            assertTrue(controller.dirty());
            Path file = directory.resolve("saved design.jcad");
            var saving = controller.save(file, false);
            await(saving::isDone);
            assertFalse(controller.dirty());
            var original = controller.snapshot().document();
            controller.edit(original.features().getLast().id(), "distance", 5);
            await(() -> !controller.rebuilding());
            assertTrue(controller.dirty());
            var opening = controller.open(file);
            await(() -> opening.isDone() && !controller.rebuilding());
            assertEquals(original, controller.snapshot().document());
            assertFalse(controller.dirty());
            Files.write(directory.resolve("bad.jcad"), new byte[] {1, 2, 3});
            var failed = controller.open(directory.resolve("bad.jcad"));
            await(failed::isDone);
            assertTrue(failed.isCompletedExceptionally());
            assertEquals(original, controller.snapshot().document());
            assertEquals(original, DocumentReader.load(file));
        }
    }

    @Test
    void brokenDefinitionsClearGeometryAndRemainRepairable() throws Exception {
        var s = sketch("plate", 0);
        var invalid =
                new Feature.Extrude(
                        "solid", new Feature.ProfileRef("missing", "missing.profile"), 8);
        Path file = directory.resolve("broken.jcad");
        DocumentWriter.save(new CadDocument("broken", List.of(s, invalid), 2), file, false);
        try (var controller = new DocumentController(callbacks::add)) {
            var opening = controller.open(file);
            await(() -> opening.isDone() && !controller.rebuilding());
            assertFalse(controller.build().valid());
            assertTrue(controller.build().bodies().isEmpty());
            assertFalse(controller.canExport());
            controller.replaceFeature(
                    new Feature.Extrude("solid", new Feature.ProfileRef(s.id(), s.profileId()), 8));
            await(() -> !controller.rebuilding());
            assertTrue(controller.canExport());
            assertEquals(19200, controller.build().bodies().getFirst().volume(), 0.01);
        }
    }

    @Test
    void editsDuringSaveRemainDirtyAndDoNotChangeTheCapturedFile() throws Exception {
        try (var controller = new DocumentController(callbacks::add)) {
            controller.add(sketch("plate", 0));
            await(() -> !controller.rebuilding());
            var captured = controller.snapshot().document();
            var file = directory.resolve("save snapshot.jcad");
            var saving = controller.save(file, false);
            controller.edit("plate", "width", 61);
            await(() -> saving.isDone() && !controller.rebuilding());
            assertEquals(captured, DocumentReader.load(file));
            assertTrue(controller.dirty());
            assertEquals(
                    61,
                    ((Feature.Rectangle)
                                    ((Feature.Sketch)
                                                    controller
                                                            .snapshot()
                                                            .document()
                                                            .features()
                                                            .getFirst())
                                            .profile())
                            .width());
        }
    }
}
