package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.export.*;
import dev.voxelbrew.engine.mesh.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Element;

class ManufacturingTest {
    @TempDir Path directory;

    @Test
    void preflightReportsRotatedBedBoundsExcludedZonesOverlapHeightAndBrokenAttachments() {
        var s = PrintingTest.reference();
        var base = ManufacturingModel.evaluate(s.document(), MeshOptions.DEFAULT);
        var printer = base.profile().printer();
        var profile =
                new PrintProfile(
                        new PrintProfile.Printer(
                                printer.id(),
                                printer.name(),
                                .4,
                                printer.bed(),
                                List.of(
                                        List.of(
                                                new PrintProfile.Point(10, 10),
                                                new PrintProfile.Point(25, 10),
                                                new PrintProfile.Point(25, 25),
                                                new PrintProfile.Point(10, 25))),
                                200,
                                "marlin2",
                                "gcode"),
                        base.profile().material(),
                        base.profile().process());
        var setup =
                base.setup()
                        .withInstances(
                                List.of(
                                        new ModelView.Instance(
                                                "rotated",
                                                "thickness.body",
                                                Transform.translation(20, 20, 0)
                                                        .compose(Transform.rotation(0, 0, 90))),
                                        new ModelView.Instance(
                                                "tall",
                                                "thickness.body",
                                                Transform.translation(20, 20, 198)),
                                        new ModelView.Instance(
                                                "overlap",
                                                "thickness.body",
                                                Transform.translation(0, 20, 0))))
                        .withSupports(
                                List.of(
                                        new SupportVolume(
                                                "lost",
                                                "missing",
                                                SupportVolume.Role.BLOCKER,
                                                0,
                                                0,
                                                0,
                                                3,
                                                3,
                                                3)));
        var report =
                PrintValidation.validate(
                        new ManufacturingModel("fixture", "rev", setup, profile, base.meshes()));
        for (var expected :
                List.of(
                        "rotated:OUTSIDE_BED",
                        "rotated:EXCLUDED_ZONE",
                        "rotated:OVERLAP",
                        "tall:OVER_HEIGHT",
                        "lost:MISSING_INSTANCE"))
            assertTrue(
                    report.diagnostics().stream()
                            .anyMatch(d -> (d.objectId() + ":" + d.code()).equals(expected)),
                    report.toString());
    }

    @Test
    void meshPreflightNamesOpenAndDegenerateObjectsAndBlocksExport() throws Exception {
        var mesh =
                MeshGeometry.box(
                        new SupportVolume(
                                "box",
                                "part",
                                SupportVolume.Role.SOLID_SUPPORT,
                                0,
                                0,
                                0,
                                10,
                                10,
                                10));
        var open =
                new MeshData(
                        mesh.vertices(),
                        Arrays.copyOf(mesh.triangles(), 33),
                        Arrays.copyOf(mesh.faceIndices(), 11));
        var triangles = mesh.triangles();
        triangles[0] = triangles[1];
        var degenerate = new MeshData(mesh.vertices(), triangles, mesh.faceIndices());
        var setup =
                PrintSetup.empty()
                        .withInstances(
                                List.of(
                                        new ModelView.Instance("open", "open", Transform.IDENTITY),
                                        new ModelView.Instance(
                                                "degenerate",
                                                "degenerate",
                                                Transform.translation(20, 0, 0))));
        var model =
                new ManufacturingModel(
                        "mesh",
                        "rev",
                        setup,
                        PrintProfile.resolve(setup),
                        Map.of("open", open, "degenerate", degenerate));
        var report = PrintValidation.validate(model);
        assertEquals(3, report.meshes().get("open").boundaryEdges());
        assertEquals(1, report.meshes().get("degenerate").degenerateTriangles());
        try (var registry = new ExporterRegistry(directory)) {
            Path file = directory.resolve("bad.stl");
            Files.writeString(file, "original");
            assertThrows(
                    java.io.IOException.class,
                    () ->
                            ExportService.export(
                                    registry,
                                    "stl",
                                    ModelViews.selectInstance(model, "open"),
                                    MeshOptions.DEFAULT,
                                    Map.of(),
                                    file,
                                    true));
            assertEquals("original", Files.readString(file));
        }
    }

