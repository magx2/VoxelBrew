package dev.voxelbrew.gui;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.sketch.SketchGeometry;
import java.util.List;
import org.junit.jupiter.api.Test;

class SketchDimensionsTest {
    @Test
    void standalonePointsAndEndpointsSupportTruePointToPointDistance() {
        var a = new Entity("a", Kind.POINT, List.of(10., 10.), false);
        var b = new Entity("b", Kind.POINT, List.of(40., 50.), false);
        var g = new SketchGeometry(List.of(a, b), List.of(), List.of());
        var refs = SketchDimensions.references(g, List.of(a.ref(), b.ref()));
        assertEquals(List.of(a.ref(Part.POINT), b.ref(Part.POINT)), refs);
        var measured = SketchDimensions.measure(g, List.of(a.ref(), b.ref()), new Vec(25, 30));
        assertEquals(new SketchDimensions.Measurement(Type.DISTANCE, 50), measured);
        var c = dev.voxelbrew.engine.sketch.SketchConstraints.create(g, "distance", measured.type(), refs, 60);
        var solved = dev.voxelbrew.engine.sketch.SketchSolver.solve(g.add(c));
        assertTrue(solved.solved());
        assertEquals(
                60,
                solved.geometry().point(refs.get(0)).distance(solved.geometry().point(refs.get(1))),
                1e-6);
    }

    @Test
    void parallelLinesMeasureSeparationInsteadOfAnAngle() {
        var a = new Entity("a", Kind.LINE, List.of(0., 0., 0., 40.), false);
        var b = new Entity("b", Kind.LINE, List.of(60., 40., 60., 0.), false);
        var g = new SketchGeometry(List.of(a, b), List.of(), List.of());
        assertEquals(
                new SketchDimensions.Measurement(Type.DISTANCE, 60),
                SketchDimensions.measure(g, List.of(a.ref(), b.ref()), new Vec(30, -10)));
    }

    @Test
    void inferredDiameterDrivesTheRealSketchSolver() {
        var circle = new Entity("circle", Kind.CIRCLE, List.of(10., 10., 3.), false);
        var g = new SketchGeometry(List.of(circle), List.of(), List.of());
        var measurement = SketchDimensions.measure(g, List.of(circle.ref()), new Vec(20, 20));
        var constraint = dev.voxelbrew.engine.sketch.SketchConstraints.create(
                g, "diameter", measurement.type(), List.of(circle.ref()), 12);
        var solved = dev.voxelbrew.engine.sketch.SketchSolver.solve(g.add(constraint));
        assertTrue(solved.solved(), solved.problems().toString());
        assertEquals(6, solved.geometry().entity("circle").value(2), 1e-6);
    }

    @Test
    void infersArcRadiusAndDirectedAngle() {
        var arc = new Entity("arc", Kind.ARC, List.of(0., 0., 5., 0., Math.PI), false);
        var a = new Entity("a", Kind.LINE, List.of(0., 0., 10., 0.), false);
        var b = new Entity("b", Kind.LINE, List.of(0., 0., 0., 10.), false);
        var g = new SketchGeometry(List.of(arc, a, b), List.of(), List.of());
        assertEquals(
                new SketchDimensions.Measurement(Type.RADIUS, 5),
                SketchDimensions.measure(g, List.of(arc.ref()), new Vec(20, 20)));
        assertEquals(
                new SketchDimensions.Measurement(Type.ANGLE, 90),
                SketchDimensions.measure(g, List.of(a.ref(), b.ref()), new Vec(20, 20)));
    }

    @Test
    void infersLineCircleAndSignedPointMeasurements() {
        var line = new Entity("line", Kind.LINE, List.of(0., 0., 30., 40.), false);
        var circle = new Entity("circle", Kind.CIRCLE, List.of(10., 10., 3.), false);
        var g = new SketchGeometry(List.of(line, circle), List.of(), List.of());
        assertEquals(
                new SketchDimensions.Measurement(Type.LENGTH, 50),
                SketchDimensions.measure(g, List.of(line.ref()), new Vec(20, 60)));
        assertEquals(
                new SketchDimensions.Measurement(Type.DIAMETER, 6),
                SketchDimensions.measure(g, List.of(circle.ref()), new Vec(20, 60)));
        var refs = List.of(line.ref(Part.END), line.ref(Part.START));
        assertEquals(
                new SketchDimensions.Measurement(Type.DISTANCE_X, -30),
                SketchDimensions.measure(g, refs, new Vec(15, 60)));
        assertEquals(
                new SketchDimensions.Measurement(Type.DISTANCE_Y, -40),
                SketchDimensions.measure(g, refs, new Vec(50, 20)));
        assertThrows(IllegalArgumentException.class, () -> SketchDimensions.measure(g, List.of(), new Vec(0, 0)));
    }
}
