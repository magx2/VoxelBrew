package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PrintingPaneTest {
    @Test
    void jobDestinationKeepsProjectDirectoryAndReplacesOnlyLastExtension() {
        assertEquals(Path.of("parts", "my.part.gcode"), PrintingPane.jobDestination(Path.of("parts", "my.part.vxb")));
        assertEquals(Path.of("part.gcode"), PrintingPane.jobDestination(Path.of("part")));
    }
}
