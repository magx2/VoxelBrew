package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.api.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UnifiedExportTest {
    @TempDir
    Path directory;

    @Test
    void prusaProjectUsesSlicerProcessAndPlacementEvenWhenDesignSpaceWasRequested() throws Exception {
        var base = ExportTest.reference();
        var setup = dev.voxelbrew.engine.printing.PrintSetup.empty()
                .withProfile(
                        "prusa-slicer",
                        "generic-marlin-200-0.4",
                        "PETG",
                        new dev.voxelbrew.engine.printing.ProcessSettings(0.25, 4, 35, true, true, 55, 0.3, 4, 6, 2))
                .withInstances(List.of(new dev.voxelbrew.engine.model.BodyInstance(
                        "placed", "thickness.body", dev.voxelbrew.engine.model.Transform.translation(25, 35, 0))));
        var document = new CadDocument(base.id(), base.features(), base.cursor(), setup);
        Path target = directory.resolve("native-project.3mf");
        var parent = new ExportDialogs()
                .register(
                        MeshExportDialog.class,
                        request -> Optional.of(new MeshExportDialog.Settings(
                                ExportSelection.design(null),
                                MeshOptions.DEFAULT,
                                Map.of(),
                                new FileExportDialog.Destination(target, false))));
        try (var registry = new ExporterRegistry(directory)) {
            registry.require("prusa-project")
                    .provider()
                    .create(Runnable::run, directory)
                    .export(document, parent)
                    .get();
        }
        try (var zip = new java.util.zip.ZipFile(target.toFile())) {
            String config;
            try (var input = zip.getInputStream(zip.getEntry("Metadata/Slic3r_PE.config"))) {
                config = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            }
            for (String setting : List.of(
                    "layer_height = 0.25",
                    "perimeters = 4",
                    "fill_density = 35.0%",
                    "support_material = 1",
                    "support_material_buildplate_only = 1",
                    "support_material_threshold = 55.0",
                    "support_material_contact_distance = 0.3",
                    "support_material_interface_layers = 4",
                    "brim_width = 6.0",
                    "raft_layers = 2",
                    "filament_type = PETG")) assertTrue(config.contains("; " + setting + "\n"), setting);
            try (var input = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))) {
                var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
                factory.setNamespaceAware(true);
                var vertices = factory.newDocumentBuilder().parse(input).getElementsByTagNameNS("*", "vertex");
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
                for (int i = 0; i < vertices.getLength(); i++) {
                    var vertex = (org.w3c.dom.Element) vertices.item(i);
                    minX = Math.min(minX, Double.parseDouble(vertex.getAttribute("x")));
                    minY = Math.min(minY, Double.parseDouble(vertex.getAttribute("y")));
                }
                assertEquals(25, minX, 0.001);
                assertEquals(35, minY, 0.001);
            }
        }
    }

    @Test
    void threeMfPrefersSlicerPlateAndCarriesSettingsWhileObjKeepsDesignSpace() throws Exception {
        var base = ExportTest.reference();
        var setup = dev.voxelbrew.engine.printing.PrintSetup.empty()
                .withInstances(List.of(new dev.voxelbrew.engine.model.BodyInstance(
                        "placed", "thickness.body", dev.voxelbrew.engine.model.Transform.translation(25, 35, 7))))
                .withWorkspaceSettings(Map.of("print-output", "custom.gcode"));
        var document = new CadDocument(base.id(), base.features(), base.cursor(), setup);
        Path target = directory.resolve("plate.3mf");
        var parent = new ExportDialogs()
                .register(
                        MeshExportDialog.class,
                        request -> Optional.of(new MeshExportDialog.Settings(
                                ExportSelection.design(null),
                                MeshOptions.DEFAULT,
                                Map.of(),
                                new FileExportDialog.Destination(target, false))));
        new ThreeMfExporter(Runnable::run, directory).export(document, parent).get();
        try (var zip = new java.util.zip.ZipFile(target.toFile());
                var input = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))) {
            var factory = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var xml = factory.newDocumentBuilder().parse(input);
            var item = (org.w3c.dom.Element)
                    xml.getElementsByTagNameNS("*", "item").item(0);
            assertEquals("1.0 0.0 0.0 0.0 1.0 0.0 0.0 0.0 1.0 25.0 35.0 7.0", item.getAttribute("transform"));
            var metadata = xml.getElementsByTagNameNS("*", "metadata").item(0).getTextContent();
            var proto = dev.voxelbrew.engine.persistence.proto.DocumentProto.PrintSetup.newBuilder();
            com.google.protobuf.util.JsonFormat.parser().merge(metadata, proto);
            assertEquals("custom.gcode", proto.getWorkspaceSettingsOrThrow("print-output"));
            assertEquals(25, proto.getInstances(0).getTransform(3));
        }
        Path obj = directory.resolve("design.obj");
        var objParent = new ExportDialogs()
                .register(
                        MeshExportDialog.class,
                        request -> Optional.of(new MeshExportDialog.Settings(
                                ExportSelection.design(null),
                                MeshOptions.DEFAULT,
                                Map.of(),
                                new FileExportDialog.Destination(obj, false))));
        try (var registry = new ExporterRegistry(directory)) {
            registry.require("obj")
                    .provider()
                    .create(Runnable::run, directory)
                    .export(document, objParent)
                    .get();
        }
        var xs = Files.readAllLines(obj).stream()
                .filter(line -> line.startsWith("v "))
                .mapToDouble(line -> Double.parseDouble(line.split("\\s+")[1]))
                .summaryStatistics();
        assertEquals(0, xs.getMin(), 0.001);
        assertEquals(60, xs.getMax(), 0.001);
    }

    @Test
    void builtInGeometryUsesTheSameProviderContractAndRejectsRollback() throws Exception {
        var document = ExportTest.reference();
        try (var registry = new ExporterRegistry(directory)) {
            for (String id : List.of("stl", "3mf", "obj", "step")) {
                Path target = directory.resolve("unified." + id);
                var parent = new ExportDialogs()
                        .register(
                                FileExportDialog.class,
                                request -> Optional.of(new FileExportDialog.Destination(target, true)))
                        .register(
                                MeshExportDialog.class,
                                request -> Optional.of(new MeshExportDialog.Settings(
                                        ExportSelection.design(null),
                                        MeshOptions.DEFAULT,
                                        Map.of(),
                                        new FileExportDialog.Destination(target, true))));
                var exporter = registry.require(id).provider().create(Runnable::run, directory);
                assertEquals(target, exporter.export(document, parent).get());
                byte[] original = Files.readAllBytes(target);
                assertTrue(original.length > 100);
                var rollback = new CadDocument(document.id(), document.features(), document.cursor() - 1);
                assertThrows(
                        ExecutionException.class,
                        () -> exporter.export(rollback, parent).get());
                assertArrayEquals(original, Files.readAllBytes(target));
            }
        }
    }

    @Test
    void cancelledMeshDialogDoesNotScheduleOrEvaluateAnything() throws Exception {
        try (var registry = new ExporterRegistry(directory)) {
            for (String id : List.of("stl", "3mf", "obj", "prusa-project")) {
                var exporter = registry.require(id)
                        .provider()
                        .create(task -> fail("Cancelled export scheduled work"), directory);
                var parent = new ExportDialogs().register(MeshExportDialog.class, request -> Optional.empty());
                assertThrows(
                        CancellationException.class,
                        () -> exporter.export(CadDocument.empty(), parent).join());
            }
        }
    }
}
