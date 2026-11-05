package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentStore;
import dev.voxelbrew.engine.sketch.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SketchCurveTypesIntegrationTest {
    @TempDir Path directory;

    private SketchGeometry geometry(Entity... entities) {
        return new SketchGeometry(
                List.of(entities), List.of(), Arrays.stream(entities).map(Entity::id).toList());
    }

    @Test
    void straightAndArcSlotsMatchAnalyticAreaAndRetainShapeOnWidthEdits() {
        var options = SketchTools.Options.defaults();
        var g =
                SketchTools.add(
                        SketchGeometry.empty(),
                        SketchTools.Tool.SLOT_CENTER_TO_CENTER,
                        "slot",
                        List.of(new Vec(0, 0), new Vec(20, 0), new Vec(10, 2)),
                        List.of(),
                        options);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 3)) {
            assertEquals((80 + 4 * Math.PI) * 3, solid.volume(), 1e-6);
        }
        g =
                g.add(
                        SketchConstraints.create(
                                g,
                                "width",
                                Type.RADIUS,
                                List.of(new Ref("slot-end", Part.OBJECT)),
                                2));
        var edited = Sketches.editDimension(g, "width", 3);
        SketchConstraints.requireSatisfied(edited);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(edited), 0, 3)) {
            assertTrue(solid.volume() > 0);
        }
        g =
                SketchTools.add(
                        SketchGeometry.empty(),
                        SketchTools.Tool.SLOT_ARC_CENTER,
                        "slot",
                        List.of(new Vec(0, 0), new Vec(10, 0), new Vec(0, 10), new Vec(12, 0)),
                        List.of(),
                        options);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 3)) {
            assertEquals((20 * Math.PI + 4 * Math.PI) * 3, solid.volume(), 1e-6);
        }
    }

    @Test
    void exactEllipseAndRationalConicVolumesMatchAnalyticSolutions() {
        int handles = NativeKernel.liveHandles();
        for (double angle : new double[] {0, .7}) {
            var e = Sketches.entity("ellipse", Kind.ELLIPSE, false, 3, 4, 10, 5, angle);
            try (var solid = Solid.profilePrism(SketchProfiles.profile(geometry(e)), 2, -3)) {
                assertEquals(Math.PI * 50 * 3, solid.volume(), 1e-6);
                assertTrue(solid.contains(3, 4, 0));
            }
        }
        double weight = Math.sqrt(.5);
        var arc =
                Sketches.entity(
                        "conic", Kind.CONIC, false, 10, 0, 10, 10, 0, 10, weight / (1 + weight));
        var a = Sketches.entity("a", Kind.LINE, false, 0, 10, 0, 0);
        var b = Sketches.entity("b", Kind.LINE, false, 0, 0, 10, 0);
        try (var solid = Solid.profilePrism(SketchProfiles.profile(geometry(arc, a, b)), 0, 2)) {
            assertEquals(Math.PI * 50, solid.volume(), 1e-6);
        }
        assertEquals(handles, NativeKernel.liveHandles());
    }

    @Test
    void exactControlAndFitSplinesProduceClosedKernelProfiles() {
        int handles = NativeKernel.liveHandles();
        for (var kind : List.of(Kind.CONTROL_SPLINE, Kind.FIT_SPLINE)) {
            var e =
                    new Entity(
                            "spline",
                            kind,
                            List.of(0., 0., 0., 10., 10., 20., 20., 20., 30., 10., 30., 0.),
                            false,
                            kind == Kind.CONTROL_SPLINE ? 5 : 0,
                            true,
                            null);
            try (var solid = Solid.profilePrism(SketchProfiles.profile(geometry(e)), 0, 2)) {
                assertTrue(solid.volume() > 100);
                assertTrue(solid.contains(15, 10, 1));
            }
        }
        assertEquals(handles, NativeKernel.liveHandles());
    }

    @Test
    void textOutlinesRoundTripAndExtrudeIntoIndependentBodiesWithHoles() throws Exception {
        int handles = NativeKernel.liveHandles();
        var text = SketchText.create("label", "OB", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        var g = geometry(text);
        var edited = SketchText.create("label", "IB", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        assertEquals(
                g.curves(text).stream()
                        .map(Entity::id)
                        .filter(id -> id.contains("glyph-1-"))
                        .toList(),
                geometry(edited).curves(edited).stream()
                        .map(Entity::id)
                        .filter(id -> id.contains("glyph-1-"))
                        .toList());
        var longId =
                SketchText.create(
                        "t".repeat(128), "OB", "SansSerif", 0, 0, 0, 10, 0, .1, "", false);
        assertEquals(2, SketchProfiles.regions(geometry(longId)).size());
        var session = new ModelSession(CadDocument.empty());
        session.add(
                new Feature.Sketch(
                        "sketch",
                        "profile",
                        new Feature.Plane("origin-xy", 0),
                        new Feature.Constrained(g)));
        session.add(new Feature.Extrude("extrude", new Feature.ProfileRef("sketch", "profile"), 2));
        var file = directory.resolve("text and curves.jcad");
        DocumentStore.save(session.document(), file, false);
        assertEquals(session.document(), DocumentStore.load(file));
        try (var evaluation = new ModelSession(DocumentStore.load(file)).evaluate()) {
            evaluation.requireCompleteDocument();
            assertEquals(2, evaluation.bodies().size());
            assertEquals("extrude.body", evaluation.bodies().getFirst().id());
            assertTrue(
                    evaluation.bodies().stream().mapToDouble(b -> b.solid().volume()).sum() > 20);
            assertEquals(
                    2, evaluation.bodies().stream().map(Evaluation.Body::id).distinct().count());
        }
        var path = Sketches.entity("path", Kind.ARC, true, 0, 0, 30, 0, Math.PI);
        var onPath =
                SketchText.create(
                        "path-text", "CAD", "SansSerif", 0, 5, 1, 4, 0, 0, path.id(), false);
        var pathGeometry =
                new SketchGeometry(List.of(path, onPath), List.of(), List.of(onPath.id()));
        assertEquals(3, SketchProfiles.regions(pathGeometry).size());
        var changed = pathGeometry.replace(path.withValues(List.of(0., 0., 40., 0., Math.PI)));
        assertNotEquals(pathGeometry.curves(onPath), changed.curves(onPath));
        var shortPath = Sketches.entity("path", Kind.LINE, true, 0, 0, 5, 0);
        var oversized =
                SketchText.create("path-text", "W", "SansSerif", 0, 0, 0, 10, 0, 0, "path", false);
        var overflow =
                new SketchGeometry(
                        List.of(shortPath, oversized), List.of(), List.of(oversized.id()));
        assertThrows(IllegalArgumentException.class, () -> overflow.curves(oversized));
        assertEquals(handles, NativeKernel.liveHandles());
    }
}
