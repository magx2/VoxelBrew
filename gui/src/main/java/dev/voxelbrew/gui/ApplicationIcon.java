package dev.voxelbrew.gui;

import java.util.List;
import java.util.Objects;
import java.util.stream.IntStream;
import javafx.scene.image.Image;
import javafx.stage.Stage;

/** Shared artwork for every workbench window and the native application launchers. */
final class ApplicationIcon {
    private static final List<Image> IMAGES = IntStream.of(16, 32, 48, 64, 128, 256, 512)
            .mapToObj(size -> {
                var resource = Objects.requireNonNull(
                        ApplicationIcon.class.getResource("icons/voxelbrew-" + size + ".png"),
                        "Missing application icon");
                var image = new Image(resource.toExternalForm());
                if (image.isError()) {
                    throw new IllegalStateException("Cannot load application icon", image.getException());
                }
                return image;
            })
            .toList();

    private ApplicationIcon() {}

    static void install(Stage stage) {
        stage.getIcons().setAll(IMAGES);
    }
}
