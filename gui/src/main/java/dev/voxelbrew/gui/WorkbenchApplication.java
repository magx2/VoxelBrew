package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.util.i18n.Translations;
import dev.voxelbrew.util.update.ApplicationImage;
import dev.voxelbrew.util.update.UpdateCancellation;
import dev.voxelbrew.util.update.UpdateService;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Screen;
import javafx.stage.Stage;

@lombok.extern.slf4j.Slf4j
public final class WorkbenchApplication extends Application {
    private Workbench workbench;

    @Override
    public void start(Stage stage) throws java.io.IOException {
        if (!Platform.isSupported(ConditionalFeature.SCENE3D))
            throw new IllegalStateException(
                    dev.voxelbrew.util.i18n.Translations.current().text("graphics.unavailable"));
        var args = getParameters().getRaw();
        Path plugins = dev.voxelbrew.util.config.ApplicationPaths.plugins(), open = null;
        Path smokeOutput = Path.of(System.getProperty("voxelbrew.smoke.output", "build/gui-smoke.png"));
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--plugin-dir") && i + 1 < args.size()) plugins = Path.of(args.get(++i));
            else if (args.get(i).equals("--open") && i + 1 < args.size()) open = Path.of(args.get(++i));
            else if (args.get(i).equals("--smoke-output") && i + 1 < args.size()) smokeOutput = Path.of(args.get(++i));
            else if (!args.get(i).equals("--smoke-test"))
                throw new IllegalArgumentException(
                        "Usage: gui [--open design.vxb] [--plugin-dir directory] [--smoke-test] [--smoke-output screenshot.png]");
        }
        if (open != null && args.contains("--smoke-test"))
            throw new IllegalArgumentException("--smoke-test creates an unsaved fixture; omit --open");
        workbench = openWindow(stage, plugins, open);
        workbench.startMcpIfConfigured();
        checkForUpdates(stage, workbench);
        if (args.contains("--smoke-test")) {
            var controller = workbench.controller();
            controller.add(new Feature.Sketch(
                    "plate", "plate.profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
            controller.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "plate.profile"), 8));
            controller.add(new Feature.Sketch(
                    "hole", "hole.profile", new Feature.Plane("origin-xy", 0), new Feature.Circle(10, 10, 4)));
            controller.add(new Feature.ThroughCut(
                    "cut", new Feature.ProfileRef("hole", "hole.profile"), new Feature.BodyRef("thickness", "body")));
            PackageSmoke.verify(workbench, stage, smokeOutput);
        }
    }

    private static void checkForUpdates(Stage stage, Workbench workbench) {
        Thread.ofVirtual().name("voxelbrew-startup-update").start(() -> {
            try {
                var result = UpdateService.system(ApplicationImage.Component.GUI)
                        .check(new UpdateService.Request(null, false), new UpdateCancellation());
                if (!result.available() || !result.installable()) return;
                Platform.runLater(() -> {
                    if (!stage.isShowing()) return;
                    var translations = Translations.current();
                    var download = new ButtonType(translations.text("update.download"));
                    var prompt = new Alert(Alert.AlertType.INFORMATION, result.message(), download, ButtonType.CANCEL);
                    prompt.setTitle(translations.text("update.title"));
                    prompt.setHeaderText(translations.text("update.heading"));
                    if (prompt.showAndWait().filter(download::equals).isPresent() && stage.isShowing())
                        workbench.openUpdates();
                });
            } catch (Exception failure) {
                log.warn("Startup update check failed", failure);
            }
        });
    }

    static Workbench openWindow(Stage stage, Path plugins, Path open) throws java.io.IOException {
        var workbench = Workbench.create(stage, plugins);
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setScene(
                workbench.createScene(Math.min(1400, screen.getWidth() - 60), Math.min(900, screen.getHeight() - 80)));
        stage.setMinWidth(Math.min(1100, screen.getWidth() - 60));
        stage.setMinHeight(Math.min(700, screen.getHeight() - 80));
        stage.show();
        log.info("Workbench window opened with plugin directory {}", plugins);
        if (open != null) workbench.controller().open(open).exceptionally(failure -> null);
        stage.setOnHidden(event -> workbench.close());
        return workbench;
    }

    @Override
    public void stop() {
        log.info("Closing workbench application");
        if (workbench != null) workbench.close();
    }
}
