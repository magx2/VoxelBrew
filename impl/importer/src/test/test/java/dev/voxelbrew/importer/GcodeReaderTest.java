package dev.voxelbrew.importer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.GcodeJob;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcodeReaderTest {
    @Test
    void orcaFeatureMarkersAndQuarterCircleRetainExtrusionAndLayer() throws Exception {
        var input =
                new java.io.ByteArrayInputStream(("G90\nM83\nG1 Z0.2\n; FEATURE: Outer wall\n" + "G2 X1 Y1 I1 J0 E2\n")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var job = GcodeReader.read(input);
        assertEquals(List.of(0.2), job.modelLayers());
        assertEquals(
                2,
                job.segments().stream().mapToDouble(GcodeJob.Segment::extrusion).sum(),
                1e-9);
        assertEquals(
                Math.PI / 2,
                job.segments().stream().mapToDouble(GcodeJob.Segment::length).sum(),
                0.01);
        assertTrue(job.segments().stream().allMatch(s -> s.role() == GcodeJob.Role.EXTERNAL_PERIMETER));
        assertEquals(1, job.segments().getLast().x1());
        assertEquals(1, job.segments().getLast().y1());
    }

    @TempDir
    Path directory;

    @Test
    void curaRolesSeparateModelFromSupportAndTravel() throws Exception {
        Path file = directory.resolve("cura.gcode");
        Files.writeString(
                file,
                "G21\nG90\nM82\nG1 Z0.2\n;TYPE:WALL-OUTER\nG1 X10 E1\n;TYPE:WALL-INNER\nG1 X20 E2\n;TYPE:FILL\nG1 X30 E3\nG1 Z0.4\n;TYPE:SUPPORT\nG1 X40 E4\n;TYPE:SUPPORT-INTERFACE\nG1 X50 E5\nG1 X60\n");
        var job = GcodeReader.read(file);
        assertEquals(List.of(.2), job.modelLayers());
        assertEquals(
                List.of(
                        GcodeJob.Role.EXTERNAL_PERIMETER,
                        GcodeJob.Role.PERIMETER,
                        GcodeJob.Role.INFILL,
                        GcodeJob.Role.SUPPORT,
                        GcodeJob.Role.INTERFACE,
                        GcodeJob.Role.TRAVEL),
                job.segments().stream().map(GcodeJob.Segment::role).toList());
    }

    @Test
    void parserCountsDepositedMotionRatherThanLayerCommentsAndRejectsUnsupportedDialects() throws Exception {
        Path file = directory.resolve("motion.gcode");
        Files.writeString(
                file,
                "G21\nG90\nM82\nG1 Z0.2\n;TYPE:Perimeter\nG1 X10 E1\nG92 E0\nG1 X20 E1\nG1 Z8\nG1 X30\nG1 Z0.4\nM83\nG91\nG1 X1 E0.1\n;LAYER_CHANGE\n;LAYER_CHANGE\n");
        var job = GcodeReader.read(file);
        assertEquals(List.of(.2, .4), job.modelLayers());
        assertEquals(
                3, job.segments().stream().filter(GcodeJob.Segment::deposits).count());
        Files.writeString(file, "G20\nG1 X1 E1\n");
        assertThrows(java.io.IOException.class, () -> GcodeReader.read(file));
        Files.writeString(file, "G3 X1 Y1 R0 E1\n");
        assertThrows(java.io.IOException.class, () -> GcodeReader.read(file));
    }
}
