package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.util.Version;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every command runs in a new JVM, proving that edits come from the saved definitions. */
class WorkflowTest {
    @TempDir
    Path directory;

    @Test
    void workspaceSessionCommitsSketchDraftAndPersistsFormulaBodyNameAndPlateUndo() throws Exception {
        Path file = directory.resolve("workspace session.vxb");
        run(0, "document", "new", file.toString());
        Path script = directory.resolve("commands.json");
        Files.writeString(script, """
                [
                  ["designer", "sketch", "begin"],
                  ["designer", "sketch", "new", "--id", "outline", "--plane", "xy"],
                  ["designer", "sketch", "draw", "--sketch", "outline", "--id", "rectangle", "--tool", "rectangle-two-point", "--points", "0,0,60,40"],
                  ["designer", "sketch", "finish"],
                  ["designer", "solid", "extrude", "--id", "thickness", "--sketch", "outline", "--distance", "8 mm"],
                  ["designer", "body", "rename", "--id", "thickness.body", "--name", "Session plate"],
                  ["slicer", "plate", "add", "--plate", "second"],
                  ["undo"],
                  ["document", "save"]
                ]
                """);
        run(0, "--document", file.toString(), "session", "--script", script.toString());
        var document = DocumentReader.load(file);
        assertEquals(2, document.features().size());
        assertEquals(1, document.printSetup().plates().size());
        assertEquals("Session plate", document.bodyName("thickness.body", "missing"));
        assertVolume(19200, run(0, "--document", file.toString(), "designer", "inspect"));
        run(
                0,
                "--document",
                file.toString(),
                "slicer",
                "placement",
                "add",
                "--instance",
                "first",
                "--body",
                "thickness.body",
                "--x",
                "20",
                "--y",
                "30");
        String executable = dev.voxelbrew.slicer.prusa.PrusaSlicerBackend.defaultExecutable()
                .toString();
        Files.writeString(
                script,
                new com.google.gson.Gson()
                        .toJson(java.util.List.of(
                                java.util.List.of("slicer", "job", "slice", "--background", "--slicer", executable),
                                java.util.List.of("slicer", "job", "wait"),
                                java.util.List.of("document", "save"))));
        assertTrue(run(0, "--document", file.toString(), "session", "--script", script.toString())
                .contains("Slice complete"));
        assertFalse(DocumentReader.load(file).activeGcode().isEmpty());
        byte[] saved = Files.readAllBytes(file);
        Files.writeString(
                script,
                new com.google.gson.Gson()
                        .toJson(java.util.List.of(
                                java.util.List.of("slicer", "job", "slice", "--background", "--slicer", executable),
                                java.util.List.of(
                                        "designer",
                                        "feature",
                                        "set",
                                        "--feature",
                                        "thickness",
                                        "--field",
                                        "distance",
                                        "--value",
                                        "9"),
                                java.util.List.of("slicer", "job", "wait"),
                                java.util.List.of("document", "save"))));
        assertTrue(run(1, "--document", file.toString(), "session", "--script", script.toString())
                .contains("Slice outdated"));
        assertArrayEquals(saved, Files.readAllBytes(file));
        Files.writeString(
                script,
                new com.google.gson.Gson()
                        .toJson(java.util.List.of(
                                java.util.List.of("slicer", "job", "slice", "--background", "--slicer", executable),
                                java.util.List.of("slicer", "job", "cancel"))));
        assertTrue(run(0, "--document", file.toString(), "session", "--script", script.toString())
                .contains("Slice cancelled"));
        run(0, "--document", file.toString(), "slicer", "plate", "add", "--plate", "empty");
        run(0, "--document", file.toString(), "slicer", "job", "slice", "--all", "--slicer", executable);
        var sliced = DocumentReader.load(file);
        assertEquals("empty", sliced.printSetup().activePlateId());
        assertFalse(sliced.gcode().get("plate-1").isEmpty());
        assertFalse(sliced.gcode().containsKey("empty"));
    }

