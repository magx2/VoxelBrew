package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ModelSessionTest {
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
