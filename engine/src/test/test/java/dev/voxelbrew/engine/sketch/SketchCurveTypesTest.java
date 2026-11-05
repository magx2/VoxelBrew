package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SketchCurveTypesTest {
    private Entity spline(Kind kind, int degree, boolean closed, double... points) {
        return new Entity(
                "spline",
                kind,
                Arrays.stream(points).boxed().toList(),
                false,
                degree,
                closed,
                null);
    }

    @Test
    void ellipseAndRationalConicHaveExactPositionsAndCurvature() {
        var e = Sketches.entity("ellipse", Kind.ELLIPSE, false, 3, 4, 10, 5, Math.PI / 2);
        assertEquals(0, e.at(0).distance(new Vec(3, 14)), 1e-12);
        assertEquals(0.4, e.curvature(0), 1e-12);
        double weight = Math.sqrt(0.5);
        var c =
                Sketches.entity(
                        "conic", Kind.CONIC, false, 10, 0, 10, 10, 0, 10, weight / (1 + weight));
        for (double t : new double[] {0, .2, .5, .8, 1}) {
            assertEquals(10, c.at(t).length(), 1e-10);
            assertEquals(.1, c.curvature(t), 1e-10);
        }
        var reflected =
                Sketches.entity("reflected", Kind.ELLIPSE, false, -3, 4, 10, 5, Math.PI / 2);
        var axis = Sketches.entity("axis", Kind.LINE, true, 0, 0, 0, 20);
        var geometry = new SketchGeometry(List.of(e, reflected, axis), List.of(), List.of());
        var symmetry =
                SketchConstraints.create(
                        geometry,
                        "symmetry",
                        Type.SYMMETRY,
                        List.of(e.ref(), reflected.ref(), axis.ref()),
                        0);
        SketchConstraints.requireSatisfied(geometry.add(symmetry));
    }

    @Test
    void fitSplinesInterpolateAndClosedSplinesJoinWithC2Continuity() {
        var e = spline(Kind.FIT_SPLINE, 0, false, 0, 0, 10, 10, 20, 0, 30, 10);
        for (int i = 0; i < 4; i++)
            assertEquals(0, e.at(i / 3.0).distance(e.point(new Ref(e.id(), Part.FIT, i))), 1e-10);
        var closed = spline(Kind.FIT_SPLINE, 0, true, 0, 0, 10, 0, 10, 10, 0, 10);
        assertEquals(0, closed.at(0).distance(closed.at(1)), 1e-10);
        assertEquals(0, closed.derivative(0).distance(closed.derivative(1)), 1e-10);
        assertEquals(closed.curvature(0), closed.curvature(1), 1e-10);
        assertEquals(new Ref("spline", Part.FIT, 2), Ref.parse("spline.fit[2]"));
        var longId = new Entity("s".repeat(128), e.kind(), e.values(), false);
        assertEquals(e.sample(), longId.sample());
    }

    @Test
    void controlSplinesSupportBothDegreesAndPeriodicClosure() {
        for (int degree : new int[] {3, 5}) {
            var e =
                    spline(
                            Kind.CONTROL_SPLINE,
                            degree,
                            false,
                            0,
                            0,
                            0,
                            10,
                            10,
                            20,
                            20,
                            20,
                            30,
                            10,
                            30,
                            0);
            assertEquals(new Vec(0, 0), e.at(0));
            assertEquals(new Vec(30, 0), e.at(1));
            var closed = new Entity(e.id(), e.kind(), e.values(), false, degree, true, null);
            assertEquals(0, closed.at(0).distance(closed.at(1)), 1e-9);
            assertEquals(0, closed.derivative(0).distance(closed.derivative(1)), 1e-9);
            assertEquals(closed.curvature(0), closed.curvature(1), 1e-9);
        }
    }

    @Test
    void indexedFitAndControlPointsRemainConstrainable() {
        var e = spline(Kind.CONTROL_SPLINE, 3, false, 0, 0, 0, 10, 10, 10, 10, 0);
        var g = new SketchGeometry(List.of(e), List.of(), List.of());
        var ref = new Ref(e.id(), Part.CONTROL, 1);
        var moved = Sketches.drag(g, ref, new Vec(2, 12)).requireSolved();
        assertEquals(0, moved.point(ref).distance(new Vec(2, 12)), 1e-6);
        var fixed = moved.add(SketchConstraints.create(moved, "lock", Type.FIX, List.of(ref), 0));
        assertFalse(Sketches.drag(fixed, ref, new Vec(3, 12)).solved());
    }

    @Test
    void invalidNewCurvesAreRejectedBeforeKernelEvaluation() {
        for (var e :
                List.of(
                        Sketches.entity("ellipse", Kind.ELLIPSE, false, 0, 0, -1, 2, 0),
                        Sketches.entity("conic", Kind.CONIC, false, 0, 0, 1, 1, 2, 0, 1),
                        spline(Kind.FIT_SPLINE, 0, false, 0, 0, 0, 0))) {
            assertFalse(
                    SketchSolver.solve(new SketchGeometry(List.of(e), List.of(), List.of()))
                            .solved());
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> spline(Kind.CONTROL_SPLINE, 5, false, 0, 0, 1, 1, 2, 0));
    }
}
