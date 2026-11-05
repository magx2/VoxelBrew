package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;
import static dev.voxelbrew.engine.sketch.SketchTools.Tool.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class SketchToolsTest {
    private SketchGeometry draw(SketchTools.Tool tool, double... xy) {
        var points = new ArrayList<Vec>();
        for (int i = 0; i < xy.length; i += 2) points.add(new Vec(xy[i], xy[i + 1]));
        var result =
                SketchTools.add(
                        SketchGeometry.empty(),
                        tool,
                        "shape",
                        points,
                        List.of(),
                        SketchTools.Options.defaults());
        assertTrue(SketchSolver.solve(result).solved());
        return result;
    }

    @Test
    void rectangleAndCircleVariantsHonorTheirDefiningPoints() {
        for (var g :
                List.of(
                        draw(RECTANGLE_TWO_POINT, 0, 0, 20, 10),
                        draw(RECTANGLE_CENTER, 10, 5, 20, 10),
                        draw(RECTANGLE_THREE_POINT, 0, 0, 20, 0, 20, 10))) {
            assertEquals(4, g.entities().size());
            assertTrue(SketchProfiles.hasProfile(g));
            assertEquals(
                    20,
                    g.entities().getFirst().at(0).distance(g.entities().getFirst().at(1)),
                    1e-9);
        }
        for (var g :
                List.of(
                        draw(CIRCLE_CENTER, 0, 0, 10, 0),
                        draw(CIRCLE_TWO_POINT, -10, 0, 10, 0),
                        draw(CIRCLE_THREE_POINT, 10, 0, 0, 10, -10, 0))) {
            assertEquals(10, g.entities().getFirst().value(2), 1e-9);
            assertEquals(0, g.entities().getFirst().point(Part.CENTER).length(), 1e-9);
        }
    }

    @Test
    void tangentCirclesUseNearestValidBranchAndRejectImpossibleRadius() {
        var a = Sketches.entity("a", Kind.LINE, true, 0, 0, 10, 0);
        var b = Sketches.entity("b", Kind.LINE, true, 0, 0, 0, 10);
        var c = Sketches.entity("c", Kind.LINE, true, 10, 0, 0, 10);
        var source = new SketchGeometry(List.of(a, b, c), List.of(), List.of());
        var g =
                SketchTools.add(
                        source,
                        CIRCLE_TWO_TANGENT,
                        "circle",
                        List.of(new Vec(5, 5)),
                        List.of(a.ref(), b.ref()),
                        SketchTools.Options.defaults());
        assertEquals(0, g.entity("circle").point(Part.CENTER).distance(new Vec(5, 5)), 1e-9);
        SketchConstraints.requireSatisfied(g);
        g =
                SketchTools.add(
                        source,
                        CIRCLE_THREE_TANGENT,
                        "circle",
                        List.of(new Vec(2, 2)),
                        List.of(a.ref(), b.ref(), c.ref()),
                        SketchTools.Options.defaults());
        assertEquals(10 / (2 + Math.sqrt(2)), g.entity("circle").value(2), 1e-9);
        SketchConstraints.requireSatisfied(g);
        var parallel = Sketches.entity("parallel", Kind.LINE, true, 0, 10, 10, 10);
        source = new SketchGeometry(List.of(a, parallel), List.of(), List.of());
        g =
                SketchTools.add(
                        source,
                        CIRCLE_TWO_TANGENT,
                        "circle",
                        List.of(new Vec(20, 5)),
                        List.of(a.ref(), parallel.ref()),
                        SketchTools.Options.defaults());
        assertEquals(0, g.entity("circle").point(Part.CENTER).distance(new Vec(20, 5)), 1e-9);
        SketchConstraints.requireSatisfied(g);
        var wrong = Sketches.entity("wrong", Kind.LINE, true, 0, 11, 10, 11);
        var invalid = new SketchGeometry(List.of(a, wrong), List.of(), List.of());
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        SketchTools.add(
                                invalid,
                                CIRCLE_TWO_TANGENT,
                                "circle",
                                List.of(new Vec(5, 5)),
                                List.of(a.ref(), wrong.ref()),
                                SketchTools.Options.defaults()));
    }

    @Test
    void arcVariantsPreserveSignedSweepAndTangentAttachments() {
        var a = draw(ARC_THREE_POINT, 10, 0, -10, 0, 0, -10).entities().getFirst();
        assertEquals(-Math.PI, a.value(4), 1e-9);
        var b = draw(ARC_CENTER, 0, 0, 10, 0, 0, 10).entities().getFirst();
        assertEquals(Math.PI / 2, b.value(4), 1e-9);
        var line = Sketches.entity("line", Kind.LINE, true, -10, 0, 0, 0);
        var source = new SketchGeometry(List.of(line), List.of(), List.of());
        var g =
                SketchTools.add(
                        source,
                        ARC_TANGENT,
                        "arc",
                        List.of(new Vec(10, 10)),
                        List.of(line.ref(Part.END)),
                        SketchTools.Options.defaults());
        SketchConstraints.requireSatisfied(g);
        assertEquals(10, g.entity("arc").value(2), 1e-9);
    }

    @Test
    void polygonModesPreserveTheRadiusApothemOrSelectedEdge() {
        assertEquals(
                10,
                draw(POLYGON_INSCRIBED, 0, 0, 10, 0).entities().getFirst().at(0).length(),
                1e-9);
        var g = draw(POLYGON_CIRCUMSCRIBED, 0, 0, 10, 0);
        assertEquals(10, g.entities().getFirst().at(.5).length(), 1e-9);
        for (double side : new double[] {10, -10}) {
            g = draw(POLYGON_EDGE, 0, 0, 10, 0, 5, side);
            assertEquals(0, g.entities().getFirst().at(0).distance(new Vec(0, 0)), 1e-9);
            assertEquals(0, g.entities().getFirst().at(1).distance(new Vec(10, 0)), 1e-9);
        }
    }

    @Test
    void straightAndCurvedSlotsRemainClosedAndTangent() {
        for (var g :
                List.of(
                        draw(SLOT_CENTER_TO_CENTER, 0, 0, 20, 0, 10, 2),
                        draw(SLOT_CENTER_POINT, 10, 0, 20, 0, 10, 2),
                        draw(SLOT_OVERALL, -2, 0, 22, 0, 10, 2),
                        draw(SLOT_ARC_CENTER, 0, 0, 10, 0, 0, 10, 12, 0),
                        draw(
                                SLOT_ARC_THREE_POINT,
                                10,
                                0,
                                0,
                                10,
                                Math.sqrt(50),
                                Math.sqrt(50),
                                12,
                                0))) {
            assertEquals(4, g.entities().size());
            assertTrue(SketchProfiles.hasProfile(g));
            SketchConstraints.requireSatisfied(g);
        }
        assertThrows(IllegalArgumentException.class, () -> draw(SLOT_OVERALL, 0, 0, 1, 0, 0, 2));
        assertThrows(IllegalArgumentException.class, () -> draw(ELLIPSE, 0, 0, 1, 0, 1, 0));
        assertThrows(
                IllegalArgumentException.class, () -> draw(CIRCLE_THREE_POINT, 0, 0, 1, 0, 2, 0));
    }

    @Test
    void newDrivingDimensionsEditCurvesAndTextWithoutChangingIds() {
        var g = draw(ELLIPSE, 0, 0, 10, 0, 0, 5);
        var ellipse = g.entities().getFirst();
        g =
                g.add(
                        SketchConstraints.create(
                                g, "major", Type.MAJOR_RADIUS, List.of(ellipse.ref()), 10));
        g = Sketches.editDimension(g, "major", 20);
        assertEquals(20, g.entity(ellipse.id()).value(2), 1e-6);
        g = draw(CONIC, 0, 0, 20, 0, 10, 10);
        var conic = g.entities().getFirst();
        g = g.add(SketchConstraints.create(g, "rho", Type.RHO, List.of(conic.ref()), .5));
        g = Sketches.editDimension(g, "rho", .7);
        assertEquals(.7, g.entity(conic.id()).value(6), 1e-6);
        var text = SketchText.create("text", "AB", "SansSerif", 0, 0, 0, 10, 0, 0, "", false);
        g = new SketchGeometry(List.of(text), List.of(), List.of(text.id()));
        g = g.add(SketchConstraints.create(g, "height", Type.TEXT_HEIGHT, List.of(text.ref()), 10));
        assertEquals(12, Sketches.editDimension(g, "height", 12).entity("text").value(2), 1e-6);
    }
}
