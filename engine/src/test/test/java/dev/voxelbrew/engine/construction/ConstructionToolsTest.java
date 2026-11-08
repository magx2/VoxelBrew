package dev.voxelbrew.engine.construction;

import static dev.voxelbrew.engine.construction.ConstructionGeometry.*;
import static dev.voxelbrew.engine.construction.ConstructionTools.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ConstructionToolsTest {
    private static final Vector O = new Vector(0, 0, 0),
            X = new Vector(1, 0, 0),
            Y = new Vector(0, 1, 0),
            Z = new Vector(0, 0, 1);

    @Test
    void allTwentyOperationsProduceExpectedGeometry() {
        var xy = new Plane(O, Z);
        var yz = new Plane(O, X);
        var zx = new Plane(O, Y);
        var x = new Axis(O, X);
        var y = new Axis(O, Y);
        var path = new Path() {
            public Vector position(double t) {
                return new Vector(10 * t, 0, 0);
            }

            public Vector tangent(double t) {
                return X;
            }
        };
        assertEquals(20, Tool.values().length);
        assertArrayEquals(
                new double[] {1, 2, 3},
                coordinateSystem(new Vector(1, 2, 3), X, Y).frame().apply(0, 0, 0),
                1e-9);
        assertEquals(new Vector(0, 0, 8), offsetPlane(xy, 8).origin());
        assertEquals(1, Math.abs(angledPlane(xy, x, 90).normal().dot(Y)), 1e-9);
        assertEquals(Z, tangentPlane(O, Z).normal());
        assertEquals(
                new Vector(0, 0, 4), midplane(xy, offsetPlane(xy, 8), false).origin());
        assertEquals(X, perpendicularPlane(x, O).normal());
        assertEquals(Z, planeTwoEdges(x, y).normal());
        assertEquals(Z, planeThreePoints(O, X, Y).normal());
        assertEquals(new Vector(5, 0, 0), planeAlongPath(path, .5).origin());
        assertEquals(new Axis(O, Z), axisSurface(O, Z));
        assertEquals(new Axis(O, Z), axisNormal(O, Z));
        assertEquals(1, Math.abs(axisTwoPlanes(xy, yz).direction().dot(Y)), 1e-9);
        assertEquals(x, axisTwoPoints(O, X));
        assertEquals(x, axisEdge(x));
        assertEquals(new Point(X), pointVertex(X));
        assertEquals(new Point(O), pointTwoEdges(x, y));
        assertEquals(new Point(O), pointThreePlanes(xy, yz, zx));
        assertEquals(new Point(Y), pointCenter(Y));
        assertEquals(new Point(new Vector(3, 0, 0)), pointEdgePlane(x, offsetPlane(yz, 3)));
        assertEquals(new Point(new Vector(2.5, 0, 0)), pointAlongPath(path, .25));
    }

    @Test
    void ambiguousAndNonfiniteInputsAreRejected() {
        var xy = new Plane(O, Z);
        assertThrows(IllegalArgumentException.class, () -> axisTwoPoints(O, O));
        assertThrows(IllegalArgumentException.class, () -> planeThreePoints(O, X, X.times(2)));
        assertThrows(IllegalArgumentException.class, () -> axisTwoPlanes(xy, offsetPlane(xy, 1)));
        assertThrows(IllegalArgumentException.class, () -> pointTwoEdges(new Axis(O, X), new Axis(Z, Y)));
        assertThrows(IllegalArgumentException.class, () -> planeTwoEdges(new Axis(O, X), new Axis(Z, Y)));
        assertThrows(IllegalArgumentException.class, () -> pointEdgePlane(new Axis(O, X), xy));
        assertThrows(IllegalArgumentException.class, () -> coordinateSystem(O, X, X));
        assertThrows(IllegalArgumentException.class, () -> offsetPlane(xy, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> angledPlane(xy, new Axis(Z, X), 45));
    }
}