    @Test
    void bodyMaterialsAndColorsPersistAcrossCommandsAndInvalidAssignmentsDoNotWrite() throws Exception {
        String file = directory.resolve("colored objects.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "designer", "solid", "extrude", file, "--id", "solid", "--sketch", "plate", "--distance", "5");
        run(
                0,
                "designer",
                "body",
                "style",
                "set",
                file,
                "--body",
                "solid.body",
                "--material",
                "PETG",
                "--color",
                "#FF0000");
        assertTrue(run(0, "designer", "body", "style", "set", file).contains("solid.body\tPETG\t#FF0000"));
        run(0, "designer", "feature", "set", file, "--feature", "solid", "--field", "distance", "--value", "8");
        assertTrue(run(0, "designer", "body", "style", "set", file).contains("solid.body\tPETG\t#FF0000"));
        byte[] saved = Files.readAllBytes(Path.of(file));
        run(1, "designer", "body", "style", "set", file, "--body", "solid.body", "--color", "red");
        run(1, "designer", "body", "style", "set", file, "--body", "missing.body", "--material", "PLA");
        run(1, "designer", "body", "style", "set", file, "--material", "PLA");
        run(1, "designer", "body", "style", "set", file, "--body", "solid.body", "--reset", "--material", "PLA");
        assertArrayEquals(saved, Files.readAllBytes(Path.of(file)));
        run(
                0,
                "designer",
                "body",
                "style",
                "set",
                file,
                "--body",
                "solid.body",
                "--material",
                "PLA",
                "--color",
                "#0000ff");
        assertTrue(run(0, "designer", "body", "style", "set", file).contains("solid.body\tPLA\t#0000FF"));
        run(0, "designer", "body", "style", "set", file, "--body", "solid.body", "--reset");
        assertTrue(DocumentReader.load(Path.of(file)).bodyStyles().isEmpty());
        assertVolume(19200, run(0, "designer", "inspect", file));
    }

    @Test
    void externalImporterListsProvidersAndPublishesEditableDocumentsAcrossProcesses() throws Exception {
        Path plugins = Files.createDirectories(directory.resolve("import plugins with spaces"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
        String listing = run(0, "document", "importers", "--plugin-dir", plugins.toString());
        assertTrue(listing.contains("plate\tExternal plate"), listing);
        assertTrue(listing.contains("json\tVoxelBrew JSON document"), listing);
        Path source = directory.resolve("source plate.vbplate"), output = directory.resolve("imported design.vxb");
        Files.writeString(source, "60,40,4");
        run(
                0,
                "document",
                "import",
                source.toString(),
                "--format",
                "plate",
                "--plugin-dir",
                plugins.toString(),
                "--option",
                "scale=2",
                "--output",
                output.toString());
        assertVolume(76800, run(0, "designer", "inspect", output.toString()));
        byte[] saved = Files.readAllBytes(output);
        run(
                1,
                "document",
                "import",
                source.toString(),
                "--format",
                "failing",
                "--plugin-dir",
                plugins.toString(),
                "--output",
                output.toString(),
                "--overwrite");
        assertArrayEquals(saved, Files.readAllBytes(output));
        run(
                1,
                "document",
                "import",
                source.toString(),
                "--format",
                "plate",
                "--plugin-dir",
                plugins.toString(),
                "--output",
                output.toString());
        assertArrayEquals(saved, Files.readAllBytes(output));
        String error = run(
                1,
                "document",
                "import",
                source.toString(),
                "--format",
                "plate",
                "--plugin-dir",
                plugins.toString(),
                "--output",
                source.toString(),
                "--overwrite");
        assertTrue(error.contains("must differ from its source"), error);
        assertEquals("60,40,4", Files.readString(source));
        assertTrue(
                run(1, "document", "import", source.toString(), "--format", "plate", "--plugin-dir", plugins.toString())
                        .contains("requires --output"));
    }

    @Test
    void olderApplicationFilesUpgradeAndFutureFilesAreRejectedWithoutWrites() throws Exception {
        Path file = directory.resolve("versioned design.vxb");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        byte[] saved = Files.readAllBytes(file);
        var message = DocumentProto.Document.parseFrom(Arrays.copyOfRange(saved, 44, saved.length));
        assertEquals(Version.version(), message.getApplicationVersion());
        byte[] older = versioned(message, "0.0.0");
        Files.write(file, older);
        assertTrue(run(0, "designer", "history", "list", file.toString()).contains("plate"));
        assertArrayEquals(older, Files.readAllBytes(file));
        run(
                0,
                "designer",
                "feature",
                "set",
                file.toString(),
                "--feature",
                "plate",
                "--field",
                "width",
                "--value",
                "61");
        byte[] upgraded = Files.readAllBytes(file);
        var result = DocumentProto.Document.parseFrom(Arrays.copyOfRange(upgraded, 44, upgraded.length));
        assertEquals(Version.version(), result.getApplicationVersion());
        assertEquals(message.getDocumentId(), result.getDocumentId());
        assertEquals("plate", result.getFeatures(0).getId());
        assertEquals(61, result.getFeatures(0).getSketch().getRectangle().getWidth());
        byte[] future = versioned(result, "65534.65534.65534");
        Files.write(file, future);
        String error = run(1, "designer", "history", "list", file.toString());
        assertTrue(error.contains("VoxelBrew 65534.65534.65534"), error);
        assertTrue(error.contains("supported version " + Version.version()), error);
        assertTrue(run(1, "document", "new", file.toString(), "--overwrite").contains("Update VoxelBrew"));
        assertArrayEquals(future, Files.readAllBytes(file));
    }

    private static byte[] versioned(DocumentProto.Document document, String version) throws Exception {
        byte[] payload =
                document.toBuilder().setApplicationVersion(version).build().toByteArray();
        return ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37})
                .putInt(payload.length)
                .put(MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }

    private void assertVolume(double expected, String output) {
        var match = java.util.regex.Pattern.compile("volume=([0-9.eE+\\-]+)").matcher(output);
        assertTrue(match.find(), output);
        assertEquals(expected, Double.parseDouble(match.group(1)), .01, output);
    }

    @Test
    void namedParametersAndVariantsPersistAcrossProcessesAndExport() throws Exception {
        String file = directory.resolve("parameter configurations.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "designer", "solid", "extrude", file, "--id", "solid", "--sketch", "plate", "--distance", "5");
        run(0, "designer", "parameter", "set", file, "--name", "width", "--unit", "cm", "--expression", "6");
        run(0, "designer", "parameter", "set", file, "--name", "thickness", "--expression", "5");
        run(
                0,
                "designer",
                "parameter",
                "bind",
                file,
                "--feature",
                "plate",
                "--field",
                "width",
                "--expression",
                "width");
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "solid",
                "--field",
                "distance",
                "--expression",
                "thickness");
        run(0, "designer", "configuration", "add", file, "--name", "Large");
        run(
                0,
                "designer",
                "configuration",
                "set",
                file,
                "--name",
                "Large",
                "--parameter",
                "width",
                "--expression",
                "8");
        run(
                0,
                "designer",
                "configuration",
                "set",
                file,
                "--name",
                "Large",
                "--parameter",
                "thickness",
                "--expression",
                "8");
        run(0, "designer", "configuration", "activate", file, "--name", "Large");
        assertTrue(run(0, "designer", "parameter", "list", file).contains("width\tcm\texpression=8"));
        assertVolume(25600, run(0, "designer", "inspect", file));
        var ids = DocumentReader.load(Path.of(file)).features().stream()
                .map(Feature::id)
                .toList();
        run(0, "designer", "sketch", "convert", file, "--sketch", "plate");
        assertVolume(25600, run(0, "designer", "inspect", file));
        run(0, "designer", "parameter", "rename", file, "--name", "width", "--to", "plateWidth");
        assertTrue(run(0, "designer", "parameter", "list", file).contains("constraint:width\tplateWidth"));
        byte[] before = Files.readAllBytes(Path.of(file));
        run(1, "designer", "parameter", "set", file, "--name", "plateWidth", "--expression", "plateWidth/2");
        assertArrayEquals(before, Files.readAllBytes(Path.of(file)));
        run(1, "designer", "parameter", "remove", file, "--name", "plateWidth");
        assertArrayEquals(before, Files.readAllBytes(Path.of(file)));
        run(0, "designer", "configuration", "activate", file, "--name", "Default");
        assertVolume(12000, run(0, "designer", "inspect", file));
        run(0, "designer", "configuration", "activate", file, "--name", "Large");
        assertEquals(
                ids,
                DocumentReader.load(Path.of(file)).features().stream()
                        .map(Feature::id)
                        .toList());
        Path output = directory.resolve("large variant.3mf");
        run(0, "document", "export", file, "--format", "3mf", "--output", output.toString());
        assertTrue(Files.size(output) > 1000);
    }

    @Test
    void newSketchTypesAndTextPersistAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("new sketch types.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "new", file, "--id", "ellipse");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "ellipse",
                "--id",
                "outline",
                "--tool",
                "ELLIPSE",
                "--points",
                "0,0,10,0,0,5");
        run(
                0,
                "designer",
                "sketch",
                "constrain",
                file,
                "--sketch",
                "ellipse",
                "--id",
                "major",
                "--type",
                "MAJOR_RADIUS",
                "--refs",
                "outline",
                "--value",
                "10");
        run(0, "designer", "solid", "extrude", file, "--id", "oval", "--sketch", "ellipse", "--distance", "2");
        assertTrue(run(0, "designer", "inspect", file).contains("volume=314.159"));
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "ellipse",
                "--field",
                "constraint:major",
                "--value",
                "12");
        assertTrue(run(0, "designer", "inspect", file).contains("volume=376.991"));
        run(0, "designer", "sketch", "new", file, "--id", "slot");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "slot",
                "--id",
                "slot-outline",
                "--tool",
                "SLOT_ARC_CENTER",
                "--points",
                "40,0,50,0,40,10,52,0");
        run(0, "designer", "solid", "extrude", file, "--id", "curved-slot", "--sketch", "slot", "--distance", "2");
        run(0, "designer", "sketch", "new", file, "--id", "spline");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "spline",
                "--id",
                "fit",
                "--tool",
                "FIT_SPLINE",
                "--points",
                "0,30,10,30,10,40,0,40",
                "--closed");
        run(0, "designer", "solid", "extrude", file, "--id", "spline-body", "--sketch", "spline", "--distance", "2");
        run(0, "designer", "sketch", "new", file, "--id", "label");
        run(
                0,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "label",
                "--id",
                "text",
                "--tool",
                "TEXT",
                "--points",
                "30,30",
                "--text",
                "OB",
                "--height",
                "8");
        run(0, "designer", "solid", "extrude", file, "--id", "letters", "--sketch", "label", "--distance", "2");
        assertTrue(run(0, "designer", "inspect", file).contains("bodyCount=5"));
        var before = DocumentReader.load(Path.of(file));
        run(0, "designer", "sketch", "text-edit", file, "--sketch", "label", "--id", "text", "--text", "OC");
        var after = DocumentReader.load(Path.of(file));
        assertEquals(before.id(), after.id());
        assertEquals(
                before.features().stream().map(Feature::id).toList(),
                after.features().stream().map(Feature::id).toList());
        var bytes = Files.readAllBytes(Path.of(file));
        run(
                1,
                "designer",
                "sketch",
                "draw",
                file,
                "--sketch",
                "label",
                "--id",
                "invalid",
                "--tool",
                "CONIC",
                "--points",
                "0,0,20,0,10,10",
                "--rho",
                "1");
        assertArrayEquals(bytes, Files.readAllBytes(Path.of(file)));
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "3mf",
                "--output",
                directory.resolve("new types.3mf").toString());
    }

    @Test
    void constrainedProfilesPersistAndRejectConflictsAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("CLI constrained plate.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        var before = DocumentReader.load(Path.of(file));
        run(0, "designer", "solid", "extrude", file, "--id", "thickness", "--sketch", "plate", "--distance", "8");
        run(0, "designer", "sketch", "convert", file, "--sketch", "plate");
        var converted =
                (Feature.Sketch) DocumentReader.load(Path.of(file)).features().getFirst();
        assertEquals(((Feature.Sketch) before.features().getFirst()).profileId(), converted.profileId());
        run(
                0,
                "designer",
                "sketch",
                "entity",
                file,
                "--sketch",
                "plate",
                "--id",
                "hole",
                "--kind",
                "CIRCLE",
                "--values",
                "10,10,2");
        run(
                0,
                "designer",
                "sketch",
                "constrain",
                file,
                "--sketch",
                "plate",
                "--id",
                "hole-fixed",
                "--type",
                "FIX",
                "--refs",
                "hole");
        assertTrue(
                run(0, "designer", "sketch", "list", file, "--sketch", "plate").contains("Fully constrained"));
        assertTrue(run(0, "designer", "inspect", file, "--point", "10,10,4").contains("contains=false"));
        var session = new dev.voxelbrew.engine.model.ModelSession(DocumentReader.load(Path.of(file)));
        try (var result = session.evaluate()) {
            assertEquals(
                    19099.469035085127,
                    result.requireCompleteDocument().getFirst().solid().volume(),
                    0.01);
        }
        byte[] original = Files.readAllBytes(Path.of(file));
        assertTrue(run(
                        1,
                        "designer",
                        "sketch",
                        "constrain",
                        file,
                        "--sketch",
                        "plate",
                        "--id",
                        "bad-width",
                        "--type",
                        "LENGTH",
                        "--refs",
                        "rectangle-bottom",
                        "--value",
                        "80")
                .contains("cannot satisfy"));
        assertArrayEquals(original, Files.readAllBytes(Path.of(file)));
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "plate",
                "--field",
                "constraint:width",
                "--value",
                "80");
        original = Files.readAllBytes(Path.of(file));
        run(
                1,
                "designer",
                "sketch",
                "move",
                file,
                "--sketch",
                "plate",
                "--point",
                "rectangle-bottom.start",
                "--x",
                "4",
                "--y",
                "5");
        assertArrayEquals(original, Files.readAllBytes(Path.of(file)));
        run(0, "designer", "sketch", "remove-constraint", file, "--sketch", "plate", "--id", "anchor");
        run(
                0,
                "designer",
                "sketch",
                "move",
                file,
                "--sketch",
                "plate",
                "--point",
                "rectangle-bottom.start",
                "--x",
                "4",
                "--y",
                "5");
        var profile = (Feature.Constrained)
                ((Feature.Sketch) DocumentReader.load(Path.of(file)).features().getFirst()).profile();
        assertEquals(4, profile.geometry().entity("rectangle-bottom").at(0).x(), 1e-6);
        assertEquals(5, profile.geometry().entity("rectangle-bottom").at(0).y(), 1e-6);
        run(0, "designer", "sketch", "solve", file, "--sketch", "plate");
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "3mf",
                "--output",
                directory.resolve("constrained plate.3mf").toString());
    }

    @Test
    void polygonAndExactSplineSketchesAreAvailableHeadlessly() throws Exception {
        String polygon = directory.resolve("polygon.vxb").toString();
        run(0, "document", "new", polygon);
        run(0, "designer", "sketch", "new", polygon, "--id", "hex");
        run(
                0,
                "designer",
                "sketch",
                "polygon",
                polygon,
                "--sketch",
                "hex",
                "--id",
                "boundary",
                "--radius",
                "10",
                "--sides",
                "6");
        assertTrue(
                run(0, "designer", "sketch", "list", polygon, "--sketch", "hex").contains("POLYGON"));
        run(0, "designer", "solid", "extrude", polygon, "--id", "body", "--sketch", "hex", "--distance", "3");
        try (var evaluation = dev.voxelbrew.engine.model.Evaluator.evaluate(DocumentReader.load(Path.of(polygon)))) {
            assertEquals(
                    3 * Math.sqrt(3) / 2 * 100 * 3,
                    evaluation.requireCompleteDocument().getFirst().solid().volume(),
                    0.001);
        }
        String spline = directory.resolve("spline.vxb").toString();
        run(0, "document", "new", spline);
        run(0, "designer", "sketch", "new", spline, "--id", "curve");
        run(
                0,
                "designer",
                "sketch",
                "entity",
                spline,
                "--sketch",
                "curve",
                "--id",
                "spline",
                "--kind",
                "SPLINE",
                "--values",
                "0,0,0,10,10,10,10,0");
        run(
                0,
                "designer",
                "sketch",
                "entity",
                spline,
                "--sketch",
                "curve",
                "--id",
                "chord",
                "--kind",
                "LINE",
                "--values",
                "10,0,0,0");
        run(0, "designer", "sketch", "profile", spline, "--sketch", "curve", "--entities", "spline,chord");
        run(0, "designer", "solid", "extrude", spline, "--id", "body", "--sketch", "curve", "--distance", "2");
        try (var evaluation = dev.voxelbrew.engine.model.Evaluator.evaluate(DocumentReader.load(Path.of(spline)))) {
            assertEquals(
                    120, evaluation.requireCompleteDocument().getFirst().solid().volume(), 1e-6);
        }
    }

    @Test
    void customProviderSurvivesFreshProcessesMissingPluginAndPrinting() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("feature plugins"));
        Path jar = plugins.resolve("provider.jar"), hidden = directory.resolve("removed provider.jar");
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
        String file = directory.resolve("custom spacer.vxb").toString();
        String plugin = plugins.toString();
        assertTrue(
                run(0, "designer", "feature", "types", "--plugin-dir", plugin).contains("outerDiameter"));
        run(0, "document", "new", file);
        run(
                0,
                "designer",
                "feature",
                "add",
                file,
                "--id",
                "spacer",
                "--provider",
                "example.spacer",
                "--type",
                "spacer",
                "--parameter",
                "height=5",
                "--plugin-dir",
                plugin);
        run(
                0,
                "designer",
                "feature",
                "set",
                file,
                "--feature",
                "spacer",
                "--field",
                "height",
                "--value",
                "8",
                "--plugin-dir",
                plugin);
        var before = DocumentReader.load(Path.of(file));
        byte[] bytes = Files.readAllBytes(Path.of(file));
        assertTrue(run(
                        1,
                        "designer",
                        "feature",
                        "set",
                        file,
                        "--feature",
                        "spacer",
                        "--field",
                        "innerDiameter",
                        "--value",
                        "30",
                        "--plugin-dir",
                        plugin)
                .contains("innerDiameter"));
        assertArrayEquals(bytes, Files.readAllBytes(Path.of(file)));
        assertTrue(run(0, "designer", "inspect", file, "--point", "0,0,4", "--plugin-dir", plugin)
                .contains("contains=false"));
        Path stl = directory.resolve("spacer.stl");
        run(0, "document", "export", file, "--format", "stl", "--output", stl.toString(), "--plugin-dir", plugin);
        byte[] mesh = Files.readAllBytes(stl);
        assertTrue(mesh.length > 84);
        Files.move(jar, hidden);
        assertTrue(run(1, "designer", "history", "list", file, "--plugin-dir", plugin)
                .contains("Missing feature provider"));
        run(1, "designer", "history", "cursor", file, "--position", "1", "--plugin-dir", plugin);
        assertEquals(before, DocumentReader.load(Path.of(file)));
        run(
                1,
                "document",
                "export",
                file,
                "--format",
                "stl",
                "--output",
                stl.toString(),
                "--overwrite",
                "--plugin-dir",
                plugin);
        assertArrayEquals(mesh, Files.readAllBytes(stl));
        Files.move(hidden, jar);
        assertTrue(run(0, "designer", "inspect", file, "--plugin-dir", plugin).contains("spacer.body"));
        run(
                0,
                "slicer",
                "placement",
                "add",
                file,
                "--instance",
                "placed",
                "--body",
                "spacer.body",
                "--x",
                "30",
                "--y",
                "30",
                "--plugin-dir",
                plugin);
        run(0, "slicer", "validate", file, "--plugin-dir", plugin);
        String job = directory.resolve("spacer.gcode").toString();
        assertTrue(run(
                        0,
                        "slicer",
                        "job",
                        "slice",
                        file,
                        "--output",
                        job,
                        "--slicer",
                        dev.voxelbrew.slicer.prusa.PrusaSlicerBackend.defaultExecutable()
                                .toString(),
                        "--plugin-dir",
                        plugin)
                .contains("model_layers=40"));
    }

    @Test
    void publicExportCommandsAndOverwriteGuard() throws Exception {
        Path file = directory.resolve("export input.vxb"),
                stl = directory.resolve("plate.stl"),
                threeMf = directory.resolve("plate.3mf");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "designer",
                "solid",
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "8");
        assertTrue(run(0, "document", "exporters", "--plugin-dir", directory.toString())
                .contains("3mf"));
        run(0, "document", "export", file.toString(), "--format", "stl", "--output", stl.toString());
        byte[] bytes = Files.readAllBytes(stl);
        assertTrue(run(1, "document", "export", file.toString(), "--format", "stl", "--output", stl.toString())
                .contains("overwrite"));
        assertArrayEquals(bytes, Files.readAllBytes(stl));
        run(0, "document", "export", file.toString(), "--format", "3mf", "--output", threeMf.toString());
        try (var zip = new java.util.zip.ZipFile(threeMf.toFile())) {
            assertNotNull(zip.getEntry("3D/3dmodel.model"));
        }
        run(0, "designer", "history", "cursor", file.toString(), "--position", "1");
        assertTrue(run(
                        1,
                        "document",
                        "export",
                        file.toString(),
                        "--format",
                        "stl",
                        "--output",
                        stl.toString(),
                        "--overwrite")
                .contains("timeline"));
        assertArrayEquals(bytes, Files.readAllBytes(stl));
    }

    @Test
    void referenceWorkflowAcrossFreshProcesses() throws Exception {
        Path file = directory.resolve("reference part.vxb");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "designer",
                "solid",
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "5");
        run(
                0,
                "designer",
                "sketch",
                "circle",
                file.toString(),
                "--id",
                "hole",
                "--center-x",
                "10",
                "--center-y",
                "10",
                "--diameter",
                "4");
        run(
                0,
                "designer",
                "solid",
                "cut",
                file.toString(),
                "--id",
                "cut",
                "--sketch",
                "hole",
                "--body-feature",
                "thickness");
        var before = DocumentReader.load(file);
        var ids = before.features().stream().map(Feature::id).toList();
        assertEquals(Arrays.asList("plate", "thickness", "hole", "cut"), ids);
        assertTrue(run(0, "designer", "inspect", file.toString()).contains("volume=11937.168146928205"));
        run(
                0,
                "designer",
                "feature",
                "set",
                file.toString(),
                "--feature",
                "thickness",
                "--field",
                "distance",
                "--value",
                "8");
        var reopened = DocumentReader.load(file);
        assertEquals(before.id(), reopened.id());
        assertEquals(ids, reopened.features().stream().map(Feature::id).toList());
        String measurements = run(0, "designer", "inspect", file.toString(), "--point", "10,10,4");
        assertTrue(measurements.contains("volume=19099.469035085127"), measurements);
        assertTrue(measurements.contains(
                "bounds=0.000000000,0.000000000,0.000000000,60.000000000,40.000000000,8.000000000"));
        assertTrue(measurements.contains("contains=false"));
        assertTrue(run(0, "designer", "inspect", file.toString(), "--point", "13,10,4")
                .contains("contains=true"));
        run(0, "designer", "history", "cursor", file.toString(), "--position", "3");
        assertTrue(run(0, "designer", "inspect", file.toString()).contains("volume=19200.000000000000"));
        run(0, "designer", "history", "cursor", file.toString(), "--position", "4");
        byte[] good = Files.readAllBytes(file);
        String invalid = run(
                1,
                "designer",
                "feature",
                "set",
                file.toString(),
                "--feature",
                "hole",
                "--field",
                "diameter",
                "--value",
                "NaN");
        assertTrue(invalid.contains("hole") && invalid.contains("diameter"));
        assertArrayEquals(good, Files.readAllBytes(file));
        String history = run(0, "designer", "history", "list", file.toString());
        assertTrue(history.contains("profileId=hole.profile") && history.contains("distance=8.0"));
        Files.write(file, Arrays.copyOf(good, good.length - 3));
        assertTrue(run(1, "designer", "inspect", file.toString()).toLowerCase().contains("truncated"));
        System.out.println(measurements);
    }

    @Test
    void referenceRepairIsAvailableThroughPublicCommands() throws Exception {
        Path file = directory.resolve("repair.vxb");
        run(0, "document", "new", file.toString());
        run(0, "designer", "sketch", "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "designer",
                "solid",
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "8");
        var document = DocumentReader.load(file);
        var features = new ArrayList<>(document.features());
        features.set(1, new Feature.Extrude("thickness", new Feature.ProfileRef("missing", "profile"), 8));
        DocumentWriter.save(new dev.voxelbrew.engine.model.CadDocument(document.id(), features, 2), file, true);
        assertTrue(run(1, "designer", "history", "list", file.toString()).contains("FAILED"));
        run(0, "designer", "feature", "repair", file.toString(), "--feature", "thickness", "--sketch", "plate");
        assertTrue(run(0, "designer", "inspect", file.toString()).contains("volume=19200.000000000000"));
        assertEquals(2, DocumentReader.load(file).features().size());
    }

    @Test
    void splitPlacementSupportAndSlicingAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("manufacturing fixture.vxb").toString();
        run(0, "document", "new", file);
        run(0, "designer", "sketch", "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "designer", "solid", "extrude", file, "--id", "thickness", "--sketch", "plate", "--distance", "8");
        run(
                0,
                "designer",
                "sketch",
                "circle",
                file,
                "--id",
                "hole",
                "--center-x",
                "10",
                "--center-y",
                "10",
                "--diameter",
                "4");
        run(0, "designer", "solid", "cut", file, "--id", "cut", "--sketch", "hole", "--body-feature", "thickness");
        run(0, "designer", "solid", "split", file, "--id", "halves", "--body-feature", "cut", "--offset", "30");
        assertTrue(run(0, "designer", "inspect", file).contains("halves.negative"));
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "stl",
                "--body",
                "halves.negative",
                "--output",
                directory.resolve("left.stl").toString());
        run(
                0,
                "document",
                "export",
                file,
                "--format",
                "stl",
                "--body",
                "halves.positive",
                "--output",
                directory.resolve("right.stl").toString());
        run(
                0,
                "slicer",
                "placement",
                "add",
                file,
                "--instance",
                "left",
                "--body",
                "halves.negative",
                "--x",
                "20",
                "--y",
                "30");
        run(
                0,
                "slicer",
                "placement",
                "add",
                file,
                "--instance",
                "right",
                "--body",
                "halves.positive",
                "--x",
                "30",
                "--y",
                "30");
        run(
                0,
                "slicer",
                "supports",
                "set",
                file,
                "--id",
                "blocker",
                "--instance",
                "left",
                "--role",
                "BLOCKER",
                "--width",
                "5",
                "--depth",
                "5",
                "--height",
                "8");
        run(0, "slicer", "process", "set", file, "--material", "PETG");
        assertTrue(run(0, "slicer", "inspect", file).contains("PETG"));
        assertTrue(run(0, "slicer", "validate", file).contains("VALID"));
        String output = directory.resolve("CLI real job.gcode").toString();
        String slicer = dev.voxelbrew.slicer.prusa.PrusaSlicerBackend.defaultExecutable()
                .toString();
        assertTrue(run(0, "slicer", "job", "slice", file, "--output", output, "--slicer", slicer)
                .contains("model_layers=40"));
        assertTrue(run(0, "slicer", "job", "inspect", output).contains("material=PETG"));
        assertEquals(
                Files.readAllLines(Path.of(output)),
                DocumentReader.load(Path.of(file)).activeGcode());
        Path exported = directory.resolve("stored-job.gcode");
        run(0, "document", "export", file, "--format", "gcode", "--output", exported.toString());
        assertEquals(Files.readAllLines(Path.of(output)), Files.readAllLines(exported));
        byte[] original = Files.readAllBytes(Path.of(output));
        assertTrue(run(1, "slicer", "job", "slice", file, "--output", output, "--slicer", slicer)
                .contains("overwrite"));
        assertArrayEquals(original, Files.readAllBytes(Path.of(output)));
        run(0, "slicer", "placement", "remove", file, "--instance", "left");
        assertTrue(DocumentReader.load(Path.of(file)).gcode().isEmpty());
        assertTrue(
                run(1, "document", "export", file, "--format", "gcode", "--output", exported.toString(), "--overwrite")
                        .contains("No G-code"));
        assertTrue(run(1, "slicer", "validate", file).contains("MISSING_INSTANCE"));
        run(0, "slicer", "supports", "remove", file, "--id", "blocker");
        run(0, "slicer", "validate", file);
    }

    private String run(int expected, String... args) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvoxelbrew.native.path=" + System.getProperty("voxelbrew.native.path"));
        command.add("-Dvoxelbrew.home=" + directory.resolve("settings"));
        command.add("-cp");
        command.add(System.getProperty("voxelbrew.test.classpath"));
        command.add(VoxelBrew.class.getName());
        command.addAll(Arrays.asList(args));
        Path log = Files.createTempFile(directory, "command-", ".txt");
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("CLI timed out: " + command);
        }
        String output = Files.readString(log);
        assertEquals(expected, process.exitValue(), String.join(" ", args) + "\n" + output);
        return output;
    }
}
