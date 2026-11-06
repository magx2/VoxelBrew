package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BodyStyleTest {
    private static final BodyStyle RED_PETG = new BodyStyle("PETG", "#FF0000");
    private static final BodyStyle BLUE_PLA = new BodyStyle("PLA", "#0000FF");

    @Test
    void validatesOpaqueColorsAndCustomMaterialLabels() {
        assertEquals(new BodyStyle("Nylon CF", "#ABCDEF"), new BodyStyle("  Nylon CF  ", "#abcdef"));
        assertThrows(IllegalArgumentException.class, () -> new BodyStyle("PLA", "red"));
        assertThrows(IllegalArgumentException.class, () -> new BodyStyle("PLA", "#FF000080"));
        assertThrows(IllegalArgumentException.class, () -> new BodyStyle("PET\nG", "#FF0000"));
        assertThrows(IllegalArgumentException.class, () -> new BodyStyle("x".repeat(65), "#FF0000"));
    }

    @Test
    void assignmentsAreAtomicUndoableAndIndependentOfTimelineAndParameters() {
        var session = new ModelSession(CadDocument.empty());
        session.setBodyStyle("one.body", RED_PETG);
        long revision = session.revision();
        session.setBodyStyle("one.body", RED_PETG);
        assertEquals(revision, session.revision(), "Identical assignments create no history entry");
        session.setBodyStyle("two.body", BLUE_PLA);
        var saved = session.document();
        session.setCursor(0);
        session.undo();
        assertEquals(RED_PETG, session.document().bodyStyle("one.body"));
        assertEquals(BodyStyle.DEFAULT, session.document().bodyStyle("two.body"));
        session.redo();
        assertEquals(saved, session.document());
        session.setParameters(
                saved.parameters().put("width", dev.voxelbrew.engine.parameters.ParameterUnit.MM, "10", ""));
        assertEquals(saved.bodyStyles(), session.document().bodyStyles());
        session.setBodyStyle("one.body", null);
        assertEquals(BodyStyle.DEFAULT, session.document().bodyStyle("one.body"));
        session.undo();
        assertEquals(RED_PETG, session.document().bodyStyle("one.body"));
        assertThrows(
                UnsupportedOperationException.class, () -> saved.bodyStyles().clear());
        String longestFeatureBody = "a".repeat(128) + ".body";
        session.setBodyStyle(longestFeatureBody, BLUE_PLA);
        assertEquals(BLUE_PLA, session.document().bodyStyle(longestFeatureBody));
    }

    @Test
    void splitInheritanceOverridesAndDeterministicPersistenceSurviveBrokenReferences() throws Exception {
        var split =
                new Feature.SplitBody("split", new Feature.BodyRef("cut", "body"), new Feature.SplitPlane(1, 0, 0, 30));
        var cut = new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "profile"), new Feature.BodyRef("base", "body"));
        var document = new CadDocument(
                "styles",
                List.of(cut, split),
                2,
                dev.voxelbrew.engine.printing.PrintSetup.empty(),
                dev.voxelbrew.engine.parameters.ParameterSet.empty(),
                Map.of("base.body", RED_PETG, "split.positive", BLUE_PLA));
        var reopened = DocumentCodec.decode(DocumentCodec.encode(document));
        assertEquals(document, reopened);
        assertEquals(RED_PETG, reopened.bodyStyle("split.negative"));
        assertEquals(BLUE_PLA, reopened.bodyStyle("split.positive"));
        var session = new ModelSession(reopened);
        session.setBodyStyle("split.positive", null);
        assertEquals(RED_PETG, session.document().bodyStyle("split.positive"));
        assertNotEquals(DocumentCodec.fingerprint(document), DocumentCodec.fingerprint(session.document()));
        session.setBodyStyle("base.body", new BodyStyle("TPU", "#00FF00"));
        assertEquals("TPU", session.document().bodyStyle("split.negative").material());
        var reverse = new java.util.LinkedHashMap<String, BodyStyle>();
        reverse.put("split.positive", BLUE_PLA);
        reverse.put("base.body", RED_PETG);
        assertArrayEquals(
                DocumentCodec.encode(document),
                DocumentCodec.encode(new CadDocument(
                        document.id(),
                        document.features(),
                        document.cursor(),
                        document.printSetup(),
                        document.parameters(),
                        reverse)));
        var cyclic = new CadDocument(
                "cyclic",
                List.of(new Feature.SplitBody("split", new Feature.BodyRef("split", "negative"), split.plane())),
                1);
        assertEquals(BodyStyle.DEFAULT, cyclic.bodyStyle("split.negative"));
    }
}
