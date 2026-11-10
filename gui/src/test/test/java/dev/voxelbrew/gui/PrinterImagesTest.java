package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterImagesTest {
    @TempDir
    Path directory;

    @Test
    void decodesWebpWithUppercaseExtension() throws Exception {
        var path = directory.resolve("printer.WEBP");
        try (var source = getClass().getResourceAsStream("webp/small_1x1.webp")) {
            Files.copy(source, path);
        }
        var image = PrinterImages.load(path.toUri().toString());
        assertNotNull(image);
        assertFalse(image.isError());
        assertEquals(1, image.getWidth());
        assertEquals(1, image.getHeight());
        assertNotNull(image.getPixelReader());
    }

    @Test
    void preservesLosslessWebpTransparency() {
        var image = PrinterImages.load(
                getClass().getResource("webp/lossless.transparent.webp").toExternalForm());
        assertNotNull(image);
        boolean transparent = false;
        boolean visible = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getPixelReader().getArgb(x, y) >>> 24;
                transparent |= alpha == 0;
                visible |= alpha > 0;
            }
        }
        assertTrue(transparent);
        assertTrue(visible);
    }

    @Test
    void corruptAndMissingWebpAllowTheDefaultIllustration() throws Exception {
        var path = directory.resolve("broken.webp");
        Files.writeString(path, "not an image");
        assertNull(PrinterImages.load(path.toUri().toString()));
        assertNull(PrinterImages.load(directory.resolve("missing.webp").toUri().toString()));
    }
}
