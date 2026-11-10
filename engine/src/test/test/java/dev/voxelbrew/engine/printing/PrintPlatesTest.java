package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrintPlatesTest {
    @Test
    void platesRoundTripAndExposeOnlySelectedContents() throws Exception {
        var first = new BodyInstance("first", "body", Transform.translation(10, 20, 0));
        var support = new SupportVolume("support", "first", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 2, 2, 5);
        var setup = PrintSetup.empty().withInstances(List.of(first)).withSupports(List.of(support));
        var legacy = new CadDocument("doc", List.of(), 0, setup);
        assertEquals(legacy, DocumentCodec.decode(DocumentCodec.encode(legacy)));
        setup = setup.addPlate("second");
        assertTrue(setup.instances().isEmpty());
        assertTrue(setup.supports().isEmpty());
        assertEquals(List.of(first), setup.selectPlate("plate-1").instances());
        var second = new BodyInstance("second", "body", Transform.translation(50, 50, 0));
        setup = setup.withInstances(List.of(second));
        var document = new CadDocument("doc", List.of(), 0, setup);
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)));
        assertEquals(document, DocumentCodec.decodeJson(DocumentCodec.encodeJson(document)));
        assertEquals(List.of(second), document.printSetup().instances());
        assertEquals(
                List.of(first), document.printSetup().selectPlate("plate-1").instances());
        assertThrows(IllegalArgumentException.class, () -> PrintSetup.empty().removeActivePlate());
        assertThrows(IllegalArgumentException.class, () -> PrintSetup.empty().selectPlate("absent"));
        assertThrows(IllegalArgumentException.class, () -> PrintSetup.empty().addPlate("plate-1"));
    }

    @Test
    void movingObjectsCarriesSupportsAndPlateChangesAreUndoable() {
        var instance = new BodyInstance("first", "body", Transform.translation(10, 20, 0));
        var support = new SupportVolume("support", "first", SupportVolume.Role.BLOCKER, 0, 0, 0, 2, 2, 5);
        var initial = PrintSetup.empty()
                .withInstances(List.of(instance))
                .withSupports(List.of(support))
                .addPlate("second")
                .selectPlate("plate-1");
        var session = new ModelSession(new CadDocument("doc", List.of(), 0, initial));
        session.setPrintSetup(initial.moveInstance("first", "second"));
        assertTrue(session.document().printSetup().instances().isEmpty());
        assertTrue(session.document().printSetup().supports().isEmpty());
        var target = session.document().printSetup().selectPlate("second");
        assertEquals(List.of(instance), target.instances());
        assertEquals(List.of(support), target.supports());
        session.undo();
        assertEquals(initial, session.document().printSetup());
        session.redo();
        assertTrue(session.document().printSetup().instances().isEmpty());
        assertEquals("plate-1", target.removeActivePlate().activePlateId());
    }
}
