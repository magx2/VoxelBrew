package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class PrintPlatesTest {
    @Test
    void perPlateSliceTargetsSurviveBinaryAndJsonRoundTrips() throws Exception {
        var setup = PrintSetup.empty().withPrintTarget("workshop").addPlate("second");
        var first = setup.plates().getFirst().id();
        var second = setup.plates().get(1).id();
        var lines = List.of("G21", "G1 X1 E1");
        var connected = PrintTarget.attach(null, lines, setup);
        var profileOnly = PrintTarget.attach(null, lines, setup.withPrintTarget(null));
        var document = new CadDocument("doc", List.of(), 0, setup)
                .withGcode(java.util.Map.of(first, lines, second, lines))
                .withPrintArtifacts(java.util.Map.of(first, connected, second, profileOnly));
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)));
        assertEquals(document, DocumentCodec.decodeJson(DocumentCodec.encodeJson(document)));
        assertEquals("printer", connected.metadata().get("target_kind"));
        assertEquals("profile", profileOnly.metadata().get("target_kind"));
    }

    @Test
    void disablingSupportsPersistsAndPreservesDefinitionsAcrossPlateEdits() throws Exception {
        var process = new ProcessSettings(0.2, 2, 20, true, true, 45, 0.2, 3, 0, 2);
        var enabled = PrintSetup.empty()
                .withProfile("prusa-slicer", "generic-marlin-200-0.4", "PLA", process)
                .withInstances(List.of(new BodyInstance("part", "body", Transform.IDENTITY)))
                .withSupports(
                        List.of(new SupportVolume("manual", "part", SupportVolume.Role.ENFORCER, 0, 0, 0, 5, 5, 5)))
                .addPlate("spare")
                .selectPlate("plate-1");
        var disabled = enabled.withSupportsDisabled(true);
        var document = new CadDocument("doc", List.of(), 0, disabled);
        assertEquals(document, DocumentCodec.decode(DocumentCodec.encode(document)));
        assertEquals(document, DocumentCodec.decodeJson(DocumentCodec.encodeJson(document)));
        assertEquals(enabled, disabled.withSupportsDisabled(false));
        assertTrue(disabled.renamePlate("plate-1", "Main")
                .selectPlate("spare")
                .removeActivePlate()
                .withWorkspaceSettings(java.util.Map.of())
                .supportsDisabled());
        assertTrue(disabled.moveInstance("part", "spare").selectPlate("spare").supportsDisabled());
        var model =
                new ManufacturingModel("doc", "revision", disabled, PrintProfile.resolve(disabled), java.util.Map.of());
        assertTrue(model.setup().supports().isEmpty());
        assertFalse(model.profile().process().automaticSupports());
        assertFalse(model.profile().process().supportsOnBedOnly());
        assertEquals(0, model.profile().process().raftLayers());
        assertEquals(enabled.supports(), disabled.supports());
        assertEquals(process, disabled.process());
        assertFalse(PrintSetup.empty().supportsDisabled());
    }

    @Test
    void allSlicerSettingsRoundTripAndSurvivePlateAndProfileEdits() throws Exception {
        var settings = java.util.Map.of(
                "print-output",
                "parts/custom.gcode",
                "print-overwrite",
                "true",
                "print-layer-height",
                "0.25",
                "print-support-width",
                "2 + 3",
                "print-rotation-space",
                "LOCAL");
        var process = new ProcessSettings(0.25, 4, 35, true, true, 55, 0.3, 4, 6, 2);
        var setup = PrintSetup.empty()
                .withWorkspaceSettings(settings)
                .withProfile("prusa-slicer", "generic-marlin-200-0.4", "PETG", process)
                .withInstances(List.of(new BodyInstance("part", "body", Transform.translation(12, 34, 5))))
                .withSupports(List.of(new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 1, 2, 3, 4, 5, 6)))
                .renamePlate("plate-1", "Main")
                .addPlate("spare")
                .selectPlate("plate-1");
        assertEquals(settings, setup.workspaceSettings());
        var doc = new CadDocument("doc", List.of(), 0, setup);
        assertEquals(doc, DocumentCodec.decode(DocumentCodec.encode(doc)));
        assertEquals(doc, DocumentCodec.decodeJson(DocumentCodec.encodeJson(doc)));
        assertEquals(
                settings,
                setup.moveInstance("part", "spare")
                        .selectPlate("spare")
                        .removeActivePlate()
                        .workspaceSettings());
        assertThrows(
                UnsupportedOperationException.class,
                () -> setup.workspaceSettings().put("bad", "value"));
    }

    @Test
    void namedPlatesPersistRemainUndoableAndSurvivePlacementEdits() throws Exception {
        var instance = new BodyInstance("part", "body", Transform.translation(10, 20, 0));
        var initial = PrintSetup.empty().withInstances(List.of(instance));
        var session = new ModelSession(new CadDocument("doc", List.of(), 0, initial));
        session.setPrintSetup(initial.renamePlate("plate-1", "  Zażółć / Assembly  "));
        var named = session.document().printSetup();
        assertEquals("Zażółć / Assembly", named.activePlate().name());
        assertEquals(3, named.version());
        assertEquals(initial.instances(), named.instances());
        assertEquals("plate-1", named.activePlateId());
        assertEquals(session.document(), DocumentCodec.decode(DocumentCodec.encode(session.document())));
        assertEquals(session.document(), DocumentCodec.decodeJson(DocumentCodec.encodeJson(session.document())));
        assertSame(named, named.renamePlate("plate-1", "Zażółć / Assembly"));
        session.undo();
        assertEquals(initial, session.document().printSetup());
        assertFalse(session.canUndo());
        session.redo();
        assertEquals(named, session.document().printSetup());
        var edited = named.withInstances(List.of(instance))
                .withSupports(List.of())
                .withProfile(named.backendId(), named.printerId(), "PETG", named.process())
                .addPlate("second")
                .renamePlate("second", "Spare parts")
                .selectPlate("plate-1")
                .moveInstance("part", "second");
        assertEquals("Zażółć / Assembly", edited.activePlate().name());
        assertEquals("Spare parts", edited.selectPlate("second").activePlate().name());
        assertEquals(
                "Zażółć / Assembly",
                edited.selectPlate("second").removeActivePlate().activePlate().name());
        assertEquals(List.of(instance), edited.selectPlate("second").instances());
        var multi = new CadDocument("doc", List.of(), 0, edited);
        assertEquals(multi, DocumentCodec.decode(DocumentCodec.encode(multi)));
        for (String invalid : List.of("", "  ", "x".repeat(129), "bad\nname"))
            assertThrows(IllegalArgumentException.class, () -> named.renamePlate("plate-1", invalid));
        assertThrows(IllegalArgumentException.class, () -> named.renamePlate("missing", "Name"));
    }

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
