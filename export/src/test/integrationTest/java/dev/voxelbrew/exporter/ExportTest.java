package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.api.ExporterDescriptor;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class ExportTest {
    @TempDir
    Path directory;

    static CadDocument reference() {
        return new CadDocument(
                "reference",
                List.of(
                        new Feature.Sketch(
                                "plate",
                                "plate.profile",
                                new Feature.Plane("origin-xy", 0),
                                new Feature.Rectangle(0, 0, 60, 40)),
                        new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 8),
                        new Feature.Sketch(
                                "hole",
                                "hole.profile",
                                new Feature.Plane("origin-xy", 0),
                                new Feature.Circle(10, 10, 4)),
                        new Feature.ThroughCut(
                                "cut",
                                new Feature.ProfileRef("hole", "hole.profile"),
                                new Feature.BodyRef("thickness", "body"))),
                4);
    }

    @Test
    void tessellationIsDetachedClosedAndDoesNotModifySource() {
        MeshData detached;
        try (var plate = Solid.rectanglePrism(0, 0, 0, 60, 40, 8);
                var cut = plate.cutThroughCircle(10, 10, 4)) {
            double volume = cut.volume();
            var bounds = cut.bounds();
            detached = cut.tessellate(MeshOptions.DEFAULT);
            assertEquals(volume, cut.volume(), 1e-10);
            assertEquals(bounds, cut.bounds());
            assertEquals(19200, plate.volume(), 1e-9);
            var coarse = cut.tessellate(new MeshOptions(0.1, 20));
            assertTrue(detached.triangleCount() > coarse.triangleCount());
        }
        MeshValidation.requireClosed(detached);
        assertTrue(Arrays.stream(detached.faceIndices()).distinct().count() >= 7);
        var original = detached.coordinate(0, 0);
        var vertices = detached.vertices();
        vertices[0] = 999;
        assertEquals(original, detached.coordinate(0, 0));
        verifyTriangles(triangles(detached), 0, 0, 0);
    }

    @Test
    void stlIsIndependentlyReadAndPlacementAppliedExactlyOnce() throws Exception {
        try (var evaluation = Evaluator.evaluate(reference());
                var registry = new ExporterRegistry(directory)) {
            var view = ModelViews.placed(
                    evaluation,
                    List.of(new BodyInstance("translated", "thickness.body", Transform.translation(25, -7, 3))));
            var destination = directory.resolve("reference.stl");
            FormatTestSupport.write(registry, "stl", view, MeshOptions.DEFAULT, Map.of(), destination, false);
            byte[] bytes = Files.readAllBytes(destination);
            var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            buffer.position(80);
            int count = buffer.getInt();
            assertEquals(84 + 50 * count, bytes.length);
            var triangles = new ArrayList<double[][]>();
            for (int i = 0; i < count; i++) {
                double[] normal = {buffer.getFloat(), buffer.getFloat(), buffer.getFloat()};
                var points = new double[3][3];
                for (var point : points) for (int axis = 0; axis < 3; axis++) point[axis] = buffer.getFloat();
                assertEquals(0, buffer.getShort());
                var cross = cross(points);
                assertTrue(dot(normal, cross) > 0, "outward normal agrees with winding");
                triangles.add(points);
            }
            verifyTriangles(triangles, 25, -7, 3);
        }
    }

    @Test
    void threeMfPackageHasSharedResourcesUnitsAndIndependentInstanceTransforms() throws Exception {
        try (var evaluation = Evaluator.evaluate(reference());
                var registry = new ExporterRegistry(directory)) {
            var view = ModelViews.placed(
                    evaluation,
                    List.of(
                            new BodyInstance("first", "thickness.body", Transform.IDENTITY),
                            new BodyInstance(
                                    "second",
                                    "thickness.body",
                                    new Transform(0, -1, 0, 100, 1, 0, 0, 20, 0, 0, 1, 3))));
            Path destination = directory.resolve("reference.3mf");
            FormatTestSupport.write(registry, "3mf", view, MeshOptions.DEFAULT, Map.of(), destination, false);
            try (var zip = new ZipFile(destination.toFile())) {
                assertNotNull(zip.getEntry("[Content_Types].xml"));
                assertNotNull(zip.getEntry("_rels/.rels"));
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                var builder = factory.newDocumentBuilder();
                var rels = builder.parse(zip.getInputStream(zip.getEntry("_rels/.rels")));
                var relationship = (Element)
                        rels.getElementsByTagNameNS("*", "Relationship").item(0);
                assertEquals("/3D/3dmodel.model", relationship.getAttribute("Target"));
                var model = builder.parse(zip.getInputStream(zip.getEntry("3D/3dmodel.model")));
                assertEquals("millimeter", model.getDocumentElement().getAttribute("unit"));
                assertEquals(1, model.getElementsByTagNameNS("*", "object").getLength());
                assertEquals(
                        "thickness.body",
                        ((Element) model.getElementsByTagNameNS("*", "object").item(0)).getAttribute("name"));
                var vertexNodes = model.getElementsByTagNameNS("*", "vertex");
                var points = new ArrayList<double[]>();
                for (int i = 0; i < vertexNodes.getLength(); i++) {
                    var v = (Element) vertexNodes.item(i);
                    points.add(new double[] {
                        Double.parseDouble(v.getAttribute("x")),
                        Double.parseDouble(v.getAttribute("y")),
                        Double.parseDouble(v.getAttribute("z"))
                    });
                }
                var triangleNodes = model.getElementsByTagNameNS("*", "triangle");
                var triangles = new ArrayList<double[][]>();
                for (int i = 0; i < triangleNodes.getLength(); i++) {
                    var t = (Element) triangleNodes.item(i);
                    triangles.add(new double[][] {
                        points.get(Integer.parseInt(t.getAttribute("v1"))),
                        points.get(Integer.parseInt(t.getAttribute("v2"))),
                        points.get(Integer.parseInt(t.getAttribute("v3")))
                    });
                }
                verifyTriangles(triangles, 0, 0, 0);
                var items = model.getElementsByTagNameNS("*", "item");
                assertEquals(2, items.getLength());
                for (int i = 0; i < items.getLength(); i++)
                    assertEquals("1", ((Element) items.item(i)).getAttribute("objectid"));
                double[] m = Arrays.stream(((Element) items.item(1))
                                .getAttribute("transform")
                                .split(" "))
                        .mapToDouble(Double::parseDouble)
                        .toArray();
                assertEquals(12, m.length);
                double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY},
                        max = {-1e99, -1e99, -1e99};
                for (var p : points)
                    for (int axis = 0; axis < 3; axis++) {
                        double value = p[0] * m[axis] + p[1] * m[3 + axis] + p[2] * m[6 + axis] + m[9 + axis];
                        min[axis] = Math.min(min[axis], value);
                        max[axis] = Math.max(max[axis], value);
                    }
                assertArrayEquals(new double[] {60, 20, 3}, min, 0.001);
                assertArrayEquals(new double[] {100, 80, 11}, max, 0.001);
            }
        }
    }

    @Test
    void independentConsumerLoadsAndFailuresPreserveDestination() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins with spaces"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("external provider.jar"));
        try (var registry = new ExporterRegistry(plugins)) {
            assertEquals(
                    List.of("3mf", "document-probe", "failing", "json", "obj", "prusa-project", "step", "stl", "vxb"),
                    registry.descriptors().stream().map(ExporterDescriptor::id).toList());
            assertTrue(registry.diagnostics().stream().anyMatch(s -> s.contains("Duplicate")));
            assertTrue(registry.diagnostics().stream().anyMatch(s -> s.contains("Incompatible")));
            assertTrue(registry.diagnostics().stream().anyMatch(s -> s.contains("DoesNotExist")));
            var doc = reference();
            Path target = directory.resolve("report.txt");
            registry.require("document-probe")
                    .provider()
                    .create(Runnable::run)
                    .export(doc, dev.voxelbrew.exporter.api.FileExportDialog.destination(target, false))
                    .get();
            assertEquals("document=" + doc.id(), Files.readString(target));
            byte[] expected = Files.readAllBytes(target);
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> registry.require("failing")
                            .provider()
                            .create(Runnable::run)
                            .export(doc, dev.voxelbrew.exporter.api.FileExportDialog.destination(target, true))
                            .get());
            assertArrayEquals(expected, Files.readAllBytes(target));
            assertThrows(
                    java.util.concurrent.ExecutionException.class,
                    () -> registry.require("document-probe")
                            .provider()
                            .create(Runnable::run)
                            .export(doc, dev.voxelbrew.exporter.api.FileExportDialog.destination(target, false))
                            .get());
            assertArrayEquals(expected, Files.readAllBytes(target));
        }
    }

    @Test
    void incompleteDocumentsAreRejected() {
        var doc = reference();
        try (var evaluation = Evaluator.evaluate(new CadDocument(doc.id(), doc.features(), 3))) {
            assertThrows(IllegalStateException.class, () -> ModelViews.from(evaluation));
        }
        try (var evaluation = Evaluator.evaluate(new CadDocument(
                "bad", List.of(new Feature.Extrude("bad", new Feature.ProfileRef("missing", "p"), 8)), 1))) {
            assertThrows(IllegalStateException.class, () -> ModelViews.from(evaluation));
        }
    }

    private static List<double[][]> triangles(MeshData mesh) {
        var result = new ArrayList<double[][]>();
        for (int t = 0; t < mesh.triangleCount(); t++) {
            double[][] points = new double[3][3];
            for (int c = 0; c < 3; c++)
                for (int a = 0; a < 3; a++) points[c][a] = mesh.coordinate(mesh.vertexIndex(t, c), a);
            result.add(points);
        }
        return result;
    }

    /** Reader-side checks deliberately do not call production preflight or geometry exporters. */
    private static void verifyTriangles(List<double[][]> triangles, double dx, double dy, double dz) {
        double[] min = {1e99, 1e99, 1e99}, max = {-1e99, -1e99, -1e99};
        double volume = 0;
        int holeSegments = 0;
        Map<String, Integer> directed = new HashMap<>();
        Map<String, Integer> undirected = new HashMap<>();
        for (var world : triangles) {
            double[][] p = new double[3][3];
            for (int c = 0; c < 3; c++)
                for (int a = 0; a < 3; a++) {
                    assertTrue(Double.isFinite(world[c][a]));
                    p[c][a] = world[c][a] - new double[] {dx, dy, dz}[a];
                    min[a] = Math.min(min[a], p[c][a]);
                    max[a] = Math.max(max[a], p[c][a]);
                }
            double[] cross = cross(p);
            assertTrue(dot(cross, cross) > 1e-20);
            volume += dot(p[0], new double[] {
                        p[1][1] * p[2][2] - p[1][2] * p[2][1],
                        p[1][2] * p[2][0] - p[1][0] * p[2][2],
                        p[1][0] * p[2][1] - p[1][1] * p[2][0]
                    })
                    / 6;
            var section = new ArrayList<double[]>();
            for (int c = 0; c < 3; c++) {
                var a = p[c];
                var b = p[(c + 1) % 3];
                String ka = key(a), kb = key(b);
                String edge = ka.compareTo(kb) < 0 ? ka + "/" + kb : kb + "/" + ka;
                undirected.merge(edge, 1, Integer::sum);
                directed.merge(edge, ka.compareTo(kb) < 0 ? 1 : -1, Integer::sum);
                if ((a[2] < 4 && b[2] > 4) || (a[2] > 4 && b[2] < 4)) {
                    double f = (4 - a[2]) / (b[2] - a[2]);
                    section.add(new double[] {a[0] + f * (b[0] - a[0]), a[1] + f * (b[1] - a[1])});
                }
            }
            if (section.size() == 2 && section.stream().allMatch(q -> Math.hypot(q[0] - 10, q[1] - 10) < 3)) {
                holeSegments++;
                for (double fraction : new double[] {0, 0.5, 1}) {
                    double x = section.get(0)[0] * (1 - fraction) + section.get(1)[0] * fraction,
                            y = section.get(0)[1] * (1 - fraction) + section.get(1)[1] * fraction;
                    assertEquals(2, Math.hypot(x - 10, y - 10), 0.021);
                }
            }
        }
        assertArrayEquals(new double[] {0, 0, 0}, min, 0.001);
        assertArrayEquals(new double[] {60, 40, 8}, max, 0.001);
        assertEquals(19099.469035085127, volume, 0.5);
        assertTrue(holeSegments >= 16);
        assertTrue(undirected.values().stream().allMatch(n -> n == 2));
        assertTrue(directed.values().stream().allMatch(n -> n == 0));
    }

    private static String key(double[] p) {
        return Math.round(p[0] * 1e6) + "," + Math.round(p[1] * 1e6) + "," + Math.round(p[2] * 1e6);
    }

    private static double[] cross(double[][] p) {
        double[] a = {p[1][0] - p[0][0], p[1][1] - p[0][1], p[1][2] - p[0][2]},
                b = {p[2][0] - p[0][0], p[2][1] - p[0][1], p[2][2] - p[0][2]};
        return new double[] {a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}
