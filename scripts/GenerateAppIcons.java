import java.awt.AlphaComposite;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.imageio.ImageIO;

/** Run from the repository root with Java 21; only resizes and packages the original artwork. */
public final class GenerateAppIcons {
    private GenerateAppIcons() {}

    public static void main(String[] args) throws IOException {
        var original =
                ImageIO.read(Path.of("gui/src/main/branding/voxelbrew.png").toFile());
        if (original == null
                || original.getWidth() != original.getHeight()
                || original.getWidth() < 1024
                || !original.getColorModel().hasAlpha()) {
            throw new IOException("Expected square RGBA artwork of at least 1024 pixels");
        }
        var resources = Path.of("gui/src/main/resources/dev/voxelbrew/gui/icons");
        var packaging = Path.of("gui/src/main/packaging");
        Files.createDirectories(resources);
        Files.createDirectories(packaging);
        Map<Integer, byte[]> frames = new LinkedHashMap<>();
        for (int size : new int[] {16, 32, 48, 64, 128, 256, 512, 1024}) {
            var image = original;
            while (image.getWidth() > size) {
                int nextSize = Math.max(size, image.getWidth() / 2);
                var resized = new BufferedImage(nextSize, nextSize, BufferedImage.TYPE_INT_ARGB);
                var graphics = resized.createGraphics();
                try {
                    graphics.setComposite(AlphaComposite.Src);
                    graphics.setRenderingHint(
                            RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    graphics.drawImage(image, 0, 0, nextSize, nextSize, null);
                } finally {
                    graphics.dispose();
                }
                image = resized;
            }
            var output = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", output)) throw new IOException("PNG writer unavailable");
            frames.put(size, output.toByteArray());
            if (size <= 512) {
                Files.write(resources.resolve("voxelbrew-" + size + ".png"), frames.get(size));
            }
        }
        Files.write(packaging.resolve("voxelbrew.ico"), ico(frames));
        Files.write(packaging.resolve("voxelbrew.icns"), icns(frames));
        System.out.println("Generated JavaFX PNGs, Windows ICO and macOS ICNS from original artwork.");
    }

    private static byte[] ico(Map<Integer, byte[]> frames) throws IOException {
        var entries = frames.entrySet().stream()
                .filter(entry -> entry.getKey() <= 256)
                .toList();
        var header = ByteBuffer.allocate(6 + 16 * entries.size()).order(ByteOrder.LITTLE_ENDIAN);
        header.putShort((short) 0).putShort((short) 1).putShort((short) entries.size());
        int offset = header.capacity();
        for (var entry : entries) {
            int size = entry.getKey();
            header.put((byte) (size == 256 ? 0 : size)).put((byte) (size == 256 ? 0 : size));
            header.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            header.putInt(entry.getValue().length).putInt(offset);
            offset += entry.getValue().length;
        }
        var output = new ByteArrayOutputStream();
        output.write(header.array());
        for (var entry : entries) output.write(entry.getValue());
        return output.toByteArray();
    }

    private static byte[] icns(Map<Integer, byte[]> frames) throws IOException {
        int[] sizes = {16, 32, 64, 128, 256, 512, 1024};
        String[] types = {"icp4", "icp5", "icp6", "ic07", "ic08", "ic09", "ic10"};
        var body = new ByteArrayOutputStream();
        for (int i = 0; i < sizes.length; i++) {
            byte[] png = frames.get(sizes[i]);
            body.write(types[i].getBytes(StandardCharsets.US_ASCII));
            body.write(ByteBuffer.allocate(4).putInt(png.length + 8).array());
            body.write(png);
        }
        var output = new ByteArrayOutputStream();
        output.write("icns".getBytes(StandardCharsets.US_ASCII));
        output.write(ByteBuffer.allocate(4).putInt(body.size() + 8).array());
        body.writeTo(output);
        return output.toByteArray();
    }
}
