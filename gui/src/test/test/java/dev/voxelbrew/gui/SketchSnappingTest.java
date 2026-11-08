package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.sketch.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchSnappingTest {
    private static SketchGeometry geometry(Entity... entities) {
        return new SketchGeometry(List.of(entities), List.of(), List.of());
    }

    @Test
    void pointsWinOverCurvesAndToleranceUsesScreenPixels() {
        var line = Sketches.entity("line", Kind.LINE, false, 10, 10, 30, 10);
        var g = geometry(line);
        var snap = SketchSnapping.find(g, new Vec(10.5, 10.1), 10);
        assertEquals(line.ref(Part.START), snap.ref());
        assertEquals(new Vec(10, 10), snap.position());
        assertEquals(line.ref(), SketchSnapping.find(g, new Vec(20, 10.5), 10).ref());
        assertNull(SketchSnapping.find(g, new Vec(20, 10.5), 20));
        assertEquals(ORIGIN, SketchSnapping.find(g, new Vec(.1, .1), 10).ref());
        assertNull(SketchSnapping.find(g, new Vec(35, 10), 10));
    }

    @Test
    void curveSnapsLieOnExactGeometryAndProduceSatisfiedConstraints() {
        var curves = List.of(
                Sketches.entity("circle", Kind.CIRCLE, false, 20, 20, 10),
                Sketches.entity("arc", Kind.ARC, false, 20, 20, 10, .2, 2),
                Sketches.entity("spline", Kind.SPLINE, false, 10, 10, 15, 30, 25, 30, 30, 10),
                Sketches.entity("ellipse", Kind.ELLIPSE, false, 20, 20, 10, 5, .2));
        for (var curve : curves) {
            var expected = curve.at(.37);
            var snap = SketchSnapping.find(geometry(curve), expected, 100);
            assertNotNull(snap, curve.kind().name());
            assertEquals(curve.ref(), snap.ref());
            assertTrue(expected.distance(snap.position()) < 1e-7);
            var p = snap.position();
            var line = Sketches.entity("new", Kind.LINE, false, 40, 40, p.x(), p.y());
            var g = geometry(curve, line);
            g = g.add(SketchConstraints.create(g, "snap", Type.COINCIDENT, List.of(line.ref(Part.END), snap.ref()), 0));
            assertTrue(SketchSolver.solve(g).solved(), curve.kind().name());
        }
    }

    @Test
    void finiteArcsDoNotSnapToTheirInvisibleExtension() {
        var arc = Sketches.entity("arc", Kind.ARC, false, 20, 20, 10, 0, Math.PI / 2);
        assertNull(SketchSnapping.find(geometry(arc), new Vec(10, 20), 10));
    }
}
