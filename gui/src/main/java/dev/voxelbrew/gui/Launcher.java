package dev.voxelbrew.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.application.Application;

/** Separate main class allows the non-modular development distribution to launch JavaFX. */
@lombok.extern.slf4j.Slf4j
public final class Launcher {
    private Launcher() {}

    @SuppressWarnings("try") // The update helper waits until all app instances release this lease.
    public static void main(String[] args) throws java.io.IOException {
        dev.voxelbrew.util.logging.Logging.initialize("gui");
        try (var lease = dev.voxelbrew.util.update.ApplicationImage.running(
                dev.voxelbrew.util.update.ApplicationImage.Component.GUI)) {
            if (args.length == 1 && args[0].equals("--version")) {
                System.out.println("VoxelBrew " + dev.voxelbrew.util.update.BuildVersion.current());
                return;
            }
            launch(args);
        } catch (java.io.IOException | RuntimeException | Error failure) {
            log.error("GUI terminated unexpectedly", failure);
            throw failure;
        } finally {
            // JUL closes handlers on JVM shutdown, after window-owned workers finish draining.
            log.info("GUI launcher finished");
        }
    }

    private static void launch(String[] args) {
        configureGraphics();
        if (System.getProperty("voxelbrew.native.path") == null) {
            try {
                Path jar = Path.of(Launcher.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI());
                Path runtime = jar.getParent().resolve("native");
                if (!Files.isDirectory(runtime))
                    runtime = jar.getParent().getParent().resolve("native");
                if (Files.isDirectory(runtime)) System.setProperty("voxelbrew.native.path", runtime.toString());
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
            if (System.getProperty("prism.forceGPU") == null) System.setProperty("prism.forceGPU", "true");
            if (System.getProperty("prism.order") == null) System.setProperty("prism.order", "es2");
        }
    }
}
