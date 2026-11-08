package dev.voxelbrew.engine.model;

import static dev.voxelbrew.engine.construction.ConstructionGeometry.*;
import static dev.voxelbrew.engine.construction.ConstructionTools.Tool.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConstructionIntegrationTest {
    private static final Vector ZERO = new Vector(0, 0, 0);

    @Test
    void constructionPlaneDrivesSolidAfterSaveEditUndoAndRollback() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        var offset = new ConstructionFeature(
                "offset", OFFSET_PLANE, List.of(ConstructionFeature.Input.originPlane(2)), 5, false);
        session.add(offset);
        session.add(new Feature.Sketch(
                "sketch",
                "profile",
                new Feature.Plane("support", 0, Transform.IDENTITY, null, "offset"),
                new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("solid", new Feature.ProfileRef("sketch", "profile"), 8));
        session = new ModelSession(DocumentCodec.decode(DocumentCodec.encode(session.document())));
        try (var result = Evaluator.evaluate(session.document())) {
            assertTrue(result.valid(), result.diagnostics().toString());
            assertEquals(19200, result.bodies().getFirst().solid().volume(), .01);
            assertEquals(
                    5, ((Plane) result.construction().get("offset")).origin().z(), 1e-9);
            var sketch = (Feature.Sketch) result.resolvedFeatures().get(1);
            assertEquals(5, sketch.plane().placement().apply(0, 0, 0)[2], 1e-9);
        }
        session.replace(new ConstructionFeature("offset", OFFSET_PLANE, offset.inputs(), 12, false));
        try (var result = Evaluator.evaluate(session.document())) {
            assertTrue(result.valid());
            assertEquals(
                    12,
                    ((Feature.Sketch) result.resolvedFeatures().get(1))
                            .plane()
                            .placement()
                            .apply(0, 0, 0)[2],
                    1e-9);
        }
        session.undo();
        try (var result = Evaluator.evaluate(session.document())) {
            assertEquals(
                    5, ((Plane) result.construction().get("offset")).origin().z(), 1e-9);
        }
        session.setCursor(0);
        try (var result = Evaluator.evaluate(session.document())) {
            assertTrue(result.construction().isEmpty());
            assertTrue(result.bodies().isEmpty());
        }
    }

    @Test
    void analyticCylinderProvidesAxisTangentCenterAndArcLengthPoint() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("circle", "profile", Feature.Plane.origin("XY"), new Feature.Circle(10, 20, 8)));
        session.add(new Feature.Extrude("cylinder", new Feature.ProfileRef("circle", "profile"), 12));
        int side = -1, circle = -1;
        try (var result = Evaluator.evaluate(session.document())) {
            var solid = result.bodies().getFirst().solid();
            int faces = (int) solid.constructionInput(0, -1, .5)[0];
            for (int i = 0; i < faces; i++) if (solid.constructionInput(0, i, .5)[0] == 2) side = i;
            int edges = (int) solid.constructionInput(1, -1, .5)[0];
            for (int i = 0; i < edges; i++)
                if (solid.constructionInput(1, i, .5)[0] == 7) {
                    circle = i;
                    break;
                }
        }
        assertTrue(side >= 0);
        assertTrue(circle >= 0);
        var face = new ConstructionFeature.Input(ConstructionFeature.Selection.FACE, "cylinder", "body", side, ZERO);
        var edge = new ConstructionFeature.Input(ConstructionFeature.Selection.EDGE, "cylinder", "body", circle, ZERO);
        session.add(new ConstructionFeature("axis", AXIS_SURFACE, List.of(face), 0, false));
        session.add(new ConstructionFeature(
                "normal",
                AXIS_NORMAL,
                List.of(
                        face,
                        new ConstructionFeature.Input(
                                ConstructionFeature.Selection.POSITION, "", "", 0, new Vector(14, 20, 6))),
                0,
                false));
        session.add(new ConstructionFeature("tangent", TANGENT_PLANE, List.of(face), .25, false));
        session.add(new ConstructionFeature("center", POINT_CENTER, List.of(edge), 0, false));
        session.add(new ConstructionFeature("path", POINT_ALONG_PATH, List.of(edge), .25, false));
        session.add(new ConstructionFeature("path-plane", PLANE_ALONG_PATH, List.of(edge), .25, false));
        var document = DocumentCodec.decode(DocumentCodec.encode(session.document()));
        assertEquals(session.document(), document);
        try (var result = Evaluator.evaluate(document)) {
            assertTrue(result.valid(), result.diagnostics().toString());
            var axis = (Axis) result.construction().get("axis");
            assertEquals(10, axis.origin().x(), 1e-8);
            assertEquals(20, axis.origin().y(), 1e-8);
            assertEquals(1, Math.abs(axis.direction().z()), 1e-8);
            assertEquals(
                    1, ((Axis) result.construction().get("normal")).direction().x(), 1e-8);
            var point = ((Point) result.construction().get("path")).position();
            var center = ((Point) result.construction().get("center")).position();
            assertEquals(4, point.distance(center), 1e-7);
            var plane = (Plane) result.construction().get("path-plane");
            assertEquals(0, plane.normal().dot(point.minus(center)), 1e-7);
            assertEquals(
                    0, ((Plane) result.construction().get("tangent")).normal().z(), 1e-7);
        }
    }

    @Test
    void filletAndChamferExposeRealTorusAndConeConstructionAxes() {
        try (var cylinder = Solid.circlePrism(10, 20, 0, 12, 10)) {
            int edge = -1;
            for (int i = 0; i < cylinder.constructionInput(1, -1, .5)[0]; i++)
                if (cylinder.constructionInput(1, i, .5)[0] == 7) {
                    edge = i + 1;
                    break;
                }
            assertTrue(edge > 0);
            for (boolean fillet : List.of(false, true)) {
                try (var finished = cylinder.edgeFinish(fillet, 1, new int[] {edge})) {
                    int face = -1;
                    for (int i = 0; i < finished.constructionInput(0, -1, .5)[0]; i++)
                        if (finished.constructionInput(0, i, .5)[0] == (fillet ? 5 : 3)) {
                            face = i;
                            break;
                        }
                    assertTrue(face >= 0, "Expected analytic " + (fillet ? "torus" : "cone"));
                    var input = new ConstructionFeature.Input(
                            ConstructionFeature.Selection.FACE, "body", "body", face, ZERO);
                    var feature = new ConstructionFeature("axis", AXIS_SURFACE, List.of(input), 0, false);
                    var axis = (Axis) dev.voxelbrew.engine.construction.ConstructionEvaluator.evaluate(
                            feature, java.util.Map.of(), ref -> finished);
                    assertEquals(10, axis.origin().x(), 1e-8);
                    assertEquals(20, axis.origin().y(), 1e-8);
                    assertEquals(1, Math.abs(axis.direction().z()), 1e-8);
                    if (fillet) {
                        var center = new ConstructionFeature("center", POINT_CENTER, List.of(input), 0, false);
                        var point = (Point) dev.voxelbrew.engine.construction.ConstructionEvaluator.evaluate(
                                center, java.util.Map.of(), ref -> finished);
                        assertEquals(axis.origin(), point.position());
                    }
                }
            }
        }
    }

    @Test
    void offsetFromReversedFaceFollowsItsOutwardNormal() {
        var session = new ModelSession(CadDocument.empty());
        session.add(
                new Feature.Sketch("base", "profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 10, 20)));
        session.add(new Feature.Extrude("body", new Feature.ProfileRef("base", "profile"), 5));
        try (var result = session.evaluate()) {
            var solid = result.bodies().getFirst().solid();
            int bottom = -1;
            for (int face = 0; face < solid.constructionInput(0, -1, .5)[0]; face++) {
                var packet = solid.constructionInput(0, face, .5);
                if (packet[0] == 1 && packet[15] < -.99) bottom = face;
            }
            assertTrue(bottom >= 0);
            var feature = new ConstructionFeature(
                    "offset",
                    OFFSET_PLANE,
                    List.of(new ConstructionFeature.Input(
                            ConstructionFeature.Selection.FACE, "body", "body", bottom, ZERO)),
                    3,
                    false);
            var plane = (Plane) dev.voxelbrew.engine.construction.ConstructionEvaluator.evaluate(
                    feature, java.util.Map.of(), ref -> solid);
            assertEquals(-1, plane.normal().z(), 1e-9);
            assertEquals(-3, plane.origin().z(), 1e-9);
        }
    }

    @Test
    void missingReferenceFailsAndBlocksDependentsWithoutPublishingGeometry() {
        var session = new ModelSession(CadDocument.empty());
        session.add(new ConstructionFeature(
                "broken",
                OFFSET_PLANE,
                List.of(new ConstructionFeature.Input(ConstructionFeature.Selection.DATUM, "missing", "", 0, ZERO)),
                4,
                false));
        session.add(new ConstructionFeature(
                "dependent",
                OFFSET_PLANE,
                List.of(new ConstructionFeature.Input(ConstructionFeature.Selection.DATUM, "broken", "", 0, ZERO)),
                2,
                false));
        try (var result = Evaluator.evaluate(session.document())) {
            assertFalse(result.valid());
            assertEquals(Evaluation.State.FAILED, result.diagnostics().get(0).state());
            assertEquals(Evaluation.State.BLOCKED, result.diagnostics().get(1).state());
            assertThrows(IllegalStateException.class, result::construction);
        }
    }
}
