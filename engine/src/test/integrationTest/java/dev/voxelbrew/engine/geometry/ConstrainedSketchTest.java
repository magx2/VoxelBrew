package dev.voxelbrew.engine.geometry;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentStore;
import dev.voxelbrew.engine.sketch.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConstrainedSketchTest {
    @TempDir Path directory;

    private static Feature.Sketch sketch(SketchGeometry geometry) {
        return new Feature.Sketch(
                "sketch",
                "profile",
                new Feature.Plane("origin-xy", 0),
                new Feature.Constrained(geometry));
    }

    private static double volume(ModelSession session) {
        try (var evaluation = session.evaluate()) {
            evaluation.requireCompleteDocument();
            return evaluation.bodies().getFirst().solid().volume();
        }
    }

    @Test
    void dimensionedRectangleAndCircleHoleRoundTripAndRebuildWithStableIds() throws Exception {
        int handles = NativeKernel.liveHandles();
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        var circle = Sketches.entity("hole", Kind.CIRCLE, false, 10, 10, 2);
        g = g.add(circle);
        var boundary = new ArrayList<>(g.boundary());
        boundary.add(circle.id());
        g = g.withBoundary(boundary);
        g = g.add(SketchConstraints.create(g, "hole-fixed", Type.FIX, List.of(circle.ref()), 0));
        var session = new ModelSession(CadDocument.empty());
        session.add(sketch(g));
        session.add(
                new Feature.Extrude("thickness", new Feature.ProfileRef("sketch", "profile"), 8));
        assertEquals(19099.469035085127, volume(session), 0.01);
        var file = directory.resolve("constrained plate.jcad");
        DocumentStore.save(session.document(), file, false);
        assertEquals(session.document(), DocumentStore.load(file));
        session = new ModelSession(DocumentStore.load(file));
        session.editDimension("sketch", "width", 80);
        assertEquals((80 * 40 - 4 * Math.PI) * 8, volume(session), 0.01);
        assertEquals(
                "profile", ((Feature.Sketch) session.document().features().getFirst()).profileId());
        assertTrue(session.undo());
        assertEquals(19099.469035085127, volume(session), 0.01);
        assertTrue(session.redo());
        session.setCursor(1);
        try (var evaluation = session.evaluate()) {
            assertTrue(evaluation.bodies().isEmpty());
            assertThrows(IllegalStateException.class, evaluation::requireCompleteDocument);
        }
        session.setCursor(2);
        assertEquals((80 * 40 - 4 * Math.PI) * 8, volume(session), 0.01);
        assertEquals(handles, NativeKernel.liveHandles());
    }

    @Test
    void signedArcAndExactBezierExtrusionsUseKernelCurvesRatherThanPolygons() {
        int handles = NativeKernel.liveHandles();
        for (double sweep : new double[] {Math.PI, -Math.PI}) {
            var arc = Sketches.entity("arc", Kind.ARC, false, 0, 0, 10, 0, sweep);
            var chord = Sketches.entity("chord", Kind.LINE, false, -10, 0, 10, 0);
            var g = new SketchGeometry(List.of(arc, chord), List.of(), List.of("arc", "chord"));
            try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 12, -3)) {
                assertEquals(Math.PI * 100 / 2 * 3, solid.volume(), 0.001);
                assertEquals(9, solid.bounds().minZ(), 1e-7);
                assertEquals(12, solid.bounds().maxZ(), 1e-7);
                assertTrue(solid.contains(0, Math.copySign(5, sweep), 10));
            }
        }
        var spline = Sketches.entity("spline", Kind.SPLINE, false, 0, 0, 0, 10, 10, 10, 10, 0);
        var chord = Sketches.entity("chord", Kind.LINE, false, 10, 0, 0, 0);
        var g = new SketchGeometry(List.of(spline, chord), List.of(), List.of("spline", "chord"));
        try (var solid = Solid.profilePrism(SketchProfiles.profile(g), 0, 2)) {
            assertEquals(120, solid.volume(), 1e-6);
            assertEquals(7.5, solid.bounds().maxY(), 1e-6);
            assertTrue(solid.contains(5, 7, 1));
            assertFalse(solid.contains(1, 7, 1));
        }
        assertEquals(handles, NativeKernel.liveHandles());
    }

    @Test
    void constrainedProfileThroughCutTracksFutureThicknessAndPreservesSource() {
        var g = Sketches.rectangle("slot", 20, 10, 10, 5, true);
        var profile = SketchProfiles.profile(g);
        for (double thickness : new double[] {5, 8})
            try (var source = Solid.rectanglePrism(0, 0, 0, 60, 40, thickness);
                    var cut = source.cutThroughProfile(profile)) {
                assertEquals(2400 * thickness, source.volume(), 1e-6);
                assertEquals((2400 - 50) * thickness, cut.volume(), 1e-6);
                assertFalse(cut.contains(25, 12, thickness / 2));
            }
    }

    @Test
    void invalidAndConflictingDefinitionsStayEditableAndNeverPublishGeometry() throws Exception {
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        var wrong =
                SketchConstraints.create(
                        g, "conflict", Type.LENGTH, List.of(g.entities().getFirst().ref()), 80);
        var broken =
                new CadDocument(
                        "document",
                        List.of(
                                sketch(g.add(wrong)),
                                new Feature.Extrude(
                                        "extrude", new Feature.ProfileRef("sketch", "profile"), 5)),
                        2);
        var file = directory.resolve("repairable constraints.jcad");
        DocumentStore.save(broken, file, false);
        assertEquals(broken, DocumentStore.load(file));
        try (var result = Evaluator.evaluate(DocumentStore.load(file))) {
            assertThrows(IllegalStateException.class, result::requireCompleteDocument);
            assertTrue(
                    result.diagnostics().stream()
                            .anyMatch(
                                    d ->
                                            d.featureId().equals("sketch")
                                                    && d.message().contains("conflict")));
        }
        var session = new ModelSession(broken);
        session.replace(sketch(g));
        assertEquals(12000, volume(session), 1e-6);
        int handles = NativeKernel.liveHandles();
        var open = g.withBoundary(List.of(g.entities().getFirst().id()));
        assertThrows(IllegalArgumentException.class, () -> SketchProfiles.profile(open));
        var other = Sketches.entity("separate", Kind.CIRCLE, false, 100, 100, 4);
        var disjoint =
                g.add(other)
                        .withBoundary(
                                List.of(
                                        other.id(),
                                        g.entities().get(0).id(),
                                        g.entities().get(1).id(),
                                        g.entities().get(2).id(),
                                        g.entities().get(3).id()));
        assertThrows(IllegalArgumentException.class, () -> SketchProfiles.profile(disjoint));
        assertEquals(handles, NativeKernel.liveHandles());
    }
}
