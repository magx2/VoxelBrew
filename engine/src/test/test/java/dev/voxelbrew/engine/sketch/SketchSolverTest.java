package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SketchSolverTest {
    private static Entity line(String id, double x, double y, double xx, double yy) {
        return Sketches.entity(id, Kind.LINE, false, x, y, xx, yy);
    }

    private static Entity circle(String id, double x, double y, double radius) {
        return Sketches.entity(id, Kind.CIRCLE, false, x, y, radius);
    }

    private static SketchGeometry geometry(Entity... entities) {
        return new SketchGeometry(List.of(entities), List.of(), List.of());
    }

    private static SketchGeometry add(SketchGeometry g, String id, Type type, double value, Ref... refs) {
        return g.add(SketchConstraints.create(g, id, type, List.of(refs), value));
    }

    private static SketchGeometry solve(SketchGeometry g) {
        var result = SketchSolver.solve(g);
        assertTrue(result.solved(), result.problems().toString());
        SketchConstraints.requireSatisfied(result.geometry());
        return result.geometry();
    }

    private static void point(Vec actual, double x, double y) {
        assertEquals(x, actual.x(), 1e-6);
        assertEquals(y, actual.y(), 1e-6);
    }

    @Test
    void horizontalVerticalLengthAndEqualProduceDimensionedLines() {
        var a = line("a", 0, 0, 10, 2);
        var b = line("b", 20, 0, 21, 12);
        var g = geometry(a, b);
        g = add(g, "a-fixed", Type.FIX, 0, a.ref(Part.START));
        g = add(g, "b-fixed", Type.FIX, 0, b.ref(Part.START));
        g = add(g, "horizontal", Type.HORIZONTAL, 0, a.ref());
        g = add(g, "vertical", Type.VERTICAL, 0, b.ref());
        g = add(g, "length", Type.LENGTH, 10, a.ref());
        g = add(g, "equal", Type.EQUAL, 0, a.ref(), b.ref());
        var result = SketchSolver.solve(g);
        assertTrue(result.solved(), result.problems().toString());
        assertEquals(0, result.degreesOfFreedom());
        point(result.geometry().entity("a").at(1), 10, 0);
        point(result.geometry().entity("b").at(1), 20, 10);
        var edited = Sketches.editDimension(result.geometry(), "length", 20);
        point(edited.entity("b").at(1), 20, 20);
    }

    @Test
    void parallelPerpendicularAndCollinearKeepTheReferenceLineFixed() {
        var a = line("a", 0, 0, 10, 0);
        var b = line("b", 2, 7, 14, 8);
        var c = line("c", 20, 0, 21, 10);
        var g = add(geometry(a, b, c), "anchor", Type.FIX, 0, a.ref());
        g = add(g, "parallel", Type.PARALLEL, 0, a.ref(), b.ref());
        g = add(g, "perpendicular", Type.PERPENDICULAR, 0, a.ref(), c.ref());
        g = solve(g);
        assertEquals(g.entity("b").at(0).y(), g.entity("b").at(1).y(), 1e-6);
        assertEquals(g.entity("c").at(0).x(), g.entity("c").at(1).x(), 1e-6);
        g = solve(add(g, "collinear", Type.COLLINEAR, 0, a.ref(), b.ref()));
        assertEquals(0, g.entity("b").at(0).y(), 1e-6);
        point(g.entity("a").at(0), 0, 0);
        point(g.entity("a").at(1), 10, 0);
    }

    @Test
    void coincidentMidpointAndSignedPointDimensionsUseTheOrigin() {
        var line = line("line", 0, 0, 20, 0);
        var p = Sketches.entity("point", Kind.POINT, true, 4, 2);
        var g = add(geometry(line, p), "anchor", Type.FIX, 0, line.ref());
        g = solve(add(g, "mid", Type.MIDPOINT, 0, p.ref(Part.POINT), line.ref()));
        point(g.entity("point").at(0), 10, 0);
        var moved = geometry(p);
        moved = add(moved, "x", Type.DISTANCE_X, -4, ORIGIN, p.ref(Part.POINT));
        moved = add(moved, "y", Type.DISTANCE_Y, 7, ORIGIN, p.ref(Part.POINT));
        point(solve(moved).entity("point").at(0), -4, 7);
        var coincident = add(geometry(line, p), "fixed", Type.FIX, 0, line.ref());
        coincident = solve(add(coincident, "on-line", Type.COINCIDENT, 0, p.ref(Part.POINT), line.ref()));
        point(coincident.entity("point").at(0), 4, 0);
        var origin = solve(add(geometry(p), "origin", Type.COINCIDENT, 0, p.ref(Part.POINT), ORIGIN));
        point(origin.entity("point").at(0), 0, 0);
    }

    @Test
    void concentricEqualAndRadiusPropagateToBothCircles() {
        var a = circle("a", 0, 0, 2);
        var b = circle("b", 4, 3, 5);
        var g = add(geometry(a, b), "anchor", Type.FIX, 0, a.ref(Part.CENTER));
        g = add(g, "centers", Type.CONCENTRIC, 0, a.ref(), b.ref());
        g = add(g, "equal", Type.EQUAL, 0, a.ref(), b.ref());
        g = add(g, "radius", Type.RADIUS, 3, a.ref());
        g = solve(g);
        point(g.entity("b").point(Part.CENTER), 0, 0);
        assertEquals(3, g.entity("b").value(2), 1e-6);
        var edited = Sketches.editDimension(g, "radius", 8);
        assertEquals(8, edited.entity("b").value(2), 1e-6);
    }

    @Test
    void lineCircleAndExternalInternalCircleTangenciesUseTheChosenBranch() {
        var a = line("a", -10, 0, 10, 0);
        var c = circle("circle", 0, 5, 2);
        var g = add(geometry(a, c), "anchor", Type.FIX, 0, a.ref());
        g = add(g, "radius", Type.RADIUS, 2, c.ref());
        g = solve(add(g, "tangent", Type.TANGENT, 0, a.ref(), c.ref()));
        assertEquals(2, g.entity("circle").value(1), 1e-6);
        for (double distance : new double[] {13, 7}) {
            var outer = circle("outer", 0, 0, 10);
            var inner = circle("inner", distance, 0, 2);
            var circles = add(geometry(outer, inner), "fixed", Type.FIX, 0, outer.ref());
            circles = add(circles, "radius", Type.RADIUS, 2, inner.ref());
            circles = solve(add(circles, "tangent", Type.TANGENT, 0, outer.ref(), inner.ref()));
            assertEquals(
                    distance > 10 ? 12 : 8,
                    circles.entity("inner").point(Part.CENTER).length(),
                    1e-6);
        }
    }

    @Test
    void symmetryReflectsPointsAndCirclesAcrossAConstructionLine() {
        var axis = line("axis", 0, -10, 0, 10).withConstruction(true);
        var a = circle("a", -7, 3, 2);
        var b = circle("b", 5, 4, 3);
        var g = add(geometry(axis, a, b), "axis-fixed", Type.FIX, 0, axis.ref());
        g = add(g, "a-fixed", Type.FIX, 0, a.ref());
        g = solve(add(g, "symmetry", Type.SYMMETRY, 0, a.ref(), b.ref(), axis.ref()));
        point(g.entity("b").point(Part.CENTER), 7, 3);
        assertEquals(2, g.entity("b").value(2), 1e-6);
    }

    @Test
    void polygonConstraintRestoresRegularityAndPreservesFourRigidSizeDegreesOfFreedom() {
        var g = Sketches.polygon("hex", 0, 0, 10, 6);
        var distorted = g.entity("hex-2");
        var values = new java.util.ArrayList<>(distorted.values());
        values.set(0, values.get(0) + 1.2);
        g = g.replace(distorted.withValues(values));
        var result = SketchSolver.solve(g);
        assertTrue(result.solved(), result.problems().toString());
        assertEquals(4, result.degreesOfFreedom());
        var lines = result.geometry().entities();
        double length = lines.getFirst().at(0).distance(lines.getFirst().at(1));
        for (int i = 0; i < 6; i++) {
            assertEquals(length, lines.get(i).at(0).distance(lines.get(i).at(1)), 1e-6);
            assertEquals(0, lines.get(i).at(1).distance(lines.get((i + 1) % 6).at(0)), 1e-6);
        }
    }

    @Test
    void curvatureMakesACubicSplineJoinAnArcWithG2Continuity() {
        var arc = Sketches.entity("arc", Kind.ARC, false, 0, 0, 10, 0, Math.PI / 2);
        var spline = Sketches.entity("spline", Kind.SPLINE, false, 0.2, 10.1, -3, 10.3, -8, 6, -10, 0);
        var g = add(geometry(arc, spline), "arc-fixed", Type.FIX, 0, arc.ref());
        g = solve(add(g, "smooth", Type.CURVATURE, 0, arc.ref(Part.END), spline.ref(Part.START)));
        var a = g.entity("arc");
        var b = g.entity("spline");
        assertEquals(0, a.at(1).distance(b.at(0)), 1e-6);
        assertEquals(1, a.derivative(1).unit().dot(b.derivative(0).unit()), 1e-6);
        assertEquals(0.1, b.curvature(0), 1e-8);
    }

    @Test
    void tangentCurveEndpointsCoincideAndStaySmooth() {
        var a = line("line", -10, 0, 0, 0);
        var b = Sketches.entity("spline", Kind.SPLINE, false, 0.2, 1, 4, 2, 6, 6, 10, 10);
        var g = add(geometry(a, b), "line-fixed", Type.FIX, 0, a.ref());
        g = solve(add(g, "tangent", Type.TANGENT, 0, a.ref(Part.END), b.ref(Part.START)));
        point(g.entity("spline").at(0), 0, 0);
        assertEquals(0, g.entity("spline").derivative(0).y(), 1e-6);
        assertTrue(g.entity("spline").derivative(0).x() > 0);
    }

    @Test
    void angleAndDistanceDriveALine() {
        var line = line("line", 0, 0, 9, 9);
        var g = add(geometry(line), "anchor", Type.COINCIDENT, 0, line.ref(Part.START), ORIGIN);
        g = add(g, "angle", Type.ANGLE, 30, line.ref());
        g = solve(add(g, "distance", Type.DISTANCE, 20, line.ref(Part.START), line.ref(Part.END)));
        point(g.entity("line").at(1), 10 * Math.sqrt(3), 10);
    }

    @Test
    void conflictsAreDiagnosedWithoutMutatingTheDefinitionAndRemovalRepairsIt() {
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        assertEquals(0, SketchSolver.solve(g).degreesOfFreedom());
        var inconsistent =
                add(g, "wrong-width", Type.LENGTH, 80, g.entities().getFirst().ref());
        var result = SketchSolver.solve(inconsistent);
        assertFalse(result.solved());
        assertTrue(result.problems().stream().anyMatch(p -> p.constraintId().equals("wrong-width")));
        assertEquals(60, g.entities().getFirst().at(1).x());
        assertThrows(IllegalArgumentException.class, result::requireSolved);
        assertTrue(SketchSolver.solve(g).solved());
        var invalid = new Constraint("missing", Type.FIX, List.of(new Ref("missing", Part.START)), List.of(0.0, 0.0));
        assertFalse(SketchSolver.solve(g.add(invalid)).solved());
        assertThrows(
                IllegalArgumentException.class,
                () -> SketchConstraints.create(
                        g, "bad", Type.RADIUS, List.of(g.entities().getFirst().ref()), -1));
    }

    @Test
    void draggingRespectsConstraintsAndFixedGeometry() {
        var g = Sketches.rectangle("plate", 0, 0, 60, 40, false);
        var result = Sketches.drag(g, g.entities().getFirst().ref(Part.START), new Vec(4, 5));
        assertTrue(result.solved(), result.problems().toString());
        point(result.geometry().entities().getFirst().at(0), 4, 5);
        assertEquals(g.constraints(), result.geometry().constraints());
        var fixed = Sketches.rectangle("plate", 0, 0, 60, 40, true);
        assertFalse(Sketches.drag(fixed, fixed.entities().getFirst().ref(Part.START), new Vec(4, 5))
                .solved());
    }
}
