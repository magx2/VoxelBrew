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
