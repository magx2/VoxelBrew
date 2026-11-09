package dev.voxelbrew.exporter.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrintingTest {
    @TempDir
    Path directory;

    static ModelSession reference() {
        var s = new ModelSession(CadDocument.empty());
        var xy = new Feature.Plane("origin-xy", 0);
        s.add(new Feature.Sketch("plate", "plate.profile", xy, new Feature.Rectangle(0, 0, 60, 40)));
        s.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 8));
        s.add(new Feature.Sketch("hole", "hole.profile", xy, new Feature.Circle(10, 10, 4)));
        s.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "hole.profile"), new Feature.BodyRef("thickness", "body")));
        s.setPrintSetup(s.document()
                .printSetup()
                .withInstances(List.of(new BodyInstance("part", "thickness.body", Transform.translation(20, 30, 0)))));
        return s;
    }

    @Test
    void realSlicerProducesFortyLayersAndPreservesPlacementAndHole() throws Exception {
        var session = reference();
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        var output = directory.resolve("real reference job.gcode");
        var result = SliceService.slice(
                new PrusaSlicerBackend(),
                model,
                PrusaSlicerBackend.defaultExecutable(),
                output,
                false,
                new Cancellation(),
                System.out::println,
                SliceService.DIRECT);
        var job = result.job();
        assertEquals(40, job.modelLayers().size());
        assertEquals(0.2, job.modelLayers().getFirst(), 1e-6);
        assertEquals(8, job.modelLayers().getLast(), 1e-6);
        assertEquals("PLA", job.report().get("material"));
        assertEquals(model.revision(), job.report().get("revision"));
        assertTrue(job.filamentMm().orElseThrow() > 0);
        assertTrue(job.estimatedTime().isPresent());
        var layer = job.segments().stream()
                .filter(s -> s.deposits() && s.role().model() && Math.abs(s.z1() - 4) < 1e-6)
                .toList();
        assertFalse(layer.isEmpty());
        for (var segment : layer) {
            assertTrue(segment.x1() >= 19.999 && segment.x1() <= 80.001, segment.toString());
            assertTrue(segment.y1() >= 29.999 && segment.y1() <= 70.001, segment.toString());
            double dx = segment.x1() - segment.x0(), dy = segment.y1() - segment.y0();
            double t = Math.max(
                    0, Math.min(1, ((30 - segment.x0()) * dx + (40 - segment.y0()) * dy) / (dx * dx + dy * dy)));
            double distance = Math.hypot(segment.x0() + t * dx - 30, segment.y0() + t * dy - 40);
            assertTrue(distance - segment.width() / 2 >= 1.95, "deposited bead enters hole: " + segment);
        }
    }

    @Test
    void placementsAndModifiersPersistAndTrackEditsWithoutChangingCadCoordinates() throws Exception {
        var session = reference();
        var first = session.document().printSetup().instances().getFirst();
        var second = new BodyInstance(
                "copy", first.bodyId(), Transform.translation(150, 30, 0).compose(Transform.rotation(0, 0, 90)));
        var setup = session.document()
                .printSetup()
                .withInstances(List.of(first, second))
                .withSupports(
                        List.of(new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 0, 0, 0, 10, 10, 8)));
        session.setPrintSetup(setup);
        Path file = directory.resolve("placements.jcad");
        DocumentWriter.save(session.document(), file, false);
        var reopened = new ModelSession(DocumentReader.load(file));
        assertEquals(session.document(), reopened.document());
        reopened.editDimension("thickness", "distance", 5);
        assertEquals(setup, reopened.document().printSetup());
        try (var evaluation = reopened.evaluate()) {
            var bounds = evaluation.bodies().getFirst().solid().bounds();
            assertEquals(0, bounds.minX(), 0.001);
            assertEquals(60, bounds.maxX(), 0.001);
        }
        var model =
                dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(reopened.document(), MeshOptions.DEFAULT);
        assertTrue(
                PrintValidation.validate(model).valid(),
                PrintValidation.validate(model).toString());
        var before = DocumentCodec.fingerprint(reopened.document());
        reopened.setPrintSetup(setup.withInstances(List.of(first)));
        assertNotEquals(before, DocumentCodec.fingerprint(reopened.document()));
        assertTrue(reopened.undo());
        assertEquals(setup, reopened.document().printSetup());
    }
}
