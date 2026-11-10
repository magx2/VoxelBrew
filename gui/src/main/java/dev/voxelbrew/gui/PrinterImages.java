package dev.voxelbrew.gui;

import java.net.URI;
import java.util.Locale;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Decodes custom WebP thumbnails without requiring native codecs or JavaFX Swing. */
final class PrinterImages {
    private PrinterImages() {}

    static Image load(String location) {
        try {
            if (!location.toLowerCase(Locale.ROOT).endsWith(".webp")) {
                return new Image(location, 96, 72, true, true);
            }
            try (var stream = URI.create(location).toURL().openStream();
                    var input = new MemoryCacheImageInputStream(stream)) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) return null;
                var reader = readers.next();
                try {
                    reader.setInput(input);
                    int width = reader.getWidth(0);
                    int height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || (long) width * height > 16_000_000) return null;
                    var decoded = reader.read(0);
                    var image = new WritableImage(width, height);
                    image.getPixelWriter()
                            .setPixels(
                                    0,
                                    0,
                                    width,
                                    height,
                                    PixelFormat.getIntArgbInstance(),
                                    decoded.getRGB(0, 0, width, height, null, 0, width),
                                    0,
                                    width);
                    return image;
                } finally {
                    reader.dispose();
                }
            }
        } catch (java.io.IOException | RuntimeException ex) {
            return null;
        }
    }
}
