package dev.voxelbrew.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;

/** Separate main class allows the non-modular development distribution to launch JavaFX. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        configureGraphics();
        if (System.getProperty("voxelbrew.native.path") == null) {
            try {
                Path jar =
                        Path.of(
                                Launcher.class
                                        .getProtectionDomain()
                                        .getCodeSource()
                                        .getLocation()
                                        .toURI());
                Path runtime = jar.getParent().getParent().resolve("native");
                if (Files.isDirectory(runtime))
                    System.setProperty("voxelbrew.native.path", runtime.toString());
            } catch (java.net.URISyntaxException ignored) {
                /* Kernel supplies the load diagnostic. */
            }
        }
        Application.launch(WorkbenchApplication.class, args);
    }

    static void configureGraphics() {
        // WSLg's llvmpipe OpenGL implementation supports 3D but is not on Prism's hardware
        // whitelist.
        if (System.getenv("WSL_DISTRO_NAME") != null) {
            if (System.getProperty("prism.forceGPU") == null)
                System.setProperty("prism.forceGPU", "true");
            if (System.getProperty("prism.order") == null) System.setProperty("prism.order", "es2");
        }
    }
}
