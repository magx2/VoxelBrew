package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ModelSessionGcodeTest {
    @Test
    void plateSelectionRetainsJobsButPlacementEditsAndRedoDiscardThem() {
        var setup = PrintSetup.empty().addPlate("second").selectPlate("plate-1");
        var jobs = Map.of("plate-1", List.of("G90"), "second", List.of("G21"));
        var session = new ModelSession(new CadDocument("doc", List.of(), 0, setup).withGcode(jobs));
        session.setPrintSetup(setup.selectPlate("second"));
        assertEquals(jobs, session.document().gcode());
        assertEquals(List.of("G21"), session.document().activeGcode());
        session.setPrintSetup(session.document()
                .printSetup()
                .withInstances(List.of(new BodyInstance("part", "body", Transform.translation(10, 20, 0)))));
        assertTrue(session.document().gcode().isEmpty());
        assertTrue(session.undo());
        session.setGcode(jobs);
        assertTrue(session.redo());
        assertTrue(session.document().gcode().isEmpty());
    }

    @Test
    void modelAndPrintEditsInvalidateAllJobsAndUndoDoesNotResurrectThem() {
        var base = CadDocument.empty();
        var document = base.withGcode(Map.of(base.printSetup().activePlateId(), List.of("G90")));
        var session = new ModelSession(document);
        assertEquals(List.of("G90"), session.document().activeGcode());
        session.add(new Feature.Sketch(
                "plate", "profile", new Feature.Plane("xy", 0), new Feature.Rectangle(0, 0, 20, 20)));
        assertTrue(session.document().gcode().isEmpty());
        session.undo();
        assertTrue(session.document().gcode().isEmpty());
        session.setGcode(document.gcode());
        session.setPrintSetup(session.document().printSetup().addPlate("second"));
        assertTrue(session.document().gcode().isEmpty());
        session.undo();
        assertTrue(session.document().gcode().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> base.withGcode(Map.of("plate-1", List.of("G90\nG21"))));
    }
}
