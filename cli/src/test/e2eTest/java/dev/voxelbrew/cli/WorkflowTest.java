package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.persistence.DocumentStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Every command runs in a new JVM, proving that edits come from the saved definitions. */
class WorkflowTest {
    @TempDir Path directory;

    private void assertVolume(double expected, String output) {
        var match = java.util.regex.Pattern.compile("volume=([0-9.eE+\\-]+)").matcher(output);
        assertTrue(match.find(), output);
        assertEquals(expected, Double.parseDouble(match.group(1)), .01, output);
    }

    @Test
    void namedParametersAndVariantsPersistAcrossProcessesAndExport() throws Exception {
        String file = directory.resolve("parameter configurations.jcad").toString();
        run(0, "new", file);
        run(0, "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "extrude", file, "--id", "solid", "--sketch", "plate", "--distance", "5");
        run(0, "parameters", "set", file, "--name", "width", "--unit", "cm", "--expression", "6");
        run(0, "parameters", "set", file, "--name", "thickness", "--expression", "5");
        run(
                0,
                "parameters",
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
                "edit",
                file,
                "--feature",
                "solid",
                "--field",
                "distance",
                "--expression",
                "thickness");
        run(0, "configurations", "add", file, "--name", "Large");
        run(
                0,
                "configurations",
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
                "configurations",
                "set",
                file,
                "--name",
                "Large",
                "--parameter",
                "thickness",
                "--expression",
                "8");
        run(0, "configurations", "activate", file, "--name", "Large");
        assertTrue(run(0, "parameters", "list", file).contains("width\tcm\texpression=8"));
        assertVolume(25600, run(0, "inspect", file));
        var ids = DocumentStore.load(Path.of(file)).features().stream().map(Feature::id).toList();
        run(0, "sketch", "convert", file, "--sketch", "plate");
        assertVolume(25600, run(0, "inspect", file));
        run(0, "parameters", "rename", file, "--name", "width", "--to", "plateWidth");
        assertTrue(run(0, "parameters", "list", file).contains("constraint:width\tplateWidth"));
        byte[] before = Files.readAllBytes(Path.of(file));
        run(1, "parameters", "set", file, "--name", "plateWidth", "--expression", "plateWidth/2");
        assertArrayEquals(before, Files.readAllBytes(Path.of(file)));
        run(1, "parameters", "remove", file, "--name", "plateWidth");
        assertArrayEquals(before, Files.readAllBytes(Path.of(file)));
        run(0, "configurations", "activate", file, "--name", "Default");
        assertVolume(12000, run(0, "inspect", file));
        run(0, "configurations", "activate", file, "--name", "Large");
        assertEquals(
                ids,
                DocumentStore.load(Path.of(file)).features().stream().map(Feature::id).toList());
        Path output = directory.resolve("large variant.3mf");
        run(0, "export", file, "--format", "3mf", "--output", output.toString());
        assertTrue(Files.size(output) > 1000);
    }

    @Test
    void newSketchTypesAndTextPersistAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("new sketch types.jcad").toString();
        run(0, "new", file);
        run(0, "sketch", "new", file, "--id", "ellipse");
        run(
                0,
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
        run(0, "extrude", file, "--id", "oval", "--sketch", "ellipse", "--distance", "2");
        assertTrue(run(0, "inspect", file).contains("volume=314.159"));
        run(
                0,
                "edit",
                file,
                "--feature",
                "ellipse",
                "--field",
                "constraint:major",
                "--value",
                "12");
        assertTrue(run(0, "inspect", file).contains("volume=376.991"));
        run(0, "sketch", "new", file, "--id", "slot");
        run(
                0,
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
        run(0, "extrude", file, "--id", "curved-slot", "--sketch", "slot", "--distance", "2");
        run(0, "sketch", "new", file, "--id", "spline");
        run(
                0,
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
        run(0, "extrude", file, "--id", "spline-body", "--sketch", "spline", "--distance", "2");
        run(0, "sketch", "new", file, "--id", "label");
        run(
                0,
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
        run(0, "extrude", file, "--id", "letters", "--sketch", "label", "--distance", "2");
        assertTrue(run(0, "inspect", file).contains("bodyCount=5"));
        var before = DocumentStore.load(Path.of(file));
        run(0, "sketch", "text-edit", file, "--sketch", "label", "--id", "text", "--text", "OC");
        var after = DocumentStore.load(Path.of(file));
        assertEquals(before.id(), after.id());
        assertEquals(
                before.features().stream().map(Feature::id).toList(),
                after.features().stream().map(Feature::id).toList());
        var bytes = Files.readAllBytes(Path.of(file));
        run(
                1,
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
                "export",
                file,
                "--format",
                "3mf",
                "--output",
                directory.resolve("new types.3mf").toString());
    }

    @Test
    void constrainedProfilesPersistAndRejectConflictsAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("CLI constrained plate.jcad").toString();
        run(0, "new", file);
        run(0, "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        var before = DocumentStore.load(Path.of(file));
        run(0, "extrude", file, "--id", "thickness", "--sketch", "plate", "--distance", "8");
        run(0, "sketch", "convert", file, "--sketch", "plate");
        var converted = (Feature.Sketch) DocumentStore.load(Path.of(file)).features().getFirst();
        assertEquals(
                ((Feature.Sketch) before.features().getFirst()).profileId(), converted.profileId());
        run(
                0,
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
                run(0, "sketch", "list", file, "--sketch", "plate").contains("Fully constrained"));
        assertTrue(run(0, "inspect", file, "--point", "10,10,4").contains("contains=false"));
        var session =
                new dev.voxelbrew.engine.model.ModelSession(DocumentStore.load(Path.of(file)));
        try (var result = session.evaluate()) {
            assertEquals(
                    19099.469035085127,
                    result.requireCompleteDocument().getFirst().solid().volume(),
                    0.01);
        }
        byte[] original = Files.readAllBytes(Path.of(file));
        assertTrue(
                run(
                                1,
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
        run(0, "edit", file, "--feature", "plate", "--field", "constraint:width", "--value", "80");
        original = Files.readAllBytes(Path.of(file));
        run(
                1,
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
        run(0, "sketch", "remove-constraint", file, "--sketch", "plate", "--id", "anchor");
        run(
                0,
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
        var profile =
                (Feature.Constrained)
                        ((Feature.Sketch) DocumentStore.load(Path.of(file)).features().getFirst())
                                .profile();
        assertEquals(4, profile.geometry().entity("rectangle-bottom").at(0).x(), 1e-6);
        assertEquals(5, profile.geometry().entity("rectangle-bottom").at(0).y(), 1e-6);
        run(0, "sketch", "solve", file, "--sketch", "plate");
        run(
                0,
                "export",
                file,
                "--format",
                "3mf",
                "--output",
                directory.resolve("constrained plate.3mf").toString());
    }

    @Test
    void polygonAndExactSplineSketchesAreAvailableHeadlessly() throws Exception {
        String polygon = directory.resolve("polygon.jcad").toString();
        run(0, "new", polygon);
        run(0, "sketch", "new", polygon, "--id", "hex");
        run(
                0,
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
        assertTrue(run(0, "sketch", "list", polygon, "--sketch", "hex").contains("POLYGON"));
        run(0, "extrude", polygon, "--id", "body", "--sketch", "hex", "--distance", "3");
        try (var evaluation =
                dev.voxelbrew.engine.model.Evaluator.evaluate(
                        DocumentStore.load(Path.of(polygon)))) {
            assertEquals(
                    3 * Math.sqrt(3) / 2 * 100 * 3,
                    evaluation.requireCompleteDocument().getFirst().solid().volume(),
                    0.001);
        }
        String spline = directory.resolve("spline.jcad").toString();
        run(0, "new", spline);
        run(0, "sketch", "new", spline, "--id", "curve");
        run(
                0,
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
        run(0, "sketch", "profile", spline, "--sketch", "curve", "--entities", "spline,chord");
        run(0, "extrude", spline, "--id", "body", "--sketch", "curve", "--distance", "2");
        try (var evaluation =
                dev.voxelbrew.engine.model.Evaluator.evaluate(
                        DocumentStore.load(Path.of(spline)))) {
            assertEquals(
                    120, evaluation.requireCompleteDocument().getFirst().solid().volume(), 1e-6);
        }
    }

    @Test
    void customProviderSurvivesFreshProcessesMissingPluginAndPrinting() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("feature plugins"));
        Path jar = plugins.resolve("provider.jar"),
                hidden = directory.resolve("removed provider.jar");
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
        String file = directory.resolve("custom spacer.jcad").toString();
        String plugin = plugins.toString();
        assertTrue(run(0, "feature-types", "--plugin-dir", plugin).contains("outerDiameter"));
        run(0, "new", file);
        run(
                0,
                "custom",
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
                "edit",
                file,
                "--feature",
                "spacer",
                "--field",
                "height",
                "--value",
                "8",
                "--plugin-dir",
                plugin);
        var before = DocumentStore.load(Path.of(file));
        byte[] bytes = Files.readAllBytes(Path.of(file));
        assertTrue(
                run(
                                1,
                                "edit",
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
        assertTrue(
                run(0, "inspect", file, "--point", "0,0,4", "--plugin-dir", plugin)
                        .contains("contains=false"));
        Path stl = directory.resolve("spacer.stl");
        run(
                0,
                "export",
                file,
                "--format",
                "stl",
                "--output",
                stl.toString(),
                "--plugin-dir",
                plugin);
        byte[] mesh = Files.readAllBytes(stl);
        assertTrue(mesh.length > 84);
        Files.move(jar, hidden);
        assertTrue(
                run(1, "history", file, "--plugin-dir", plugin)
                        .contains("Missing feature provider"));
        run(1, "cursor", file, "--position", "1", "--plugin-dir", plugin);
        assertEquals(before, DocumentStore.load(Path.of(file)));
        run(
                1,
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
        assertTrue(run(0, "inspect", file, "--plugin-dir", plugin).contains("spacer.body"));
        run(
                0,
                "place",
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
        run(0, "validate", file, "--plugin-dir", plugin);
        String job = directory.resolve("spacer.gcode").toString();
        assertTrue(
                run(
                                0,
                                "slice",
                                file,
                                "--output",
                                job,
                                "--slicer",
                                dev.voxelbrew.engine.printing.PrusaSlicerBackend.defaultExecutable()
                                        .toString(),
                                "--plugin-dir",
                                plugin)
                        .contains("model_layers=40"));
    }

    @Test
    void publicExportCommandsAndOverwriteGuard() throws Exception {
        Path file = directory.resolve("export input.jcad"),
                stl = directory.resolve("plate.stl"),
                threeMf = directory.resolve("plate.3mf");
        run(0, "new", file.toString());
        run(0, "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "8");
        assertTrue(run(0, "exporters", "--plugin-dir", directory.toString()).contains("3mf"));
        run(0, "export", file.toString(), "--format", "stl", "--output", stl.toString());
        byte[] bytes = Files.readAllBytes(stl);
        assertTrue(
                run(1, "export", file.toString(), "--format", "stl", "--output", stl.toString())
                        .contains("overwrite"));
        assertArrayEquals(bytes, Files.readAllBytes(stl));
        run(0, "export", file.toString(), "--format", "3mf", "--output", threeMf.toString());
        try (var zip = new java.util.zip.ZipFile(threeMf.toFile())) {
            assertNotNull(zip.getEntry("3D/3dmodel.model"));
        }
        run(0, "cursor", file.toString(), "--position", "1");
        assertTrue(
                run(
                                1,
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
        Path file = directory.resolve("reference part.jcad");
        run(0, "new", file.toString());
        run(0, "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
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
                "cut",
                file.toString(),
                "--id",
                "cut",
                "--sketch",
                "hole",
                "--body-feature",
                "thickness");
        var before = DocumentStore.load(file);
        var ids = before.features().stream().map(Feature::id).toList();
        assertEquals(Arrays.asList("plate", "thickness", "hole", "cut"), ids);
        assertTrue(run(0, "inspect", file.toString()).contains("volume=11937.168146928205"));
        run(
                0,
                "edit",
                file.toString(),
                "--feature",
                "thickness",
                "--field",
                "distance",
                "--value",
                "8");
        var reopened = DocumentStore.load(file);
        assertEquals(before.id(), reopened.id());
        assertEquals(ids, reopened.features().stream().map(Feature::id).toList());
        String measurements = run(0, "inspect", file.toString(), "--point", "10,10,4");
        assertTrue(measurements.contains("volume=19099.469035085127"), measurements);
        assertTrue(
                measurements.contains(
                        "bounds=0.000000000,0.000000000,0.000000000,60.000000000,40.000000000,8.000000000"));
        assertTrue(measurements.contains("contains=false"));
        assertTrue(
                run(0, "inspect", file.toString(), "--point", "13,10,4").contains("contains=true"));
        run(0, "cursor", file.toString(), "--position", "3");
        assertTrue(run(0, "inspect", file.toString()).contains("volume=19200.000000000000"));
        run(0, "cursor", file.toString(), "--position", "4");
        byte[] good = Files.readAllBytes(file);
        String invalid =
                run(
                        1,
                        "edit",
                        file.toString(),
                        "--feature",
                        "hole",
                        "--field",
                        "diameter",
                        "--value",
                        "NaN");
        assertTrue(invalid.contains("hole") && invalid.contains("diameter"));
        assertArrayEquals(good, Files.readAllBytes(file));
        String history = run(0, "history", file.toString());
        assertTrue(history.contains("profileId=hole.profile") && history.contains("distance=8.0"));
        Files.write(file, Arrays.copyOf(good, good.length - 3));
        assertTrue(run(1, "inspect", file.toString()).toLowerCase().contains("truncated"));
        System.out.println(measurements);
    }

    @Test
    void referenceRepairIsAvailableThroughPublicCommands() throws Exception {
        Path file = directory.resolve("repair.jcad");
        run(0, "new", file.toString());
        run(0, "rectangle", file.toString(), "--id", "plate", "--width", "60", "--height", "40");
        run(
                0,
                "extrude",
                file.toString(),
                "--id",
                "thickness",
                "--sketch",
                "plate",
                "--distance",
                "8");
        var document = DocumentStore.load(file);
        var features = new ArrayList<>(document.features());
        features.set(
                1,
                new Feature.Extrude("thickness", new Feature.ProfileRef("missing", "profile"), 8));
        DocumentStore.save(
                new dev.voxelbrew.engine.model.CadDocument(document.id(), features, 2), file, true);
        assertTrue(run(1, "history", file.toString()).contains("FAILED"));
        run(0, "repair", file.toString(), "--feature", "thickness", "--sketch", "plate");
        assertTrue(run(0, "inspect", file.toString()).contains("volume=19200.000000000000"));
        assertEquals(2, DocumentStore.load(file).features().size());
    }

    @Test
    void splitPlacementSupportAndSlicingAcrossFreshProcesses() throws Exception {
        String file = directory.resolve("manufacturing fixture.jcad").toString();
        run(0, "new", file);
        run(0, "rectangle", file, "--id", "plate", "--width", "60", "--height", "40");
        run(0, "extrude", file, "--id", "thickness", "--sketch", "plate", "--distance", "8");
        run(
                0,
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
        run(0, "cut", file, "--id", "cut", "--sketch", "hole", "--body-feature", "thickness");
        run(0, "split", file, "--id", "halves", "--body-feature", "cut", "--offset", "30");
        assertTrue(run(0, "inspect", file).contains("halves.negative"));
        run(
                0,
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
                "place",
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
                "place",
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
                "support",
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
        run(0, "print-settings", file, "--material", "PETG");
        assertTrue(run(0, "print-setup", file).contains("PETG"));
        assertTrue(run(0, "validate", file).contains("VALID"));
        String output = directory.resolve("CLI real job.gcode").toString();
        String slicer =
                dev.voxelbrew.engine.printing.PrusaSlicerBackend.defaultExecutable().toString();
        assertTrue(
                run(0, "slice", file, "--output", output, "--slicer", slicer)
                        .contains("model_layers=40"));
        assertTrue(run(0, "inspect-job", output).contains("material=PETG"));
        byte[] original = Files.readAllBytes(Path.of(output));
        assertTrue(
                run(1, "slice", file, "--output", output, "--slicer", slicer)
                        .contains("overwrite"));
        assertArrayEquals(original, Files.readAllBytes(Path.of(output)));
        run(0, "remove-instance", file, "--instance", "left");
        assertTrue(run(1, "validate", file).contains("MISSING_INSTANCE"));
        run(0, "remove-support", file, "--id", "blocker");
        run(0, "validate", file);
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
        Process process =
                new ProcessBuilder(command)
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