    @Test
    void layOnEachPlanarFaceAndSupportExchangeApplyPlacementExactlyOnce() throws Exception {
        var session = PrintingTest.reference();
        var original = ManufacturingModel.evaluate(session.document(), MeshOptions.DEFAULT);
        var mesh = original.meshes().get("thickness.body");
        int planar = 0;
        for (int face : Arrays.stream(mesh.faceIndices()).distinct().toArray()) {
            try {
                var placed =
                        MeshGeometry.transform(
                                mesh,
                                MeshGeometry.layOnFace(
                                        mesh,
                                        face,
                                        Transform.translation(80, 80, 0)
                                                .compose(Transform.rotation(0, 0, 90))));
                for (int t = 0; t < placed.triangleCount(); t++)
                    if (placed.faceIndex(t) == face)
                        for (int c = 0; c < 3; c++)
                            assertEquals(0, placed.coordinate(placed.vertexIndex(t, c), 2), .001);
                assertEquals(0, MeshGeometry.bounds(placed).minZ(), .001);
                planar++;
            } catch (IllegalArgumentException curved) {
                assertTrue(curved.getMessage().contains("not planar"));
            }
        }
        assertEquals(6, planar);
        session.setPrintSetup(
                session.document()
                        .printSetup()
                        .withSupports(
                                List.of(
                                        new SupportVolume(
                                                "pillar",
                                                "part",
                                                SupportVolume.Role.SOLID_SUPPORT,
                                                15,
                                                5,
                                                0,
                                                4,
                                                4,
                                                7.8))));
        var model = ManufacturingModel.evaluate(session.document(), MeshOptions.DEFAULT);
        assertEquals(1, model.selection(ManufacturingModel.Selection.MODEL).bodies().size());
        assertEquals(1, model.selection(ManufacturingModel.Selection.SUPPORTS).bodies().size());
        try (var registry = new ExporterRegistry(directory)) {
            Path file = directory.resolve("supports.stl");
            ExportService.export(
                    registry,
                    "stl",
                    model.selection(ManufacturingModel.Selection.SUPPORTS),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    file,
                    false);
            var data = ByteBuffer.wrap(Files.readAllBytes(file)).order(ByteOrder.LITTLE_ENDIAN);
            data.position(80);
            assertEquals(12, data.getInt());
            double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
            for (int t = 0; t < 12; t++) {
                data.position(data.position() + 12);
                for (int c = 0; c < 3; c++) {
                    double x = data.getFloat(), y = data.getFloat(), z = data.getFloat();
                    minX = Math.min(minX, x);
                    maxX = Math.max(maxX, x);
                    assertTrue(y >= 35 && y <= 39);
                    assertTrue(z >= 0 && z <= 7.801);
                }
                data.getShort();
            }
            assertEquals(35, minX, .001);
            assertEquals(39, maxX, .001);
            Path packageFile = directory.resolve("assembly.3mf");
            ExportService.export(
                    registry,
                    "3mf",
                    model.selection(ManufacturingModel.Selection.ALL),
                    MeshOptions.DEFAULT,
                    Map.of(),
                    packageFile,
                    false);
            try (var zip = new ZipFile(packageFile.toFile())) {
                var factory = DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                var xml =
                        factory.newDocumentBuilder()
                                .parse(zip.getInputStream(zip.getEntry("3D/3dmodel.model")));
                var objects = xml.getElementsByTagNameNS("*", "object");
                assertEquals(2, objects.getLength());
                assertEquals("solidsupport", ((Element) objects.item(1)).getAttribute("type"));
                var items = xml.getElementsByTagNameNS("*", "item");
                assertEquals(2, items.getLength());
                assertEquals(
                        ((Element) items.item(0)).getAttribute("transform"),
                        ((Element) items.item(1)).getAttribute("transform"));
            }
        }
    }
}
