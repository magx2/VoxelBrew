package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.api.*;
import dev.voxelbrew.importer.*;
import dev.voxelbrew.importer.api.ImportResult;
import java.nio.file.*;
import java.util.*;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerSettingsIoTest {
    @TempDir
    Path directory;

    private PrintSetup setup() {
        return PrintSetup.empty()
                .withProfile(
                        "prusa-slicer",
                        "generic-marlin-200-0.4",
                        "PETG",
                        new ProcessSettings(0.25, 4, 35, true, true, 55, 0.3, 4, 6, 2))
                .withInstances(List.of(new BodyInstance("part", "body", Transform.translation(20, 30, 0))))
                .withSupports(List.of(new SupportVolume("block", "part", SupportVolume.Role.BLOCKER, 1, 2, 3, 4, 5, 6)))
                .renamePlate("plate-1", "Main")
                .addPlate("spare")
                .selectPlate("plate-1")
                .withWorkspaceSettings(Map.of(
                        "print-output",
                        "custom.gcode",
                        "print-overwrite",
                        "true",
                        "print-walls",
                        "5",
                        "print-support-width",
                        "2 + 3",
                        "print-rotation-space",
                        "LOCAL"));
    }

    @Test
    void bothDocumentProviderPairsPreserveAllAppliedSettingsAndDraftsWithoutModifyingInput() throws Exception {
        var expected = setup();
        var document = new CadDocument("settings", List.of(), 0, expected);
        try (var exporters = new ExporterRegistry(directory);
                var importers = new ImporterRegistry(directory)) {
            for (String format : List.of("vxb", "json")) {
                Path file = directory.resolve("settings." + format);
                exporters
                        .require(format)
                        .provider()
                        .create(Runnable::run, directory)
                        .export(document, FileExportDialog.destination(file, false))
                        .get();
                byte[] before = Files.readAllBytes(file);
                var imported = assertInstanceOf(
                                ImportResult.Document.class, ImportService.read(importers, format, file, Map.of()))
                        .document();
                assertEquals(expected, imported.printSetup());
                assertArrayEquals(before, Files.readAllBytes(file));
                Path copy = directory.resolve("copy-" + format + ".vxb");
                exporters
                        .require("vxb")
                        .provider()
                        .create(Runnable::run, directory)
                        .export(imported, FileExportDialog.destination(copy, false))
                        .get();
                assertEquals(expected, DocumentReader.load(copy).printSetup());
            }
        }
    }

    @Test
    void directThreeMfFileWriterKeepsManufacturingMetadata() throws Exception {
        var setup = setup();
        var mesh = MeshGeometry.box(
                new SupportVolume("box", "part", SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 10, 10, 10));
        var model = new ManufacturingModel(
                "settings", "revision", setup, PrintProfile.resolve(setup), Map.of("body", mesh));
        Path file = directory.resolve("direct.3mf");
        new ThreeMfExporter()
                .writeFile(
                        ModelViews.from(model),
                        new MeshExportDialog.Settings(
                                new ExportSelection(
                                        ExportSelection.Space.BUILD_PLATE, ExportSelection.Contents.MODEL, null, null),
                                MeshOptions.DEFAULT,
                                Map.of(),
                                new FileExportDialog.Destination(file, false)));
        try (var zip = new ZipFile(file.toFile());
                var input = zip.getInputStream(zip.getEntry("3D/3dmodel.model"))) {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(true);
            var xml = factory.newDocumentBuilder().parse(input);
            var metadata = xml.getElementsByTagNameNS("*", "metadata");
            assertEquals(1, metadata.getLength());
            var saved = DocumentProto.PrintSetup.newBuilder();
            com.google.protobuf.util.JsonFormat.parser().merge(metadata.item(0).getTextContent(), saved);
            assertEquals(setup.workspaceSettings(), saved.getWorkspaceSettingsMap());
            assertEquals(4, saved.getProcess().getWalls());
            assertEquals(2, saved.getPlatesCount());
            assertEquals("plate-1", saved.getActivePlateId());
        }
    }
}
