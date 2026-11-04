package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.persistence.DocumentFormatException;
import dev.voxelbrew.engine.persistence.DocumentStore;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EditableEngineTest {
    @TempDir Path directory;
    private static final Feature.Plane XY = new Feature.Plane("origin-xy", 0);

    static ModelSession reference() {
        var session = new ModelSession(CadDocument.empty());
        session.add(
                new Feature.Sketch(
                        "plate", "plate.profile", XY, new Feature.Rectangle(0, 0, 60, 40)));
        session.add(
                new Feature.Extrude(
                        "thickness", new Feature.ProfileRef("plate", "plate.profile"), 5));
        session.add(new Feature.Sketch("hole", "hole.profile", XY, new Feature.Circle(10, 10, 4)));
        session.add(
                new Feature.ThroughCut(
                        "cut",
                        new Feature.ProfileRef("hole", "hole.profile"),
                        new Feature.BodyRef("thickness", "body")));
        return session;
    }

    @Test
    void reopenEditAndRollbackPreserveStableIdsAndThroughHole() throws Exception {
        var session = reference();
        Path file = directory.resolve("reference.jcad");
        DocumentStore.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentStore.load(file));
        var ids = reopened.document().features().stream().map(Feature::id).toList();
        reopened.editDimension("thickness", "distance", 8);
        DocumentStore.save(reopened.document(), file, true);
        var edited = DocumentStore.load(file);
        assertEquals(ids, edited.features().stream().map(Feature::id).toList());
        assertEquals(session.document().id(), edited.id());
        assertReference(reopened, 8, 4);
        reopened.setCursor(3);
        try (var result = reopened.evaluate()) {
            assertEquals(19200, result.bodies().getFirst().solid().volume(), 0.01);
            assertTrue(result.bodies().getFirst().solid().contains(10, 10, 4));
            assertThrows(IllegalStateException.class, result::requireCompleteDocument);
        }
        reopened.setCursor(4);
        assertReference(reopened, 8, 4);
    }

    @Test
    void rollbackIsIndependentOfUndoRedoAndRejectedEditsAreNotTransactions() {
        var session = reference();
        session.editDimension("thickness", "distance", 8);
        session.setCursor(2);
        assertTrue(session.undo());
        assertEquals(2, session.document().cursor());
        assertEquals(5, ((Feature.Extrude) session.document().features().get(1)).distance());
        assertTrue(session.redo());
        assertEquals(2, session.document().cursor());
        session.setCursor(4);
        assertReference(session, 8, 4);
        var valid = session.document();
        for (double bad : new double[] {0, -1, Double.NaN, Double.POSITIVE_INFINITY}) {
            var failure =
                    assertThrows(
                            ModelException.class,
                            () -> session.editDimension("hole", "diameter", bad));
            assertTrue(failure.getMessage().contains("hole"));
            assertTrue(failure.getMessage().contains("diameter"));
            assertEquals(valid, session.document());
        }
        session.editDimension("hole", "diameter", 6);
        var circle =
                (Feature.Circle) ((Feature.Sketch) session.document().features().get(2)).profile();
        assertEquals(10, circle.centerX());
        assertEquals(10, circle.centerY());
        assertReference(session, 8, 6);
        assertTrue(session.undo());
        assertReference(session, 8, 4);
    }

    @Test
    void missingReferenceReopensAsFailedAndBlockedAndCanBeRepaired() throws Exception {
        var session = reference();
        session.replace(
                new Feature.Extrude(
                        "thickness", new Feature.ProfileRef("missing", "missing.profile"), 5));
        Path file = directory.resolve("broken.jcad");
        DocumentStore.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentStore.load(file));
        try (var result = reopened.evaluate()) {
            assertEquals(Evaluation.State.FAILED, result.diagnostics().get(1).state());
            assertTrue(result.diagnostics().get(1).message().contains("missing"));
            assertEquals(Evaluation.State.BLOCKED, result.diagnostics().get(3).state());
            assertThrows(IllegalStateException.class, result::bodies);
            assertThrows(IllegalStateException.class, result::requireCompleteDocument);
        }
        reopened.replace(
                new Feature.Extrude(
                        "thickness", new Feature.ProfileRef("plate", "plate.profile"), 5));
        assertEquals(4, reopened.document().features().size());
        assertReference(reopened, 5, 4);
    }

    @Test
    void forwardReferencesAndWrongNamedOutputsFailAtTheirFeature() {
        var session = reference();
        session.replace(
                new Feature.Extrude(
                        "thickness", new Feature.ProfileRef("hole", "hole.profile"), 5));
        try (var result = session.evaluate()) {
            assertEquals(Evaluation.State.FAILED, result.diagnostics().get(1).state());
            assertTrue(result.diagnostics().get(1).message().contains("non-earlier"));
        }
        session.undo();
        session.replace(
                new Feature.ThroughCut(
                        "cut",
                        new Feature.ProfileRef("hole", "hole.profile"),
                        new Feature.BodyRef("thickness", "wrong")));
        try (var result = session.evaluate()) {
            assertTrue(result.diagnostics().get(3).message().contains("missing body output"));
        }
    }

    @Test
    void offsetPlanesAndSignedCircularExtrusionsRoundTrip() throws Exception {
        for (double z : new double[] {0, 12}) {
            var session = new ModelSession(CadDocument.empty());
            session.add(
                    new Feature.Sketch(
                            "rectangle",
                            "profile",
                            new Feature.Plane("plane", z),
                            new Feature.Rectangle(0, 0, 60, 40)));
            session.add(
                    new Feature.Extrude(
                            "extrude", new Feature.ProfileRef("rectangle", "profile"), 5));
            Path file = directory.resolve("offset-" + z + ".jcad");
            DocumentStore.save(session.document(), file, false);
            var reopened = DocumentStore.load(file);
            assertEquals(session.document(), reopened);
            try (var result = Evaluator.evaluate(reopened)) {
                var bounds = result.bodies().getFirst().solid().bounds();
                assertEquals(z, bounds.minZ(), 0.001);
                assertEquals(z + 5, bounds.maxZ(), 0.001);
            }
        }
        var circle = new ModelSession(CadDocument.empty());
        circle.add(
                new Feature.Sketch(
                        "circle",
                        "profile",
                        new Feature.Plane("offset", 12),
                        new Feature.Circle(10, 10, 6)));
        circle.add(new Feature.Extrude("extrude", new Feature.ProfileRef("circle", "profile"), -3));
        try (var result = circle.evaluate()) {
            var solid = result.bodies().getFirst().solid();
            assertEquals(27 * Math.PI, solid.volume(), 0.01);
            assertEquals(9, solid.bounds().minZ(), 0.001);
            assertEquals(12, solid.bounds().maxZ(), 0.001);
        }
    }

    @Test
    void failedGeometryKeepsDefinitionsAndCannotExposeStaleSolid() {
        var session = reference();
        session.editDimension("hole", "diameter", 1000);
        try (var result = session.evaluate()) {
            assertFalse(result.valid());
            assertThrows(IllegalStateException.class, result::requireCompleteDocument);
        }
        assertEquals(
                1000,
                ((Feature.Circle) ((Feature.Sketch) session.document().features().get(2)).profile())
                        .diameter());
        assertTrue(session.undo());
        assertReference(session, 5, 4);
    }

    @Test
    void truncatedAndCorruptFilesHaveUsefulErrorsAndPreserveOriginalOnFailedSave()
            throws Exception {
        Path file = directory.resolve("part.jcad");
        var document = reference().document();
        DocumentStore.save(document, file, false);
        byte[] valid = Files.readAllBytes(file);
        assertThrows(
                java.io.IOException.class,
                () -> DocumentStore.save(CadDocument.empty(), file, false));
        assertArrayEquals(valid, Files.readAllBytes(file));
        for (int length : new int[] {0, 10, 43, valid.length - 1}) {
            Files.write(file, Arrays.copyOf(valid, length));
            var failure =
                    assertThrows(DocumentFormatException.class, () -> DocumentStore.load(file));
            assertTrue(failure.getMessage().toLowerCase().contains("truncated"));
        }
        valid[valid.length - 1] ^= 1;
        Files.write(file, valid);
        assertTrue(
                assertThrows(DocumentFormatException.class, () -> DocumentStore.load(file))
                        .getMessage()
                        .contains("checksum"));
        assertThrows(DocumentFormatException.class, () -> DocumentStore.save(document, file, true));
        assertArrayEquals(valid, Files.readAllBytes(file));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void newerSchemasAndUnknownFieldsCannotBeDestructivelyWritten() throws Exception {
        for (boolean newerVersion : new boolean[] {true, false}) {
            Path file = directory.resolve("future-" + newerVersion + ".jcad");
            DocumentStore.save(reference().document(), file, false);
            byte[] bytes = Files.readAllBytes(file);
            var message =
                    DocumentProto.Document.parseFrom(Arrays.copyOfRange(bytes, 44, bytes.length))
                            .toBuilder();
            if (newerVersion) {
                message.setSchemaVersion(DocumentStore.SCHEMA_VERSION + 1);
            } else {
                message.setUnknownFields(
                        com.google.protobuf.UnknownFieldSet.newBuilder()
                                .addField(
                                        100,
                                        com.google.protobuf.UnknownFieldSet.Field.newBuilder()
                                                .addVarint(42)
                                                .build())
                                .build());
            }
            byte[] payload = message.build().toByteArray();
            byte[] future =
                    ByteBuffer.allocate(44 + payload.length)
                            .put(bytes, 0, 8)
                            .putInt(payload.length)
                            .put(MessageDigest.getInstance("SHA-256").digest(payload))
                            .put(payload)
                            .array();
            Files.write(file, future);
            assertTrue(
                    assertThrows(DocumentFormatException.class, () -> DocumentStore.load(file))
                            .getMessage()
                            .contains("read-only"));
            assertThrows(
                    DocumentFormatException.class,
                    () -> DocumentStore.save(CadDocument.empty(), file, true));
            assertArrayEquals(future, Files.readAllBytes(file));
        }
    }

    @Test
    void concurrentCreateNeverOverwritesAnotherDocument() throws Exception {
        Path file = directory.resolve("race.jcad");
        var first = CadDocument.empty();
        var second = CadDocument.empty();
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var results = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (var document : List.of(first, second)) {
                results.add(
                        executor.submit(
                                () -> {
                                    start.await();
                                    try {
                                        DocumentStore.save(document, file, false);
                                        return true;
                                    } catch (java.io.IOException expectedConflict) {
                                        return false;
                                    }
                                }));
            }
            start.countDown();
            int successes = 0;
            for (var result : results) {
                if (result.get()) {
                    successes++;
                }
            }
            assertEquals(1, successes);
        }
        assertTrue(List.of(first.id(), second.id()).contains(DocumentStore.load(file).id()));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }

    @Test
    void undoRedoFeatureCreationKeepsCursorAtEnd() {
        var session = reference();
        assertTrue(session.undo());
        assertEquals(3, session.document().cursor());
        assertTrue(session.redo());
        assertEquals(4, session.document().cursor());
        assertReference(session, 5, 4);
    }

    @Test
    void sharedOffsetPlaneEditsAreOneTransactionAndOriginIsFixed() {
        var session = new ModelSession(CadDocument.empty());
        var plane = new Feature.Plane("offset-12", 12);
        session.add(
                new Feature.Sketch(
                        "one", "one.profile", plane, new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Sketch("two", "two.profile", plane, new Feature.Circle(10, 10, 4)));
        session.editDimension("one", "offsetZ", 15);
        for (var feature : session.document().features()) {
            assertEquals(15, ((Feature.Sketch) feature).plane().offsetZ());
        }
        session.undo();
        for (var feature : session.document().features()) {
            assertEquals(12, ((Feature.Sketch) feature).plane().offsetZ());
        }
        var reference = reference();
        var before = reference.document();
        assertThrows(ModelException.class, () -> reference.editDimension("plate", "offsetZ", 12));
        assertEquals(before, reference.document());
    }

    private static void assertReference(ModelSession session, double thickness, double diameter) {
        try (var result = session.evaluate()) {
            assertTrue(result.valid(), result.diagnostics().toString());
            assertEquals(1, result.requireCompleteDocument().size());
            var body = result.bodies().getFirst();
            assertEquals("thickness.body", body.id());
            assertEquals(
                    (2400 - Math.PI * diameter * diameter / 4) * thickness,
                    body.solid().volume(),
                    0.01);
            assertFalse(body.solid().contains(10, 10, thickness / 2));
            assertTrue(body.solid().contains(15, 10, thickness / 2));
            assertEquals(60, body.solid().bounds().maxX(), 0.001);
            assertEquals(40, body.solid().bounds().maxY(), 0.001);
            assertEquals(thickness, body.solid().bounds().maxZ(), 0.001);
        }
    }
}
