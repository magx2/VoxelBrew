package dev.voxelbrew.importer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.GcodeJob;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GcodeReaderTest {
    @TempDir Path directory;

    @Test
    void parserCountsDepositedMotionRatherThanLayerCommentsAndRejectsUnsupportedDialects()
            throws Exception {
        Path file = directory.resolve("motion.gcode");
        Files.writeString(
                file,
                "G21\nG90\nM82\nG1 Z0.2\n;TYPE:Perimeter\nG1 X10 E1\nG92 E0\nG1 X20 E1\nG1 Z8\nG1 X30\nG1 Z0.4\nM83\nG91\nG1 X1 E0.1\n;LAYER_CHANGE\n;LAYER_CHANGE\n");
        var job = GcodeReader.read(file);
        assertEquals(List.of(.2, .4), job.modelLayers());
        assertEquals(3, job.segments().stream().filter(GcodeJob.Segment::deposits).count());
        Files.writeString(file, "G20\nG1 X1 E1\n");
        assertThrows(java.io.IOException.class, () -> GcodeReader.read(file));
        Files.writeString(file, "G3 X1 Y1 I1 E1\n");
        assertThrows(java.io.IOException.class, () -> GcodeReader.read(file));
    }
}
