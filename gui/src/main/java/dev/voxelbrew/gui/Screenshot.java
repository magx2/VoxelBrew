package dev.voxelbrew.gui;

import javafx.scene.Scene;
import javafx.scene.image.PixelFormat;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

final class Screenshot {
    private Screenshot() { }
    static void save(Scene scene,Path path) throws IOException {
        var snapshot=scene.snapshot(null);int width=(int)snapshot.getWidth(),height=(int)snapshot.getHeight();
        int[] pixels=new int[width*height];snapshot.getPixelReader().getPixels(0,0,width,height,PixelFormat.getIntArgbInstance(),pixels,0,width);
        var image=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);image.setRGB(0,0,width,height,pixels,0,width);
        Files.createDirectories(path.toAbsolutePath().getParent());ImageIO.write(image,"png",path.toFile());
    }
}
