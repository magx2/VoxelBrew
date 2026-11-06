package dev.voxelbrew.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Point3D;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.util.Duration;

/** Explicit opt-in acceptance command, exercised through the packaged native launcher. */
@lombok.extern.slf4j.Slf4j
final class PackageSmoke {
    private PackageSmoke() {}

    static void verify(Workbench workbench, Stage stage, Path screenshot) {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        awaitBuild(workbench, stage, screenshot, deadline);
    }

    private static void later(double seconds, Runnable action) {
        var pause = new PauseTransition(Duration.seconds(seconds));
        pause.setOnFinished(e -> {
            try {
                action.run();
            } catch (Exception failure) {
                log.error("Packaged GUI smoke check failed", failure);
                System.exit(1);
            }
        });
        pause.play();
    }

    private static void awaitBuild(Workbench w, Stage stage, Path file, long deadline) {
        later(.25, () -> {
            if (w.controller().rebuilding()) {
                if (System.nanoTime() >= deadline) throw new IllegalStateException("Smoke rebuild timed out");
                awaitBuild(w, stage, file, deadline);
                return;
            }
            if (!w.controller().canExport())
                throw new IllegalStateException(
                        "Smoke rebuild failed: " + w.controller().error());
            stage.setAlwaysOnTop(true);
            stage.centerOnScreen();
            stage.toFront();
            stage.requestFocus();
            w.viewport().standardView("Top");
            boolean[] picked = {false};
            stage.getScene().addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
                var result = event.getPickResult();
                if (result.getIntersectedNode() != null
                        && "body-thickness.body"
                                .equals(result.getIntersectedNode().getId())
                        && result.getIntersectedFace() >= 0) picked[0] = true;
            });
            later(1, () -> {
                var point = w.viewport().modelToScreen(new Point3D(30, 20, 8));
                if (point == null) throw new IllegalStateException("Reference body is outside the viewport");
                var robot = new Robot();
                robot.mouseMove(point.getX(), point.getY());
                later(.2, () -> {
                    robot.mousePress(MouseButton.PRIMARY);
                    later(.1, () -> {
                        robot.mouseRelease(MouseButton.PRIMARY);
                        later(.5, () -> {
                            if (!picked[0])
                                throw new IllegalStateException("Packaged GUI did not pick the rendered body");
                            try {
                                Screenshot.save(stage.getScene(), file);
                                String result = "GUI_SMOKE_PASS rendered and picked OCCT reference; Java "
                                        + System.getProperty("java.runtime.version")
                                        + "; runtime="
                                        + System.getProperty("java.home");
                                String filename = file.getFileName().toString();
                                Files.writeString(
                                        file.resolveSibling(filename.replaceFirst("\\.[^.]+$", "") + ".txt"), result);
                                System.out.println(result);
                            } catch (java.io.IOException failure) {
                                throw new java.io.UncheckedIOException(failure);
                            }
                            w.close();
                            stage.hide();
                            Platform.exit();
                        });
                    });
                });
            });
        });
    }
}
