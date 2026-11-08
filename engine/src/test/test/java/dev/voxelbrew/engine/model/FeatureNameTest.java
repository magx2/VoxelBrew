package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class FeatureNameTest {
    @Test
    void renamePersistsAndParticipatesInUndoWithoutChangingFeatureIdentity() throws Exception {
        var sketch = new Feature.Sketch(
                "sketch", "profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40));
        var initial = new CadDocument("named", List.of(sketch), 1);
        var session = new ModelSession(initial);
        session.renameFeature("sketch", "  Base profile  ");
        assertEquals("Base profile", session.document().featureName("sketch", "Sketch"));
        assertEquals(initial.features(), session.document().features());
        assertEquals(session.document(), DocumentCodec.decode(DocumentCodec.encode(session.document())));
        assertTrue(session.undo());
        assertEquals(initial, session.document());
        assertTrue(session.redo());
        assertEquals("Base profile", session.document().featureNames().get("sketch"));
        assertThrows(IllegalArgumentException.class, () -> session.renameFeature("missing", "Invalid"));
    }
}
