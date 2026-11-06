package dev.voxelbrew.engine.model;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SketchPlaneTest {
    @TempDir
    Path directory;

    private static ModelSession plate(Feature.Plane plane, double distance) {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("plate", "profile", plane, new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "profile"), distance));
        return session;
    }

    @Test
    void everyOriginPlaneSupportsSignedExtrusionThroughCutsAndPersistence() throws Exception {
        for (var plane : List.of(
                Feature.Plane.origin("XY"),
                Feature.Plane.origin("XZ"),
                Feature.Plane.origin("YZ"),
                new Feature.Plane(
                        "tilted", 4, Transform.translation(11, -5, 7).compose(Transform.rotation(17, 33, 47)), null)))
            for (double distance : new double[] {5, -8}) {
                var session = plate(plane, distance);
                session.add(new Feature.Sketch("hole", "hole.profile", plane, new Feature.Circle(10, 10, 4)));
                session.add(new Feature.ThroughCut(
                        "cut",
                        new Feature.ProfileRef("hole", "hole.profile"),
                        new Feature.BodyRef("thickness", "body")));
                var file = directory.resolve(plane.id() + distance + ".jcad");
                DocumentWriter.save(session.document(), file, false);
                var reopened = new ModelSession(DocumentReader.load(file));
                assertEquals(session.document(), reopened.document());
                try (var result = reopened.evaluate()) {
                    assertTrue(result.valid(), result.diagnostics().toString());
                    var solid = result.bodies().getFirst().solid();
                    assertEquals((2400 - Math.PI * 4) * Math.abs(distance), solid.volume(), .01);
                    var inside = plane.placement().apply(30, 20, distance / 2);
                    var hole = plane.placement().apply(10, 10, distance / 2);
                    assertTrue(solid.contains(inside[0], inside[1], inside[2]));
                    assertFalse(solid.contains(hole[0], hole[1], hole[2]));
                    var bounds = solid.bounds();
                    double[] low = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
                    double[] high = {-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
                    for (double x : new double[] {0, 60})
                        for (double y : new double[] {0, 40})
                            for (double z : new double[] {0, distance}) {
                                var p = plane.placement().apply(x, y, z);
                                for (int a = 0; a < 3; a++) {
                                    low[a] = Math.min(low[a], p[a]);
                                    high[a] = Math.max(high[a], p[a]);
                                }
                            }
                    assertArrayEquals(low, new double[] {bounds.minX(), bounds.minY(), bounds.minZ()}, .001);
                    assertArrayEquals(high, new double[] {bounds.maxX(), bounds.maxY(), bounds.maxZ()}, .001);
                }
            }
    }

    @Test
    void attachmentFollowsThicknessAfterReopenAndCanBeRepairedWithOneUndo() throws Exception {
        var session = plate(Feature.Plane.origin("XY"), 5);
        Feature.Plane attachment;
        try (var result = session.evaluate()) {
            var body = result.bodies().getFirst();
            var faces = body.solid().planarFaces();
            int top = faces.stream()
                    .filter(f -> f.nz() > .99)
                    .findFirst()
                    .orElseThrow()
                    .faceIndex();
            attachment = FaceSupport.attach(body.output(), faces, top);
        }
        session.add(new Feature.Sketch("attached", "attached.profile", attachment, new Feature.Rectangle(5, 5, 10, 8)));
        session.add(new Feature.Extrude("boss", new Feature.ProfileRef("attached", "attached.profile"), 3));
        var file = directory.resolve("attached sketch.jcad");
        DocumentWriter.save(session.document(), file, false);
        session = new ModelSession(DocumentReader.load(file));
        session.editDimension("thickness", "distance", 8);
        try (var result = session.evaluate()) {
            assertTrue(result.valid(), result.diagnostics().toString());
            var boss = result.bodies().stream()
                    .filter(b -> b.id().equals("boss.body"))
                    .findFirst()
                    .orElseThrow()
                    .solid();
            assertEquals(8, boss.bounds().minZ(), .001);
            assertEquals(11, boss.bounds().maxZ(), .001);
            assertEquals(240, boss.volume(), .001);
        }
        var old = (Feature.Sketch) session.document().features().get(2);
        var broken = new FaceSupport(new Feature.BodyRef("missing", "body"), 0, 0, 1, 0, 1);
        session.replace(new Feature.Sketch(
                old.id(),
                old.profileId(),
                new Feature.Plane(attachment.id(), 0, attachment.frame(), broken),
                old.profile()));
        try (var result = session.evaluate()) {
            assertFalse(result.valid());
        }
        var invalid = session.document();
        session.replace(old);
        try (var result = session.evaluate()) {
            assertTrue(result.valid());
        }
        session.undo();
        assertEquals(invalid, session.document());
        session.redo();
        try (var result = session.evaluate()) {
            assertTrue(result.valid());
        }
    }

    @Test
    void exactPlanarFacesMatchMeshFacesAndTransformsKeepSourceImmutable() {
        var frame = Transform.translation(3, -7, 11).compose(Transform.rotation(21, 33, 47));
        try (var source = Solid.circlePrism(0, 0, 0, 20, 7);
                var moved = source.transformed(frame)) {
            assertEquals(0, source.bounds().minZ(), .001);
            assertEquals(7, source.bounds().maxZ(), .001);
            assertEquals(source.volume(), moved.volume(), .001);
            var faces = moved.planarFaces();
            assertEquals(2, faces.size(), "A cylinder side must not be offered as a sketch plane");
            var mesh = moved.tessellate(MeshOptions.DEFAULT);
            for (var face : faces) {
                int triangles = 0;
                for (int t = 0; t < mesh.triangleCount(); t++)
                    if (mesh.faceIndex(t) == face.faceIndex()) {
                        triangles++;
                        for (int c = 0; c < 3; c++) {
                            int v = mesh.vertexIndex(t, c);
                            assertEquals(
                                    face.offset(),
                                    face.nx() * mesh.coordinate(v, 0)
                                            + face.ny() * mesh.coordinate(v, 1)
                                            + face.nz() * mesh.coordinate(v, 2),
                                    1e-7);
                        }
                    }
                assertTrue(triangles > 0);
                var p = face.frame().apply(2, 3, 0);
                assertEquals(face.offset(), face.nx() * p[0] + face.ny() * p[1] + face.nz() * p[2], 1e-9);
            }
            try (var restored = moved.transformed(frame.inverse())) {
                assertEquals(source.volume(), restored.volume(), .001);
                assertEquals(source.bounds().maxX(), restored.bounds().maxX(), .001);
                assertEquals(source.bounds().maxZ(), restored.bounds().maxZ(), .001);
            }
        }
    }
}
