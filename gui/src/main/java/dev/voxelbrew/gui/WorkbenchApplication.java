package dev.voxelbrew.gui;

import dev.voxelbrew.engine.export.ExporterRegistry;
import dev.voxelbrew.engine.model.Feature;
import java.nio.file.Path;
import javafx.application.Application;
import javafx.application.ConditionalFeature;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Screen;
import javafx.stage.Stage;

public final class WorkbenchApplication extends Application {
    private Workbench workbench;

    @Override
    public void start(Stage stage) throws java.io.IOException {
        if (!Platform.isSupported(ConditionalFeature.SCENE3D))
            throw new IllegalStateException(
                    "JavaFX 3D is unavailable. Check graphics drivers and WSLg/Mesa. For Mesa software rendering use JAVA_OPTS='-Dprism.order=es2 -Dprism.forceGPU=true'.");
        var args = getParameters().getRaw();
        Path plugins = ExporterRegistry.defaultDirectory(), open = null;
        Path smokeOutput =
                Path.of(System.getProperty("voxelbrew.smoke.output", "build/gui-smoke.png"));
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("--plugin-dir") && i + 1 < args.size())
                plugins = Path.of(args.get(++i));
            else if (args.get(i).equals("--open") && i + 1 < args.size())
                open = Path.of(args.get(++i));
            else if (args.get(i).equals("--smoke-output") && i + 1 < args.size())
                smokeOutput = Path.of(args.get(++i));
            else if (!args.get(i).equals("--smoke-test"))
                throw new IllegalArgumentException(
                        "Usage: gui [--open design.jcad] [--plugin-dir directory] [--smoke-test] [--smoke-output screenshot.png]");
        }
        if (open != null && args.contains("--smoke-test"))
            throw new IllegalArgumentException(
                    "--smoke-test creates an unsaved fixture; omit --open");
        workbench = new Workbench(stage, plugins);
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setScene(
                new Scene(
                        workbench,
                        Math.min(1400, screen.getWidth() - 60),
                        Math.min(900, screen.getHeight() - 80)));
        stage.setMinWidth(Math.min(1100, screen.getWidth() - 60));
        stage.setMinHeight(Math.min(700, screen.getHeight() - 80));
        stage.show();
        if (open != null) workbench.controller().open(open).exceptionally(failure -> null);
        if (args.contains("--smoke-test")) {
            var controller = workbench.controller();
            controller.add(
                    new Feature.Sketch(
                            "plate",
                            "plate.profile",
                            new Feature.Plane("origin-xy", 0),
                            new Feature.Rectangle(0, 0, 60, 40)));
            controller.add(
                    new Feature.Extrude(
                            "thickness", new Feature.ProfileRef("plate", "plate.profile"), 8));
            controller.add(
                    new Feature.Sketch(
                            "hole",
                            "hole.profile",
                            new Feature.Plane("origin-xy", 0),
                            new Feature.Circle(10, 10, 4)));
            controller.add(
                    new Feature.ThroughCut(
                            "cut",
                            new Feature.ProfileRef("hole", "hole.profile"),
                            new Feature.BodyRef("thickness", "body")));
            PackageSmoke.verify(workbench, stage, smokeOutput);
        }
    }

    @Override
    public void stop() {
        if (workbench != null) workbench.close();
    }
}
