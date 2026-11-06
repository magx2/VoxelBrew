package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.*;
import org.junit.jupiter.api.Test;

class BodyNameTest {
    @Test
    void namesAreUndoableWithoutChangingIdsGeometryOrOtherMetadata() {
        var sketch =
                new Feature.Sketch(
                        "sketch",
                        "profile",
                        new Feature.Plane("origin-xy", 0),
                        new Feature.Rectangle(0, 0, 60, 40));
        var extrusion =
                new Feature.Extrude("solid", new Feature.ProfileRef("sketch", "profile"), 5);
        var initial = new CadDocument("named", List.of(sketch, extrusion), 2);
        var session = new ModelSession(initial);
        session.renameBody("solid.body", "  Mounting plate  ");
        assertEquals("Mounting plate", session.document().bodyName("solid.body", "Body 1"));
        assertEquals(initial.features(), session.document().features());
        assertEquals(initial.id(), session.document().id());
        long revision = session.revision();
        session.renameBody("solid.body", "Mounting plate");
        assertEquals(revision, session.revision());
        assertTrue(session.undo());
        assertEquals(initial, session.document());
        assertFalse(session.canUndo(), "Rename adds exactly one transaction");
        session.redo();
        var style = new BodyStyle("PETG", "#FF0000");
        session.setBodyStyle("solid.body", style);
        session.editDimension("solid", "distance", 8);
        assertEquals("Mounting plate", session.document().bodyNames().get("solid.body"));
        session.setCursor(1);
        session.undo();
        assertEquals(1, session.document().cursor());
        assertEquals(5, ((Feature.Extrude) session.document().features().get(1)).distance());
        assertEquals(style, session.document().bodyStyle("solid.body"));
        session.redo();
        assertEquals(1, session.document().cursor());
        session.renameBody("solid.body", null);
        assertEquals("Body 1", session.document().bodyName("solid.body", "Body 1"));
        session.undo();
        assertEquals("Mounting plate", session.document().bodyNames().get("solid.body"));
    }

    @Test
    void unicodeNamesRoundTripDeterministicallyAndRemainIndependentOfStableReferences()
            throws Exception {
        var names = new LinkedHashMap<String, String>();
        names.put("z.body", "Pokrywa żółta");
        names.put("a.body", "Support / left");
        var document =
                new CadDocument(
                        "names",
                        List.of(),
                        0,
                        dev.voxelbrew.engine.printing.PrintSetup.empty(),
                        dev.voxelbrew.engine.parameters.ParameterSet.empty(),
                        Map.of(),
                        names);
        names.clear();
        assertEquals(2, document.bodyNames().size());
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)));
        assertThrows(UnsupportedOperationException.class, () -> document.bodyNames().clear());
        var reverse = new TreeMap<>(document.bodyNames());
        var reordered =
                new CadDocument(
                        document.id(),
                        document.features(),
                        document.cursor(),
                        document.printSetup(),
                        document.parameters(),
                        document.bodyStyles(),
                        reverse);
        assertArrayEquals(DocumentCodec.encode(document), DocumentCodec.encode(reordered));
        assertEquals(DocumentCodec.fingerprint(document), DocumentCodec.fingerprint(reordered));
        var session = new ModelSession(document);
        session.renameBody("a.body", "Right support");
        assertNotEquals(
                DocumentCodec.fingerprint(document), DocumentCodec.fingerprint(session.document()));
        var unnamed = new CadDocument("names", List.of(), 0);
        assertTrue(DocumentCodec.decode(DocumentCodec.encode(unnamed)).bodyNames().isEmpty());
    }

    @Test
    void invalidNamesLeaveTheDocumentAndUndoHistoryUntouched() {
        var session = new ModelSession(CadDocument.empty());
        var before = session.snapshot();
        for (String name : List.of("", "   ", "line\nbreak", "x\u0000y", "x".repeat(129)))
            assertThrows(
                    IllegalArgumentException.class, () -> session.renameBody("body.one", name));
        assertThrows(
                IllegalArgumentException.class,
                () -> session.renameBody("invalid body ID", "Plate"));
        assertEquals(before, session.snapshot());
    }
}
