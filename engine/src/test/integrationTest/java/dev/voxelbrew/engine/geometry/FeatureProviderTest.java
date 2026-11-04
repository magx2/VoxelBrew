package dev.voxelbrew.engine.geometry;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.feature.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FeatureProviderTest {
    @TempDir Path directory;

    private Path plugins() throws Exception {
        var plugins = Files.createDirectory(directory.resolve("feature plugins with spaces"));
        Files.copy(
                Path.of(System.getProperty("voxelbrew.test.plugin")),
                plugins.resolve("external features.jar"));
        return plugins;
    }

    private static double expected(double height) {
        return Math.PI * (100 - 4) * height;
    }

    @Test
    void spacerRoundTripsEditsAndConsumesNamedOutputsWithoutLeakingSolids() throws Exception {
        int handles = NativeKernel.liveHandles();
        Path plugins = plugins();
        var session = new ModelSession(CadDocument.empty());
        try (var registry = new FeatureRegistry(plugins)) {
            session.add(registry.create("spacer", "example.spacer", "spacer", Map.of()));
        }
        try (var result = session.evaluate(plugins)) {
            var body = result.bodies().getFirst();
            assertEquals("spacer.body", body.id());
            assertEquals(expected(8), body.solid().volume(), 0.01);
            assertEquals(new Bounds(-10, -10, 0, 10, 10, 8), body.solid().bounds());
            assertFalse(body.solid().contains(0, 0, 4));
            assertTrue(body.solid().contains(5, 0, 4));
        }
        Path file = directory.resolve("spacer.jcad");
        DocumentStore.save(session.document(), file, false);
        session = new ModelSession(DocumentStore.load(file));
        var original = session.document();
        session.editDimension("spacer", "height", 12, plugins);
        try (var result = session.evaluate(plugins)) {
            assertEquals(expected(12), result.bodies().getFirst().solid().volume(), 0.01);
        }
        assertTrue(session.undo());
        assertEquals(original, session.document());
        assertTrue(session.redo());
        session.add(
                new Feature.SplitBody(
                        "split",
                        new Feature.BodyRef("spacer", "body"),
                        new Feature.SplitPlane(1, 0, 0, 0)));
        try (var result = session.evaluate(plugins)) {
            assertEquals(2, result.bodies().size());
            assertEquals(
                    expected(12),
                    result.bodies().stream().mapToDouble(b -> b.solid().volume()).sum(),
                    0.01);
        }
        session.setCursor(0);
        try (var result = session.evaluate(plugins)) {
            assertTrue(result.bodies().isEmpty());
            assertThrows(IllegalStateException.class, result::requireCompleteDocument);
        }
        assertEquals(handles, NativeKernel.liveHandles());
    }

    @Test
    void missingPluginPreservesExactPayloadAndReinstallationRepairsSameIdentities()
            throws Exception {
        Path plugins = plugins(),
                jar = plugins.resolve("external features.jar"),
                hidden = directory.resolve("removed provider.jar");
        var session = new ModelSession(CadDocument.empty());
        try (var registry = new FeatureRegistry(plugins)) {
            session.add(
                    registry.create("spacer", "example.spacer", "spacer", Map.of("height", 12.0)));
        }
        session.add(
                new Feature.SplitBody(
                        "split",
                        new Feature.BodyRef("spacer", "body"),
                        new Feature.SplitPlane(1, 0, 0, 0)));
        var before = session.document();
        Path file = directory.resolve("repairable.jcad");
        DocumentStore.save(before, file, false);
        Files.move(jar, hidden);
        var missing = DocumentStore.load(file);
        try (var result = Evaluator.evaluate(missing, plugins)) {
            assertEquals(
                    List.of(Evaluation.State.FAILED, Evaluation.State.BLOCKED),
                    result.diagnostics().stream().map(Evaluation.Diagnostic::state).toList());
            assertTrue(
                    result.diagnostics().getFirst().message().contains("Missing feature provider"));
            assertThrows(IllegalStateException.class, result::bodies);
            assertThrows(IllegalStateException.class, result::requireCompleteDocument);
        }
        DocumentStore.save(missing, file, true);
        var preserved = DocumentStore.load(file);
        assertEquals(before, preserved);
        assertArrayEquals(
                ((Feature.Custom) before.features().getFirst()).payload(),
                ((Feature.Custom) preserved.features().getFirst()).payload());
        Files.move(hidden, jar);
        try (var result = Evaluator.evaluate(preserved, plugins)) {
            result.requireCompleteDocument();
            assertEquals(
                    List.of("split.negative", "split.positive"),
                    result.bodies().stream().map(Evaluation.Body::id).toList());
            assertEquals(
                    expected(12),
                    result.bodies().stream().mapToDouble(b -> b.solid().volume()).sum(),
                    0.01);
        }
    }

    @Test
    void futureOrCorruptPayloadsRemainSavableAndProviderFailuresAreIsolated() throws Exception {
        int handles = NativeKernel.liveHandles();
        var plugins = plugins();
        try (var registry = new FeatureRegistry(plugins)) {
            assertEquals(2, registry.descriptors().size());
            for (var word : List.of("Duplicate", "Incompatible", "MissingFeatureProvider"))
                assertTrue(
                        registry.diagnostics().stream().anyMatch(d -> d.contains(word)),
                        registry.diagnostics().toString());
            var session = new ModelSession(CadDocument.empty());
            session.add(registry.create("good", "example.spacer", "spacer", Map.of()));
            var before = session.snapshot();
            assertThrows(
                    ModelException.class,
                    () -> session.editDimension("good", "innerDiameter", 30, plugins));
            assertEquals(before, session.snapshot());
            assertThrows(
                    ModelException.class,
                    () ->
                            registry.create(
                                    "bad", "example.spacer", "spacer", Map.of("unknown", 3.0)));
            session.add(registry.create("failed", "example.failed", "spacer", Map.of()));
            try (var result = session.evaluate(plugins)) {
                assertEquals(Evaluation.State.OK, result.diagnostics().getFirst().state());
                assertEquals(Evaluation.State.FAILED, result.diagnostics().getLast().state());
                assertTrue(result.diagnostics().getLast().message().contains("Deliberate"));
                assertThrows(IllegalStateException.class, result::bodies);
            }
        }
        for (int version : new int[] {1, 999}) {
            var custom =
                    new Feature.Custom(
                            "opaque",
                            "example.spacer",
                            "spacer",
                            version,
                            new byte[] {0, -1, 13, 10});
            var document = new CadDocument("future", List.of(custom), 1);
            Path file = directory.resolve("payload-" + version + ".jcad");
            DocumentStore.save(document, file, false);
            try (var result = Evaluator.evaluate(DocumentStore.load(file), plugins)) {
                assertFalse(result.valid());
            }
            DocumentStore.save(DocumentStore.load(file), file, true);
            assertEquals(document, DocumentStore.load(file));
        }
        assertEquals(handles, NativeKernel.liveHandles());
    }
}
