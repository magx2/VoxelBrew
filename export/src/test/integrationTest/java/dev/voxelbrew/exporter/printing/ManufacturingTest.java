package dev.voxelbrew.exporter.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.BodyInstance;
import dev.voxelbrew.engine.model.Transform;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.exporter.ExportService;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.ModelViews;
import dev.voxelbrew.exporter.api.ModelView;
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
    void completeExportAdapterPreservesProfilesSupportsAndDetachedLifetime() throws Exception {
        var session = PrintingTest.reference();
        session.setPrintSetup(
                session.document()
                        .printSetup()
                        .withSupports(
                                List.of(
                                        new SupportVolume(
                                                "block",
                                                "part",
                                                SupportVolume.Role.BLOCKER,
                                                0,
                                                0,
                                                0,
                                                4,
                                                4,
                                                4),
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
        ModelView view;
        try (var evaluation = session.evaluate()) {
            view =
                    new ExportSelection(
                                    ExportSelection.Space.BUILD_PLATE,
                                    ExportSelection.Contents.MODEL,
                                    null,
                                    null)
                            .view(session.document(), evaluation, MeshOptions.DEFAULT);
        }
        // Export after the evaluation closes: the build-plate adapter owns detached meshes.
        try (var registry = new ExporterRegistry(directory)) {
            for (boolean includeSolids : List.of(false, true)) {
                var output = directory.resolve("project-" + includeSolids + ".3mf");
                ExportService.export(
                        registry,
                        "prusa-project",
                        view,
                        MeshOptions.DEFAULT,
                        Map.of("include-support-solids", Boolean.toString(includeSolids)),
                        output,
                        false);
                try (var zip = new ZipFile(output.toFile())) {
                    var xml =
                            DocumentBuilderFactory.newInstance()
                                    .newDocumentBuilder()
                                    .parse(
                                            zip.getInputStream(
                                                    zip.getEntry(
                                                            "Metadata/Slic3r_PE_model.config")));
                    assertEquals(
                            includeSolids ? 3 : 2, xml.getElementsByTagName("volume").getLength());
                    var metadata = xml.getElementsByTagName("metadata");
                    var values = new ArrayList<String>();
                    for (int i = 0; i < metadata.getLength(); i++)
                        values.add(((Element) metadata.item(i)).getAttribute("value"));
                    assertTrue(values.contains("part/thickness.body"));
                    assertTrue(values.contains("part/block"));
                    assertTrue(values.contains("SupportBlocker"));
                    assertEquals(includeSolids, values.contains("part/pillar"));
                    var config =
                            new String(
                                    zip.getInputStream(zip.getEntry("Metadata/Slic3r_PE.config"))
                                            .readAllBytes(),
                                    java.nio.charset.StandardCharsets.UTF_8);
                    assertTrue(config.contains("; filament_type = PLA\n"));
                    assertTrue(config.contains("; nozzle_diameter = 0.4\n"));
                }
            }
        }
    }

    @Test
    void projectExportRejectsFilteredViewsAndPreservesExistingDestination() throws Exception {
        var model =
                ManufacturingModel.evaluate(
                        PrintingTest.reference().document(), MeshOptions.DEFAULT);
        var view = ModelViews.from(model);
        var output = directory.resolve("original.3mf");
        Files.writeString(output, "original project");
        try (var registry = new ExporterRegistry(directory)) {
            for (var filtered :
                    List.of(
                            ModelViews.select(view, "thickness.body"),
                            ModelViews.selectInstance(view, "part"),
                            ModelViews.from(model, ExportSelection.Contents.ALL))) {
                var failure =
                        assertThrows(
                                java.io.IOException.class,
                                () ->
                                        ExportService.export(
                                                registry,
                                                "prusa-project",
                                                filtered,
                                                MeshOptions.DEFAULT,
                                                Map.of(),
                                                output,
                                                true));
                assertTrue(failure.getMessage().contains("complete build-plate setup"));
                assertEquals("original project", Files.readString(output));
            }
        }
        try (var entries = Files.list(directory)) {
            assertEquals(List.of(output), entries.toList());
        }
    }

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
                                        new BodyInstance(
                                                "rotated",
                                                "thickness.body",
                                                Transform.translation(20, 20, 0)
                                                        .compose(Transform.rotation(0, 0, 90))),
                                        new BodyInstance(
                                                "tall",
                                                "thickness.body",
                                                Transform.translation(20, 20, 198)),
                                        new BodyInstance(
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
        var model = new ManufacturingModel("fixture", "rev", setup, profile, base.meshes());
        var report = PrintValidation.validate(model);
        assertSame(report.meshes().get("rotated"), report.meshes().get("tall"));
        assertEquals(report, PrintValidation.validate(model));
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
                                        new BodyInstance("open", "open", Transform.IDENTITY),
                                        new BodyInstance(
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
                                    ModelViews.selectInstance(ModelViews.from(model), "open"),
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
        assertEquals(1, ModelViews.from(model, ExportSelection.Contents.MODEL).bodies().size());
        assertEquals(1, ModelViews.from(model, ExportSelection.Contents.SUPPORTS).bodies().size());
        try (var registry = new ExporterRegistry(directory)) {
            Path file = directory.resolve("supports.stl");
            ExportService.export(
                    registry,
                    "stl",
                    ModelViews.from(model, ExportSelection.Contents.SUPPORTS),
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
                    ModelViews.from(model, ExportSelection.Contents.ALL),
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
