package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.mesh.*;
import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.exporter.api.FileExportDialog;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdditionalFormatsTest {
    @TempDir
    Path directory;

    @Test
    void allFourFormatsAreDiscoveredAndObjPreservesMultiplePlacedInstances() throws Exception {
        try (var registry = new ExporterRegistry(directory);
                var evaluation = Evaluator.evaluate(ExportTest.reference())) {
            assertNotNull(registry.require("stl"));
            assertNotNull(registry.require("3mf"));
            assertNotNull(registry.require("obj"));
            assertNotNull(registry.require("step"));
            String body = evaluation.bodies().getFirst().id();
            var view = ModelViews.placed(
                    evaluation,
                    List.of(
                            new BodyInstance("first object", body, Transform.translation(25, -7, 3)),
                            new BodyInstance("second object", body, Transform.translation(100, 20, 0))));
            Path target = directory.resolve("placed.obj");
            FormatTestSupport.write(registry, "obj", view, MeshOptions.DEFAULT, Map.of(), target, false);
            var points = new ArrayList<Double>();
            var indices = new ArrayList<Integer>();
            int objects = 0;
            for (String line : Files.readAllLines(target)) {
                String[] fields = line.split("\\s+");
                if (fields[0].equals("o")) objects++;
                else if (fields[0].equals("v")) {
                    assertEquals(4, fields.length);
                    for (int i = 1; i < 4; i++) points.add(Double.parseDouble(fields[i]));
                } else if (fields[0].equals("f")) {
                    assertEquals(4, fields.length);
                    for (int i = 1; i < 4; i++) {
                        int index = Integer.parseInt(fields[i]);
                        assertTrue(index > 0 && index <= points.size() / 3);
                        indices.add(index - 1);
                    }
                }
            }
            assertEquals(2, objects);
            var mesh = new MeshData(
                    points.stream().mapToDouble(Double::doubleValue).toArray(),
                    indices.stream().mapToInt(Integer::intValue).toArray(),
                    new int[indices.size() / 3]);
            MeshValidation.requireClosed(mesh);
            double[] min = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
            double[] max = {Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY};
            double volume = 0;
            for (int v = 0; v < mesh.vertexCount(); v++)
                for (int axis = 0; axis < 3; axis++) {
                    min[axis] = Math.min(min[axis], mesh.coordinate(v, axis));
                    max[axis] = Math.max(max[axis], mesh.coordinate(v, axis));
                }
            for (int triangle = 0; triangle < mesh.triangleCount(); triangle++) {
                int a = mesh.vertexIndex(triangle, 0),
                        b = mesh.vertexIndex(triangle, 1),
                        c = mesh.vertexIndex(triangle, 2);
                double ax = mesh.coordinate(a, 0), ay = mesh.coordinate(a, 1), az = mesh.coordinate(a, 2);
                double bx = mesh.coordinate(b, 0), by = mesh.coordinate(b, 1), bz = mesh.coordinate(b, 2);
                double cx = mesh.coordinate(c, 0), cy = mesh.coordinate(c, 1), cz = mesh.coordinate(c, 2);
                volume += (ax * (by * cz - bz * cy) + ay * (bz * cx - bx * cz) + az * (bx * cy - by * cx)) / 6;
            }
            assertArrayEquals(new double[] {25, -7, 0}, min, .001);
            assertArrayEquals(new double[] {160, 60, 11}, max, .001);
            assertEquals(2 * 19099.469035085127, volume, 1.0);
            byte[] original = Files.readAllBytes(target);
            assertThrows(
                    java.io.IOException.class,
                    () -> FormatTestSupport.write(registry, "obj", view, MeshOptions.DEFAULT, Map.of(), target, false));
            assertArrayEquals(original, Files.readAllBytes(target));
        }
    }

    @Test
    void stepRoundTripsExactReferenceThroughSeparateReaderAndPreservesDestinationOnFailure() throws Exception {
        var document = ExportTest.reference();
        Path target = directory.resolve("exact reference.step");
        try (var registry = new ExporterRegistry(directory)) {
            registry.require("step")
                    .provider()
                    .create(Runnable::run, directory)
                    .export(document, FileExportDialog.destination(target, false))
                    .get();
        }
        var metrics = inspectStep(target);
        assertEquals("1", metrics.getProperty("solids"));
        assertEquals(19099.469035085127, Double.parseDouble(metrics.getProperty("volume")), .01);
        assertArrayEquals(
                new double[] {0, 0, 0, 60, 40, 8},
                Arrays.stream(metrics.getProperty("bounds").split(","))
                        .mapToDouble(Double::parseDouble)
                        .toArray(),
                .001);
        assertEquals("0", metrics.getProperty("holeContainsMaterial"));
        assertEquals(0, document.parameters().builtins().fileVersion());
        String text = Files.readString(target);
        assertTrue(text.contains("ADVANCED_FACE"));
        assertTrue(text.contains("CYLINDRICAL_SURFACE"));
        byte[] previous = Files.readAllBytes(target);
        var rollback = new CadDocument(document.id(), document.features(), document.cursor() - 1);
        assertThrows(IllegalStateException.class, () -> StepDocumentWriter.save(rollback, target, true, directory));
        assertArrayEquals(previous, Files.readAllBytes(target));
        assertThrows(java.io.IOException.class, () -> StepDocumentWriter.save(document, target, false, directory));
        assertArrayEquals(previous, Files.readAllBytes(target));
    }

    @Test
    @SuppressWarnings("try")
    void stepRetainsTwoExactSolidsAndRejectsClosedHandles() throws Exception {
        try (var a = Solid.rectanglePrism(0, 0, 0, 2, 3, 4);
                var b = Solid.rectanglePrism(10, 0, 0, 3, 4, 5)) {
            Path target = directory.resolve("two.step");
            Files.write(target, Solid.encodeStep(List.of(a, b)));
            var metrics = inspectStep(target);
            assertEquals("2", metrics.getProperty("solids"));
            assertEquals(84, Double.parseDouble(metrics.getProperty("volume")), .001);
            a.close();
            assertThrows(IllegalStateException.class, () -> Solid.encodeStep(List.of(a, b)));
        }
    }

    private Properties inspectStep(Path source) throws Exception {
        Path transcript = directory.resolve(source.getFileName() + ".reader.txt");
        var builder = new ProcessBuilder(System.getProperty("voxelbrew.test.stepReader"), source.toString())
                .redirectErrorStream(true)
                .redirectOutput(transcript.toFile());
        builder.environment()
                .put(
                        "PATH",
                        System.getProperty("voxelbrew.native.path")
                                + java.io.File.pathSeparator
                                + builder.environment().getOrDefault("PATH", ""));
        var process = builder.start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "STEP reader timed out");
            assertEquals(0, process.exitValue(), Files.readString(transcript));
        } finally {
            process.destroyForcibly();
        }
        var properties = new Properties();
        try (var reader = Files.newBufferedReader(transcript)) {
            properties.load(reader);
        }
        return properties;
    }
}
