package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SplitBodyTest {
    @TempDir
    Path directory;

    @Test
    void splitOutputsPersistReplayAndConserveExactVolume() throws Exception {
        var session = EditableEngineTest.reference();
        session.editDimension("thickness", "distance", 8);
        session.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("cut", "body"), new Feature.SplitPlane(1, 0, 0, 30)));
        var file = directory.resolve("split.vxb");
        DocumentWriter.save(session.document(), file, false);
        session = new ModelSession(DocumentReader.load(file));
        try (var evaluated = session.evaluate()) {
            var bodies = evaluated.requireCompleteDocument();
            assertEquals(
                    List.of("split.negative", "split.positive"),
                    bodies.stream().map(Evaluation.Body::id).toList());
            assertEquals(
                    19099.469035085127,
                    bodies.stream().mapToDouble(b -> b.solid().volume()).sum(),
                    0.01);
            assertEquals(30, bodies.get(0).solid().bounds().maxX(), 0.001);
            assertEquals(30, bodies.get(1).solid().bounds().minX(), 0.001);
            assertFalse(bodies.get(0).solid().contains(10, 10, 4));
        }
        session.editDimension("split", "offset", 20);
        try (var evaluated = session.evaluate()) {
            assertEquals(20, evaluated.bodies().getFirst().solid().bounds().maxX(), 0.001);
        }
        assertTrue(session.undo());
        session.setCursor(4);
        try (var evaluated = session.evaluate()) {
            assertEquals(1, evaluated.bodies().size());
            assertEquals("thickness.body", evaluated.bodies().getFirst().id());
        }
        session.setCursor(5);
        assertTrue(session.redo());
        try (var evaluated = session.evaluate()) {
            assertEquals(20, evaluated.bodies().getFirst().solid().bounds().maxX(), 0.001);
        }
    }

    @Test
    void missedPlaneIsRepairableAndNamedOutputCanFeedAnotherFeature() {
        var session = EditableEngineTest.reference();
        session.add(new Feature.SplitBody(
                "split", new Feature.BodyRef("cut", "body"), new Feature.SplitPlane(1, 0, 0, 100)));
        session.add(new Feature.SplitBody(
                "quarter", new Feature.BodyRef("split", "positive"), new Feature.SplitPlane(0, 1, 0, 20)));
        try (var failed = session.evaluate()) {
            assertEquals(Evaluation.State.FAILED, failed.diagnostics().get(4).state());
            assertTrue(failed.diagnostics().get(4).message().contains("does not intersect"));
            assertEquals(Evaluation.State.BLOCKED, failed.diagnostics().get(5).state());
            assertThrows(IllegalStateException.class, failed::requireCompleteDocument);
        }
        session.editDimension("split", "offset", 30);
        try (var result = session.evaluate()) {
            assertEquals(3, result.requireCompleteDocument().size());
            assertEquals(
                    (60 * 40 - 4 * Math.PI) * 5,
                    result.bodies().stream()
                            .mapToDouble(b -> b.solid().volume())
                            .sum(),
                    0.01);
        }
    }
}
