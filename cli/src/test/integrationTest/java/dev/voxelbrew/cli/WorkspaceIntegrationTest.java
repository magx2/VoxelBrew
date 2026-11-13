package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.io.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** CLI/service integration in-process; real OCCT and atomic document codecs, no child JVM. */
class WorkspaceIntegrationTest {
    @TempDir
    Path directory;

    private String output;

    private int run(String... args) {
        var text = new StringWriter();
        int result = VoxelBrew.commandLine()
                .setOut(new PrintWriter(text))
                .setErr(new PrintWriter(text))
                .execute(args);
        output = text.toString();
        return result;
    }

    private Path part(String name) throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "outline", "outline.profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("solid", new Feature.ProfileRef("outline", "outline.profile"), 8));
        Path file = directory.resolve(name + ".vxb");
        DocumentWriter.save(session.document(), file, false);
        return file;
    }

    @Test
    void finishingCommandsRebuildPersistedSolidsAndKeepTheirExpressions() throws Exception {
        for (String operation : new String[] {"fillet", "chamfer", "shell"}) {
            Path file = part(operation);
            String selection = operation.equals("shell") ? "--face" : "--edges";
            assertEquals(
                    0,
                    run(
                            "--document",
                            file.toString(),
                            "designer",
                            "solid",
                            operation,
                            "--id",
                            "finish",
                            "--body-feature",
                            "solid",
                            "--size",
                            "1 mm",
                            selection,
                            "1"),
                    output);
            var document = DocumentReader.load(file);
            assertEquals(3, document.features().size());
            assertTrue(document.parameters().bindings().stream()
                    .anyMatch(b -> b.target().featureId().equals("finish")
                            && b.expression().equals("1 mm")));
            try (var evaluation = new ModelSession(document).evaluate()) {
                assertTrue(evaluation.valid());
                double volume =
                        evaluation.requireCompleteDocument().getFirst().solid().volume();
                assertTrue(volume > 0 && volume < 19200, operation + " volume=" + volume);
            }
        }
    }

    @Test
    void constructionPreviewIsTransientAndAppliedDatumSurvivesReopen() throws Exception {
        Path file = part("construction");
        byte[] before = Files.readAllBytes(file);
        assertEquals(
                0,
                run(
                        "--document",
                        file.toString(),
                        "designer",
                        "construction",
                        "preview",
                        "--id",
                        "datum",
                        "--tool",
                        "offset-plane",
                        "--input",
                        "origin_plane:::0",
                        "--value",
                        "12"),
                output);
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(
                0,
                run(
                        "--document",
                        file.toString(),
                        "designer",
                        "construction",
                        "add",
                        "--id",
                        "datum",
                        "--tool",
                        "offset-plane",
                        "--input",
                        "origin_plane:::0",
                        "--value",
                        "12"),
                output);
        var document = DocumentReader.load(file);
        assertInstanceOf(ConstructionFeature.class, document.features().getLast());
        try (var evaluation = new ModelSession(document).evaluate()) {
            assertTrue(evaluation.valid());
            assertTrue(evaluation.construction().containsKey("datum"));
        }
    }

    @Test
    void staleRevisionAndInvalidExpressionLeaveTheOriginalBytesUntouched() throws Exception {
        Path file = part("protected");
        byte[] before = Files.readAllBytes(file);
        String revision = DocumentCodec.fingerprint(DocumentReader.load(file));
        assertEquals(
                1,
                run(
                        "--document",
                        file.toString(),
                        "designer",
                        "body",
                        "rename",
                        "--id",
                        "solid.body",
                        "--name",
                        "Changed",
                        "--revision",
                        revision + "stale"));
        assertArrayEquals(before, Files.readAllBytes(file));
        assertEquals(
                1,
                run(
                        "--document",
                        file.toString(),
                        "designer",
                        "solid",
                        "shell",
                        "--id",
                        "bad",
                        "--body-feature",
                        "solid",
                        "--face",
                        "1",
                        "--size",
                        "missing_parameter"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test
    void placementRotationPreservesClearanceAndInvalidatesStoredJobs() throws Exception {
        Path file = part("rotation");
        assertEquals(
                0,
                run(
                        "--document",
                        file.toString(),
                        "slicer",
                        "placement",
                        "add",
                        "--instance",
                        "first",
                        "--body",
                        "solid.body",
                        "--x",
                        "30",
                        "--y",
                        "30",
                        "--z",
                        "5"),
                output);
        var before = DocumentReader.load(file);
        DocumentWriter.save(before.withGcode(java.util.Map.of("plate-1", java.util.List.of("G1 X1"))), file, true);
        assertEquals(
                0,
                run(
                        "--document",
                        file.toString(),
                        "slicer",
                        "placement",
                        "rotate",
                        "--instance",
                        "first",
                        "--axis",
                        "x",
                        "--space",
                        "local",
                        "--angle",
                        "90 deg"),
                output);
        var document = DocumentReader.load(file);
        assertTrue(document.gcode().isEmpty());
        var model = dev.voxelbrew.engine.printing.ManufacturingModels.evaluate(
                document, dev.voxelbrew.engine.mesh.MeshOptions.DEFAULT);
        var bounds = dev.voxelbrew.engine.printing.MeshGeometry.bounds(
                model.placedMesh(model.instances().getFirst()));
        assertEquals(5, bounds.minZ(), 0.001);
        assertEquals(40, bounds.maxZ() - bounds.minZ(), 0.001);
    }
}
