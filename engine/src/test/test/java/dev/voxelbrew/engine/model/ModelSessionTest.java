package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelSessionTest {
    @Test
    void deletionPreservesCursorIdentityAndIsOneUndoTransaction() {
        var session = new ModelSession(CadDocument.empty());
        for (int i = 0; i < 4; i++)
            session.add(
                    new Feature.Sketch(
                            "s" + i,
                            "p" + i,
                            new Feature.Plane("origin-xy", 0),
                            new Feature.Rectangle(0, 0, 60, 40)));
        var original = session.document().features();
        session.setCursor(2);
        session.deleteFeatures(0, 2);
        assertEquals(0, session.document().cursor());
        assertEquals(original.subList(2, 4), session.document().features());
        session.undo();
        assertEquals(original, session.document().features());
        session.redo();
        assertEquals(original.subList(2, 4), session.document().features());
        session.setCursor(2);
        session.deleteFeatures(1, 2);
        assertEquals(1, session.document().cursor());
        session.undo();
        session.deleteFeatures(1, 2);
        assertEquals(1, session.document().features().size());
        var before = session.snapshot();
        assertThrows(IndexOutOfBoundsException.class, () -> session.deleteFeatures(0, 2));
        assertEquals(before, session.snapshot());
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
        var reference = new ModelSession(CadDocument.empty());
        reference.add(
                new Feature.Sketch(
                        "plate",
                        "profile",
                        new Feature.Plane("origin-xy", 0),
                        new Feature.Rectangle(0, 0, 60, 40)));
        var before = reference.document();
        assertThrows(ModelException.class, () -> reference.editDimension("plate", "offsetZ", 12));
        assertEquals(before, reference.document());
    }
}
