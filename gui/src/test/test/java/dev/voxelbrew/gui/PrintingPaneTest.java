package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class PrintingPaneTest {
    @Test
    void jobDestinationKeepsProjectDirectoryAndReplacesOnlyLastExtension() {
        assertEquals(
                Path.of("parts", "my.part-1-boo.gcode"),
                PrintingPane.jobDestination(Path.of("parts", "my.part.vxb"), "boo", 1, 1));
        assertEquals(Path.of("part-1-Plate 1.gcode"), PrintingPane.jobDestination(Path.of("part"), "Plate 1", 1, 1));
    }

    @Test
    void plateNumberWidthFollowsTotalPlateCount() {
        for (int count : new int[] {3, 9, 10, 33, 99, 100, 999, 1000, 9999, 10000}) {
            String number = "0".repeat(Integer.toString(count).length() - 1) + "3";
            assertEquals(
                    Path.of("Foo-" + number + "-boo.gcode"),
                    PrintingPane.jobDestination(Path.of("Foo.vxb"), "boo", 3, count));
        }
    }

    @Test
    void escapesUnsafeCharactersWithoutLosingUnicodeOrCreatingDirectories() {
        assertEquals(
                Path.of("Foo-03-zażółć %3C%3E%3A%22%2F%5C%7C%3F%2A%25%2E%20.gcode"),
                PrintingPane.jobDestination(Path.of("Foo.vxb"), "zażółć <>:\"/\\|?*%. ", 3, 33));
        assertEquals(Path.of("Foo-1-%2E%2E.gcode"), PrintingPane.jobDestination(Path.of("Foo.vxb"), "..", 1, 1));
        assertNotEquals(
                PrintingPane.jobDestination(Path.of("Foo.vxb"), "/", 1, 1),
                PrintingPane.jobDestination(Path.of("Foo.vxb"), "%2F", 1, 1));
    }
}
