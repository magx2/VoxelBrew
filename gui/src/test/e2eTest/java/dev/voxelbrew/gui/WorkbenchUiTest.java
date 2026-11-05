package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.SketchGeometry;
import dev.voxelbrew.engine.sketch.SketchTools;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.exporter.printing.PrusaSlicerBackend;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.importer.GcodeReader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.*;
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Polygon;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

/** Actual JavaFX windows, rendering, picking, keyboard input and mouse gestures on both OSes. */
class WorkbenchUiTest {
    @TempDir Path directory;
    private Stage stage;
    private Workbench workbench;
    private static Robot robot;
    private static Appearance.Choice originalAppearance;
    private UpdateFixture updates;

    @BeforeAll
    static void toolkit() throws Exception {
        originalAppearance = new Appearance().load();
        var started = new CountDownLatch(1);
        Launcher.configureGraphics();
        Platform.startup(
                () -> {
                    Platform.setImplicitExit(false);
                    started.countDown();
                });
        assertTrue(started.await(20, TimeUnit.SECONDS));
        fx(
                () -> {
                    assertTrue(
                            Platform.isSupported(ConditionalFeature.SCENE3D),
                            "Real JavaFX 3D is required; use WSLg or Xvfb/Mesa");
                    robot = new Robot();
                    return null;
                });
    }

    @AfterAll
    static void shutdown() {
        new Appearance().save(originalAppearance);
        Platform.exit();
    }

    @BeforeEach
    void window() throws Exception {
        updates = new UpdateFixture(directory.resolve("update fixture"));
        Path plugins = Files.createDirectory(directory.resolve("plugins with spaces"));
        Files.copy(
                Path.of(System.getProperty("voxelbrew.test.plugin")),
                plugins.resolve("provider.jar"));
        fx(
                () -> {
                    stage = new Stage();
                    if (System.getProperty("os.name").startsWith("Windows"))
                        stage.setAlwaysOnTop(true);
                    workbench = Workbench.create(stage, plugins, updates::service);
                    var screen = Screen.getPrimary().getVisualBounds();
                    // Match the hosted Windows runner so clipped inspector fields require
                    // scrolling.
                    stage.setScene(
                            new Scene(
                                    workbench,
                                    Math.min(964, screen.getWidth() - 60),
                                    Math.min(648, screen.getHeight() - 80)));
                    stage.show();
                    // JavaFX 21's Windows Robot converts coordinates using the nearest monitor
                    // center. Keep the fixture away from edges shared with a different DPI screen.
                    stage.setX(screen.getMinX() + (screen.getWidth() - stage.getWidth()) / 2);
                    stage.setY(screen.getMinY() + (screen.getHeight() - stage.getHeight()) / 2);
                    stage.toFront();
                    stage.requestFocus();
                    return null;
                });
        Thread.sleep(1200);
    }

    @AfterEach
    void close(TestInfo info) throws Exception {
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(),
                            Path.of(
                                    System.getProperty("voxelbrew.ui.artifacts"),
                                    info.getTestMethod().orElseThrow().getName() + "-last.png"));
                    int index = 0;
                    for (var window : new ArrayList<>(Window.getWindows()))
                        if (window != stage) {
                            if (window.getScene() != null)
                                Screenshot.save(
                                        window.getScene(),
                                        Path.of(
                                                System.getProperty("voxelbrew.ui.artifacts"),
                                                info.getTestMethod().orElseThrow().getName()
                                                        + "-dialog-"
                                                        + (index++)
                                                        + ".png"));
                            window.hide();
                        }
                    workbench.close();
                    stage.hide();
                    return null;
                });
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    @Test
    void daggerGraphsIsolateWindowsPreferencesAndWorkerLifetimes() throws Exception {
        fx(
                () -> {
                    var config =
                            new dev.voxelbrew.util.config.UserConfiguration(
                                    directory.resolve("isolated.properties"));
                    config.set("language", "pl");
                    config.set("appearance", "DARK");
                    var secondStage = new Stage();
                    var graph =
                            DaggerGuiComponent.factory()
                                    .create(
                                            secondStage,
                                            directory.resolve("isolated plugins"),
                                            new dev.voxelbrew.util.di.ApplicationModule(
                                                    config,
                                                    () -> {
                                                        throw new AssertionError(
                                                                "Startup must not create an updater");
                                                    }),
                                            new dev.voxelbrew.exporter.di.FileIoModule());
                    var second = graph.workbench();
                    try {
                        secondStage.setScene(new Scene(second, 964, 648));
                        secondStage.show();
                        assertSame(second, graph.workbench());
                        assertNotSame(workbench.controller(), second.controller());
                        assertNotEquals(
                                workbench.controller().snapshot().document().id(),
                                second.controller().snapshot().document().id());
                        assertEquals(
                                new dev.voxelbrew.util.i18n.Translations(
                                                Locale.forLanguageTag("pl"))
                                        .text("action.save"),
                                ((Button) second.lookup("#save")).getText());
                        assertTrue(second.getStyleClass().contains("dark"));
                        second.close();
                        second.close();
                        var resources = graph.resources();
                        assertTrue(
                                List.of(
                                                resources.rebuild,
                                                resources.files,
                                                resources.slicing,
                                                resources.validation,
                                                resources.appearance)
                                        .stream()
                                        .allMatch(ExecutorService::isShutdown));
                        workbench.controller().newDocument();
                        assertFalse(workbench.controller().ioBusy());
                    } finally {
                        second.close();
                        secondStage.hide();
                    }
                    return null;
                });
        ready();
    }

    private Node node(String selector) {
        var windows = new ArrayList<>(Window.getWindows());
        Collections.reverse(windows);
        for (var window : windows)
            if (window.isShowing() && window.getScene() != null) {
                var node = window.getScene().lookup(selector);
                if (node != null && node.isVisible()) return node;
            }
        throw new AssertionError("Missing control " + selector);
    }

    private void click(String id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(
                        () -> {
                            try {
                                node("#" + id);
                                return true;
                            } catch (AssertionError missing) {
                                return false;
                            }
                        })
                && System.nanoTime() < deadline) Thread.sleep(40);
        var window = fx(() -> node("#" + id).getScene().getWindow());
        focus(window);
        reveal(id);
        fx(
                () -> {
                    window.getScene().getRoot().applyCss();
                    window.getScene().getRoot().layout();
                    var node = node("#" + id);
                    assertFalse(node.isDisabled(), id + " is disabled");
                    var bounds = node.localToScreen(node.getBoundsInLocal());
                    assertNotNull(bounds);
                    assertTrue(
                            bounds.getCenterY() < window.getY() + window.getHeight(),
                            "Control outside window: " + id);
                    robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                    return null;
                });
        Thread.sleep(120);
        fx(
                () -> {
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(60);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(180);
    }

    private void reveal(String id) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            int direction =
                    fx(
                            () -> {
                                var target = node("#" + id);
                                target.getScene().getRoot().applyCss();
                                target.getScene().getRoot().layout();
                                var bounds = target.localToScreen(target.getBoundsInLocal());
                                for (var parent = target.getParent();
                                        parent != null;
                                        parent = parent.getParent()) {
                                    if (!(parent instanceof ScrollPane scroll)) continue;
                                    var viewport = scroll.lookup(".viewport");
                                    var visible =
                                            viewport.localToScreen(viewport.getBoundsInLocal());
                                    if (bounds.getMinY() >= visible.getMinY()
                                            && bounds.getMaxY() <= visible.getMaxY()) continue;
                                    robot.mouseMove(visible.getCenterX(), visible.getCenterY());
                                    return bounds.getCenterY() < visible.getCenterY() ? -3 : 3;
                                }
                                return 0;
                            });
            if (direction == 0) return;
            Thread.sleep(100);
            fx(
                    () -> {
                        robot.mouseWheel(direction);
                        return null;
                    });
            Thread.sleep(150);
        }
        fail("Could not scroll control into view: " + id);
    }

    private void focus(Window window) throws Exception {
        if (!fx(window::isFocused)) {
            fx(
                    () -> {
                        if (window instanceof Stage target) target.toFront();
                        window.requestFocus();
                        return null;
                    });
            Thread.sleep(200);
            if (!fx(window::isFocused)) {
                fx(
                        () -> {
                            var point = window.getScene().getRoot().localToScreen(20, 20);
                            robot.mouseMove(point);
                            robot.mouseClick(MouseButton.PRIMARY);
                            return null;
                        });
                Thread.sleep(150);
                fx(
                        () -> {
                            window.requestFocus();
                            return null;
                        });
                Thread.sleep(200);
            }
        }
    }

    private void key(KeyCode code) throws Exception {
        fx(
                () -> {
                    robot.keyType(code);
                    return null;
                });
        Thread.sleep(100);
    }

    private void shortcut(KeyCode code, boolean shift) throws Exception {
        KeyCode modifier =
                System.getProperty("os.name").startsWith("Mac") ? KeyCode.META : KeyCode.CONTROL;
        focus(stage);
        assertTrue(fx(stage::isFocused), "Window focus before shortcut");
        fx(
                () -> {
                    robot.keyPress(modifier);
                    return null;
                });
        Thread.sleep(60);
        if (shift) {
            fx(
                    () -> {
                        robot.keyPress(KeyCode.SHIFT);
                        return null;
                    });
            Thread.sleep(60);
        }
        key(code);
        if (shift) {
            fx(
                    () -> {
                        robot.keyRelease(KeyCode.SHIFT);
                        return null;
                    });
            Thread.sleep(60);
        }
        fx(
                () -> {
                    robot.keyRelease(modifier);
                    return null;
                });
        Thread.sleep(250);
    }

    private void text(String id, String value) throws Exception {
        click(id);
        var target = fx(() -> (TextField) node("#" + id));
        for (int attempt = 0; attempt < 2 && !fx(target::isFocused); attempt++) {
            Thread.sleep(250);
            click(id);
        }
        assertTrue(fx(target::isFocused), "Focused field: " + id);
        int length = fx(target::getLength);
        key(KeyCode.HOME);
        assertSame(target, fx(() -> node("#" + id)), "Stable inspector field: " + id);
        assertEquals(0, fx(target::getCaretPosition), "Home key: " + id);
        for (int i = 0; i < length; i++) {
            fx(
                    () -> {
                        robot.keyType(KeyCode.DELETE);
                        return null;
                    });
            Thread.sleep(20);
        }
        Thread.sleep(150);
        assertEquals(
                "",
                fx(() -> ((TextField) node("#" + id)).getText()),
                "Robot field clearing: " + id);
        for (char c : value.toCharArray()) {
            fx(
                    () -> {
                        boolean shift =
                                Character.isUpperCase(c) || c == ':' || c == '_' || c == '~';
                        KeyCode code;
                        if (Character.isLetter(c))
                            code = KeyCode.valueOf(Character.toString(Character.toUpperCase(c)));
                        else if (Character.isDigit(c)) code = KeyCode.valueOf("DIGIT" + c);
                        else
                            code =
                                    switch (c) {
                                        case '.' -> KeyCode.PERIOD;
                                        case '-', '_' -> KeyCode.MINUS;
                                        case '/' -> KeyCode.SLASH;
                                        case '\\' -> KeyCode.BACK_SLASH;
                                        case ':' -> KeyCode.SEMICOLON;
                                        case '`', '~' -> KeyCode.BACK_QUOTE;
                                        case ' ' -> KeyCode.SPACE;
                                        default ->
                                                throw new IllegalArgumentException(
                                                        "Unsupported test character " + c);
                                    };
                        if (shift) robot.keyPress(KeyCode.SHIFT);
                        robot.keyType(code);
                        if (shift) robot.keyRelease(KeyCode.SHIFT);
                        return null;
                    });
            Thread.sleep(12);
        }
        Thread.sleep(120);
        assertEquals(
                value, fx(() -> ((TextField) node("#" + id)).getText()), "Robot text entry: " + id);
    }

    private void choice(String id, int index) throws Exception {
        click(id);
        // The native popup can appear after a later pulse when switching workspace tabs.
        // Wait for the observed popup; do not replay the click or set its selection in code.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fx(() -> ((ComboBox<?>) node("#" + id)).isShowing())
                && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(fx(() -> ((ComboBox<?>) node("#" + id)).isShowing()), "Popup opened: " + id);
        // Popup windows can select a different DPI monitor than their owner in Windows Robot.
        // Exercise the actual opened popup through native keys, without setting selection in code.
        key(KeyCode.HOME);
        for (int row = 0; row < index; row++) key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        assertEquals(
                index,
                fx(() -> ((ComboBox<?>) node("#" + id)).getSelectionModel().getSelectedIndex()),
                "Combo selection: " + id);
    }

    private void ready() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (fx(
                    () ->
                            !workbench.controller().rebuilding()
                                    && !workbench.controller().ioBusy())) {
                Thread.sleep(150);
                return;
            }
            Thread.sleep(25);
        }
        fail("GUI operation timed out");
    }

    private CadDocument document() throws Exception {
        return fx(() -> workbench.controller().snapshot().document());
    }

    @Test
    void updatesCheckCancelAndProtectUnsavedDocument() throws Exception {
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            updates.slow = true;
            click("updates");
            click("update-check");
            int request = updates.requests;
            click("update-cancel");
            updates.slow = false;
            click("updates");
            text("update-version", "1.1.0");
            click("update-force");
            click("update-check");
            waitForUpdate("Update available");
            assertTrue(updates.requests > request);
            assertEquals(
                    dev.voxelbrew.util.update.ReleaseVersion.release("1.1.0"), updates.requested);
            fx(
                    () -> {
                        Screenshot.save(
                                stage.getScene(),
                                Path.of(
                                        System.getProperty("voxelbrew.ui.artifacts"),
                                        "updates-" + palette + ".png"));
                        return null;
                    });
            click("update-cancel");
        }
        fx(
                () -> {
                    workbench
                            .controller()
                            .add(
                                    new Feature.Sketch(
                                            "update-sketch",
                                            "profile",
                                            new Feature.Plane("xy", 0),
                                            new Feature.Rectangle(0, 0, 20, 10)));
                    return null;
                });
        ready();
        var before = document();
        click("updates");
        click("update-check");
        waitForUpdate("Update available");
        click("update-download");
        waitForUpdate("Verified and ready");
        click("update-install");
        click("unsaved-cancel");
        assertEquals(before, document());
        assertTrue(fx(() -> stage.isShowing()));
        assertFalse(fx(() -> node("#update-install").isDisabled()));
        click("update-cancel");
        ready();
        assertEquals(before, document());
        // Cancel removes staging after background work has stopped.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean exists;
        do {
            try (var files = Files.walk(directory.resolve("update fixture"))) {
                exists =
                        files.anyMatch(
                                path ->
                                        path.getFileName()
                                                .toString()
                                                .startsWith(".VoxelBrew-update-"));
            }
            if (exists) Thread.sleep(50);
        } while (exists && System.nanoTime() < deadline);
        assertFalse(exists);
    }

    private void waitForUpdate(String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (fx(() -> ((Label) node("#update-status")).getText().contains(message))) return;
            Thread.sleep(30);
        }
        fail(fx(() -> ((Label) node("#update-status")).getText()));
    }

    private double volume() throws Exception {
        return fx(
                () ->
                        workbench.controller().build().bodies().stream()
                                .mapToDouble(DocumentController.Body::volume)
                                .sum());
    }

    private void modelClick(double x, double y, double z) throws Exception {
        fx(
                () -> {
                    var point = workbench.viewport().modelToScreen(new Point3D(x, y, z));
                    assertNotNull(point);
                    robot.mouseMove(point);
                    return null;
                });
        Thread.sleep(80);
        fx(
                () -> {
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(60);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(180);
    }

    private void rectangle(double offset) throws Exception {
        click("rectangle");
        modelClick(0, 0, 0);
        modelClick(60, 40, 0);
        assertEquals(
                60,
                fx(() -> Double.parseDouble(((TextField) node("#sketch-width")).getText())),
                0.6);
        text("sketch-x", "0");
        text("sketch-y", "0");
        text("sketch-width", "60");
        text("sketch-height", "40");
        text("sketch-offset", Double.toString(offset));
        click("finish-sketch");
        ready();
    }

    private void save(Path path) throws Exception {
        click("save");
        text("file-path", path.toString());
        click("file-confirm");
        ready();
        assertTrue(Files.exists(path));
    }

    private void sketchReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> ((SketchEditor) node("#sketch-editor")).controller().busy())
                && System.nanoTime() < deadline) Thread.sleep(25);
        assertFalse(fx(() -> ((SketchEditor) node("#sketch-editor")).controller().busy()));
        Thread.sleep(100);
    }

    private void sketchClick(double x, double y, boolean shift) throws Exception {
        fx(
                () -> {
                    var point =
                            ((SketchEditor) node("#sketch-editor"))
                                    .modelToScreen(new SketchGeometry.Vec(x, y));
                    assertNotNull(point);
                    robot.mouseMove(point);
                    if (shift) robot.keyPress(KeyCode.SHIFT);
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(60);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    if (shift) robot.keyRelease(KeyCode.SHIFT);
                    return null;
                });
        Thread.sleep(150);
    }

    private void sketchDimension(int type, String value) throws Exception {
        choice("sketch-dimension-type", type);
        text("sketch-dimension-value", value);
        click("sketch-apply-dimension");
        sketchReady();
    }

    private void sketchType(SketchTools.Tool tool) throws Exception {
        choice("sketch-create-type", tool.ordinal());
    }

    private void open(Path file) throws Exception {
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
    }

    private void sketchNumericPoint(double x, double y) throws Exception {
        text("sketch-point-x", Double.toString(x));
        text("sketch-point-y", Double.toString(y));
        click("sketch-add-point");
        sketchReady();
    }

    private SketchGeometry sketchGeometry() throws Exception {
        return fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
    }

    private void parameter(String name, String expression) throws Exception {
        click("parameter-new");
        text("parameter-name", name);
        text("parameter-expression", expression);
        click("parameter-save");
        assertEquals("", fx(() -> ((Label) node("#parameters-error")).getText()));
    }

    private void parameterTab(int index) throws Exception {
        var position =
                fx(
                        () -> {
                            var tabs = (TabPane) node("#parameters-tabs");
                            var headers =
                                    tabs.lookupAll(".tab").stream()
                                            .filter(Node::isVisible)
                                            .sorted(
                                                    java.util.Comparator.comparingDouble(
                                                            n ->
                                                                    n.localToScreen(
                                                                                    n
                                                                                            .getBoundsInLocal())
                                                                            .getMinX()))
                                            .toList();
                            return headers.get(index)
                                    .localToScreen(headers.get(index).getBoundsInLocal());
                        });
        fx(
                () -> {
                    robot.mouseMove(position.getCenterX(), position.getCenterY());
                    robot.mouseClick(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(200);
        assertEquals(
                index,
                fx(
                        () ->
                                ((TabPane) node("#parameters-tabs"))
                                        .getSelectionModel()
                                        .getSelectedIndex()));
    }

    private void tableRow(String id, int index) throws Exception {
        click(id);
        key(KeyCode.HOME);
        for (int i = 0; i < index; i++) key(KeyCode.DOWN);
        assertEquals(
                index,
                fx(() -> ((TableView<?>) node("#" + id)).getSelectionModel().getSelectedIndex()));
    }

    @Test
    void namedSketchParametersAnd3dConfigurationsDriveOneDesign() throws Exception {
        choice("appearance", 2);
        click("constrained-sketch");
        sketchReady();
        click("sketch-parameters");
        choice("parameter-unit", 1); // centimeters, converted to model millimeters by JSR 385
        parameter("width", "6");
        choice("parameter-unit", 0);
        parameter("thickness", "5");
        click("parameters-apply");
        sketchReady();
        click("sketch-tool-rectangle");
        sketchClick(0, 0, false);
        sketchClick(60, 40, false);
        sketchReady();
        String bottom = sketchGeometry().entities().getFirst().id();
        sketchReference(bottom);
        choice("sketch-dimension-type", 0);
        text("sketch-dimension-value", "width");
        click("sketch-apply-dimension");
        sketchReady();
        assertEquals(
                "width",
                fx(
                        () ->
                                ((SketchEditor) node("#sketch-editor"))
                                        .controller()
                                        .parameters()
                                        .bindings()
                                        .getFirst()
                                        .expression()));
        click("sketch-clear-selection");
        sketchReference(sketchGeometry().entities().get(1).id());
        text("sketch-dimension-value", "40");
        click("sketch-apply-dimension");
        sketchReady();
        click("constraint-finish");
        ready();
        click("extrude");
        text("extrude-distance", "thickness");
        click("commit-extrusion");
        ready();
        assertEquals(12000, volume(), .01);
        String body = fx(() -> workbench.controller().build().bodies().getFirst().id());
        var featureIds =
                fx(
                        () ->
                                workbench.controller().snapshot().document().features().stream()
                                        .map(Feature::id)
                                        .toList());
        click("parameters");
        parameterTab(1);
        text("configuration-name", "Large");
        click("configuration-add");
        tableRow("configuration-table", 1);
        choice("configuration-parameter", 0);
        text("configuration-expression", "8");
        click("configuration-set");
        choice("configuration-parameter", 1);
        text("configuration-expression", "8");
        click("configuration-set");
        click("configuration-activate");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(),
                            artifacts.resolve("parameter-configurations-dark.png"));
                    return null;
                });
        click("parameters-apply");
        ready();
        assertEquals(25600, volume(), .02);
        assertEquals(body, fx(() -> workbench.controller().build().bodies().getFirst().id()));
        click("undo");
        ready();
        assertEquals(12000, volume(), .02);
        click("redo");
        ready();
        assertEquals(25600, volume(), .02);
        assertEquals(
                featureIds,
                fx(
                        () ->
                                workbench.controller().snapshot().document().features().stream()
                                        .map(Feature::id)
                                        .toList()));
        Path file = directory.resolve("parameter versions.jcad");
        save(file);
        open(file);
        ready();
        assertEquals(
                "Large",
                fx(() -> workbench.controller().snapshot().document().parameters().activeName()));
        choice("active-configuration", 0);
        ready();
        assertEquals(12000, volume(), .02);
        choice("active-configuration", 1);
        ready();
        assertEquals(25600, volume(), .02);
        click("save");
        ready();
        String inspection = cli("inspect", file.toString());
        var measured =
                java.util.regex.Pattern.compile("volume=([0-9.eE+\\-]+)").matcher(inspection);
        assertTrue(measured.find(), inspection);
        assertEquals(25600, Double.parseDouble(measured.group(1)), .02, inspection);
        Files.copy(
                file,
                artifacts.resolve("gui-parameter-variants.jcad"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("parameter-cli-inspection.txt"), inspection);
    }

    @Test
    void parameterRepairRenameAndSketchCancellationUseRealControls() throws Exception {
        choice("appearance", 1);
        rectangle(0);
        click("parameters");
        parameter("width", "60");
        click("parameters-apply");
        ready();
        text("dimension-width", "width");
        click("apply-width");
        ready();
        var before = fx(() -> workbench.controller().snapshot());
        click("parameters");
        tableRow("parameter-table", 0);
        text("parameter-expression", "missing");
        click("parameter-save");
        assertTrue(
                fx(
                        () ->
                                ((Label) node("#parameters-error"))
                                        .getText()
                                        .contains("Unknown parameter")));
        text("parameter-expression", "60");
        choice("parameter-unit", 5); // reject an angle parameter bound to a length
        click("parameter-save");
        assertTrue(
                fx(
                        () ->
                                ((Label) node("#parameters-error"))
                                        .getText()
                                        .toLowerCase(java.util.Locale.ROOT)
                                        .contains("incompatible")));
        click("parameters-cancel");
        assertEquals(before, fx(() -> workbench.controller().snapshot()));
        click("parameters");
        tableRow("parameter-table", 0);
        text("parameter-name", "plateWidth");
        click("parameter-save");
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(),
                            Path.of(
                                    System.getProperty("voxelbrew.ui.artifacts"),
                                    "parameters-light.png"));
                    return null;
                });
        click("parameters-apply");
        ready();
        assertEquals("plateWidth", fx(() -> ((TextField) node("#dimension-width")).getText()));
        before = fx(() -> workbench.controller().snapshot());
        click("edit-sketch-constraints");
        sketchReady();
        click("sketch-parameters");
        parameter("temporary", "12");
        click("parameters-apply");
        sketchReady();
        click("constraint-cancel");
        assertEquals(before, fx(() -> workbench.controller().snapshot()));
        click("edit-sketch-constraints");
        sketchReady();
        click("constraint-finish");
        ready();
        assertEquals(
                "constraint:width",
                fx(
                        () ->
                                workbench
                                        .controller()
                                        .snapshot()
                                        .document()
                                        .parameters()
                                        .bindings()
                                        .getFirst()
                                        .target()
                                        .field()));
        click("extrude");
        click("commit-extrusion");
        ready();
        assertEquals(12000, volume(), .01);
        click("parameters");
        parameterTab(1);
        text("configuration-name", "Invalid");
        click("configuration-add");
        tableRow("configuration-table", 1);
        text("configuration-expression", "0");
        click("configuration-set");
        click("configuration-activate");
        click("parameters-apply");
        ready();
        assertFalse(fx(() -> workbench.controller().build().valid()));
        assertTrue(fx(() -> workbench.controller().build().bodies().isEmpty()));
        assertTrue(fx(() -> node("#export").isDisabled()));
        choice("active-configuration", 0);
        ready();
        assertEquals(12000, volume(), .01);
        assertTrue(fx(() -> workbench.controller().canExport()));
    }

    @Test
    void ellipseSlotsAndConicsUseRealSketchTools() throws Exception {
        choice("appearance", 1);
        click("constrained-sketch");
        sketchReady();
        sketchType(SketchTools.Tool.ELLIPSE);
        sketchClick(15, 10, false);
        sketchClick(25, 10, false);
        sketchClick(15, 15, false);
        sketchReady();
        String ellipse = sketchGeometry().entities().getFirst().id();
        sketchReference(ellipse);
        sketchDimension(7, "10");
        sketchDimension(8, "5");
        sketchType(SketchTools.Tool.SLOT_CENTER_TO_CENTER);
        sketchNumericPoint(5, 30);
        sketchNumericPoint(25, 30);
        sketchNumericPoint(15, 32);
        sketchType(SketchTools.Tool.CONIC);
        text("sketch-tool-rho", Double.toString(Math.sqrt(.5) / (1 + Math.sqrt(.5))));
        sketchNumericPoint(45, 10);
        sketchNumericPoint(35, 20);
        sketchNumericPoint(45, 20);
        click("sketch-tool-line");
        sketchClick(35, 20, false);
        sketchClick(45, 10, false);
        sketchReady();
        assertTrue(
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().canFinish()),
                fx(() -> ((Label) node("#sketch-error")).getText()));
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(),
                            artifacts.resolve("sketch-types-ellipse-slot-conic.png"));
                    return null;
                });
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertEquals(3, fx(() -> workbench.controller().build().bodies().size()));
        assertEquals((50 * Math.PI + 80 + 4 * Math.PI + 25 * Math.PI - 50) * 5, volume(), .02);
        var file = directory.resolve("ellipse slot conic.jcad");
        save(file);
        open(file);
        ready();
        assertEquals(3, fx(() -> workbench.controller().build().bodies().size()));
    }

    @Test
    void fitControlSplinesAndTextUseRealSketchTools() throws Exception {
        choice("appearance", 2);
        click("constrained-sketch");
        sketchReady();
        sketchType(SketchTools.Tool.FIT_SPLINE);
        click("sketch-closed-curve");
        sketchNumericPoint(0, 0);
        sketchNumericPoint(15, 0);
        sketchNumericPoint(15, 15);
        sketchNumericPoint(0, 15);
        click("sketch-complete-curve");
        sketchReady();
        sketchType(SketchTools.Tool.CONTROL_SPLINE);
        choice("sketch-spline-degree", 1);
        sketchNumericPoint(0, 35);
        sketchNumericPoint(0, 45);
        sketchNumericPoint(10, 55);
        sketchNumericPoint(20, 55);
        sketchNumericPoint(30, 45);
        sketchNumericPoint(30, 35);
        click("sketch-complete-curve");
        sketchReady();
        var before = sketchGeometry();
        sketchDrag(0, 35, 2, 37, false);
        var control =
                sketchGeometry().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.CONTROL_SPLINE)
                        .findFirst()
                        .orElseThrow();
        assertEquals(2, control.value(0), .3);
        assertEquals(37, control.value(1), .3);
        click("sketch-undo");
        sketchReady();
        assertEquals(before, sketchGeometry());
        sketchType(SketchTools.Tool.TEXT);
        text("sketch-text-content", "OB");
        text("sketch-text-height", "8");
        sketchNumericPoint(40, 10);
        String textId =
                sketchGeometry().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.TEXT)
                        .findFirst()
                        .orElseThrow()
                        .id();
        sketchReference(textId);
        click("sketch-edit-text");
        text("sketch-edit-text-content", "OC");
        key(KeyCode.ENTER);
        sketchReady();
        assertEquals("OC", sketchGeometry().entity(textId).text().content());
        click("sketch-fit");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(),
                            artifacts.resolve("sketch-types-splines-text-dark.png"));
                    return null;
                });
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertEquals(4, fx(() -> workbench.controller().build().bodies().size()));
        var file = directory.resolve("splines and editable text.jcad");
        save(file);
        var saved = document();
        click("undo");
        ready();
        assertTrue(fx(() -> workbench.controller().build().bodies().isEmpty()));
        click("redo");
        ready();
        assertEquals(saved, document());
        open(file);
        ready();
        assertEquals(saved, document());
        Files.writeString(
                artifacts.resolve("sketch-types-cli-inspection.txt"),
                cli("inspect", file.toString(), "--point", "5,5,2"));
    }

    @Test
    void tangentCirclesAndAssociativeTextPathsUseRealControls() throws Exception {
        click("constrained-sketch");
        sketchReady();
        click("sketch-construction");
        click("sketch-tool-line");
        sketchClick(0, 0, false);
        sketchClick(50, 0, false);
        sketchReady();
        click("sketch-tool-line");
        sketchClick(0, 0, false);
        sketchClick(0, 50, false);
        sketchReady();
        var lines = sketchGeometry().entities();
        click("sketch-construction");
        sketchReference(lines.get(0).id());
        sketchReference(lines.get(1).id());
        sketchType(SketchTools.Tool.CIRCLE_TWO_TANGENT);
        sketchClick(8, 8, false);
        sketchReady();
        var circle =
                sketchGeometry().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.CIRCLE)
                        .findFirst()
                        .orElseThrow();
        assertEquals(
                0,
                circle.point(SketchGeometry.Part.CENTER).distance(new SketchGeometry.Vec(5, 5)),
                .05);
        click("sketch-construction");
        click("sketch-tool-arc");
        sketchClick(30, 20, false);
        sketchClick(50, 20, false);
        sketchClick(30, 40, false);
        sketchReady();
        click("sketch-construction");
        String arc =
                sketchGeometry().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.ARC)
                        .findFirst()
                        .orElseThrow()
                        .id();
        sketchReference(arc);
        sketchType(SketchTools.Tool.TEXT_ON_PATH);
        text("sketch-text-content", "CAD");
        text("sketch-text-height", "4");
        sketchNumericPoint(50, 20);
        var text =
                sketchGeometry().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.TEXT)
                        .findFirst()
                        .orElseThrow();
        assertEquals(arc, text.text().pathId());
        var before = sketchGeometry().curves(text).getFirst().at(0);
        sketchDrag(30, 20, 35, 20, false);
        assertTrue(before.distance(sketchGeometry().curves(text).getFirst().at(0)) > 4);
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(),
                            artifacts.resolve("sketch-types-tangent-and-path-text.png"));
                    return null;
                });
        sketchReference(text.id());
        click("sketch-edit-text");
        choice("sketch-edit-text-path", 0);
        key(KeyCode.ENTER);
        sketchReady();
        assertEquals("", sketchGeometry().entity(text.id()).text().pathId());
        click("sketch-undo");
        sketchReady();
        assertEquals(arc, sketchGeometry().entity(text.id()).text().pathId());
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertEquals(4, fx(() -> workbench.controller().build().bodies().size()));
        var saved = document();
        click("constrained-sketch");
        sketchReady();
        sketchType(SketchTools.Tool.CONIC);
        sketchClick(0, 0, false);
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
        assertEquals(saved, document());
    }

    @Test
    void constrainedSketchDimensionsConflictsHistoryAndFilesUseRealControls() throws Exception {
        choice("appearance", 1);
        click("constrained-sketch");
        sketchReady();
        click("sketch-tool-rectangle");
        sketchClick(0, 0, false);
        sketchClick(60, 40, false);
        sketchReady();
        sketchClick(0, 0, false);
        click("constraint-fix");
        sketchReady();
        sketchClick(30, 0, false);
        sketchDimension(0, "60");
        sketchClick(0, 20, false);
        sketchDimension(0, "40");
        assertEquals(
                "Fully constrained", fx(() -> ((Label) node("#sketch-solve-status")).getText()));
        sketchClick(30, 0, false);
        sketchDimension(0, "80");
        assertTrue(fx(() -> node("#constraint-finish").isDisabled()));
        assertTrue(fx(() -> ((Label) node("#sketch-error")).getText().contains("cannot satisfy")));
        click("sketch-undo");
        sketchReady();
        assertFalse(fx(() -> node("#constraint-finish").isDisabled()));
        click("sketch-redo");
        sketchReady();
        assertTrue(fx(() -> node("#constraint-finish").isDisabled()));
        click("sketch-undo");
        sketchReady();
        click("sketch-tool-circle");
        sketchClick(10, 10, false);
        sketchClick(12, 10, false);
        sketchReady();
        sketchClick(12, 10, false);
        sketchDimension(5, "4");
        sketchClick(10, 10, false);
        sketchClick(0, 0, true);
        sketchDimension(2, "-10");
        sketchDimension(3, "-10");
        assertEquals(
                "Fully constrained", fx(() -> ((Label) node("#sketch-solve-status")).getText()));
        assertEquals(
                0, document().features().size(), "Sketch editing stays transient until Finish");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(), artifacts.resolve("sketch-constraints-light.png"));
                    return null;
                });
        click("constraint-finish");
        ready();
        assertEquals(1, document().features().size());
        assertInstanceOf(
                Feature.Constrained.class,
                ((Feature.Sketch) document().features().getFirst()).profile());
        click("extrude");
        ready();
        text("extrude-distance", "8");
        key(KeyCode.ENTER);
        ready();
        assertEquals(19099.469035085127, volume(), 0.01);
        Path file = directory.resolve("GUI constrained plate.jcad");
        save(file);
        var saved = DocumentReader.load(file);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertEquals(saved, document());
        String inspection = cli("inspect", file.toString(), "--point", "10,10,4");
        assertTrue(inspection.contains("contains=false"));
        assertTrue(cli("inspect", file.toString(), "--point", "30,20,4").contains("contains=true"));
        choice("appearance", 2);
        click("history-0");
        click("edit-sketch-constraints");
        sketchReady();
        int widthIndex =
                fx(
                        () -> {
                            var values =
                                    ((SketchEditor) node("#sketch-editor"))
                                            .controller()
                                            .definition()
                                            .constraints();
                            for (int i = 0; i < values.size(); i++)
                                if (values.get(i).type() == SketchGeometry.Type.LENGTH
                                        && values.get(i)
                                                .refs()
                                                .getFirst()
                                                .entityId()
                                                .endsWith("bottom")) return i;
                            throw new AssertionError("Missing width dimension");
                        });
        click("sketch-constraints");
        key(KeyCode.HOME);
        for (int i = 0; i < widthIndex; i++) key(KeyCode.DOWN);
        text("sketch-dimension-value", "80");
        click("sketch-apply-dimension");
        sketchReady();
        assertEquals(
                "Fully constrained", fx(() -> ((Label) node("#sketch-solve-status")).getText()));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(), artifacts.resolve("sketch-constraints-dark.png"));
                    return null;
                });
        click("constraint-finish");
        ready();
        assertEquals((80 * 40 - 4 * Math.PI) * 8, volume(), 0.01);
        assertEquals(saved.features().getFirst().id(), document().features().getFirst().id());
        click("undo");
        ready();
        assertEquals(saved, document());
        click("redo");
        ready();
        assertEquals((80 * 40 - 4 * Math.PI) * 8, volume(), 0.01);
        var beforeCancel = fx(() -> workbench.controller().snapshot());
        click("constrained-sketch");
        sketchReady();
        click("sketch-tool-line");
        sketchClick(10, 10, false);
        key(KeyCode.ESCAPE);
        assertTrue(
                fx(
                        () ->
                                ((SketchEditor) node("#sketch-editor"))
                                        .controller()
                                        .definition()
                                        .entities()
                                        .isEmpty()));
        click("constraint-cancel");
        assertEquals(beforeCancel, fx(() -> workbench.controller().snapshot()));
        Files.copy(
                file,
                artifacts.resolve("gui-constrained-plate.jcad"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("constrained-cli-inspection.txt"), inspection);
    }

    private void sketchReference(String ref) throws Exception {
        int index =
                fx(
                        () -> {
                            var items = ((ComboBox<?>) node("#sketch-reference")).getItems();
                            for (int i = 0; i < items.size(); i++)
                                if (items.get(i).toString().equals(ref)) return i;
                            throw new AssertionError("Missing sketch reference " + ref);
                        });
        choice("sketch-reference", index);
        click("sketch-select-ref");
    }

    private void sketchDrag(double x, double y, double toX, double toY, boolean cancel)
            throws Exception {
        var positions =
                fx(
                        () -> {
                            var editor = (SketchEditor) node("#sketch-editor");
                            return new javafx.geometry.Point2D[] {
                                editor.modelToScreen(new SketchGeometry.Vec(x, y)),
                                editor.modelToScreen(new SketchGeometry.Vec(toX, toY))
                            };
                        });
        fx(
                () -> {
                    robot.mouseMove(positions[0]);
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    robot.mouseMove(positions[1]);
                    return null;
                });
        Thread.sleep(180);
        if (cancel) key(KeyCode.ESCAPE);
        var released = new CountDownLatch(1);
        javafx.event.EventHandler<MouseEvent> releaseObserved =
                event -> {
                    if (event.getButton() == MouseButton.PRIMARY) released.countDown();
                };
        var canvas = fx(() -> node("#sketch-canvas"));
        try {
            fx(
                    () -> {
                        canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, releaseObserved);
                        robot.mouseRelease(MouseButton.PRIMARY);
                        return null;
                    });
            assertTrue(
                    released.await(10, TimeUnit.SECONDS),
                    "Native sketch mouse release was not delivered");
        } finally {
            // This FX dispatch also waits until the release handler has submitted its solve.
            fx(
                    () -> {
                        canvas.removeEventFilter(MouseEvent.MOUSE_RELEASED, releaseObserved);
                        return null;
                    });
        }
        sketchReady();
    }

    @Test
    void sketchPointDragsCurvatureAndRegularPolygonsUseRealControls() throws Exception {
        choice("appearance", 1);
        click("constrained-sketch");
        sketchReady();
        click("sketch-tool-line");
        sketchClick(10, 10, false);
        sketchClick(30, 10, false);
        sketchReady();
        var before = fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        sketchDrag(30, 10, 35, 15, true);
        assertEquals(
                before,
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition()));
        sketchDrag(30, 10, 35, 15, false);
        var moved = fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertEquals(35, moved.entities().getFirst().at(1).x(), 0.3);
        assertEquals(15, moved.entities().getFirst().at(1).y(), 0.3);
        assertEquals(
                moved.entities().getFirst().at(0).y(), moved.entities().getFirst().at(1).y(), 1e-6);
        click("sketch-undo");
        sketchReady();
        assertEquals(
                before,
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition()));
        click("sketch-redo");
        sketchReady();
        click("constraint-cancel");
        assertTrue(document().features().isEmpty());
        click("constrained-sketch");
        sketchReady();
        click("sketch-tool-arc");
        sketchClick(0, 0, false);
        sketchClick(10, 0, false);
        sketchClick(0, 10, false);
        sketchReady();
        click("sketch-tool-spline");
        sketchClick(0, 10, false);
        sketchClick(-3, 10, false);
        sketchClick(-8, 6, false);
        sketchClick(-10, 0, false);
        sketchReady();
        String arcId =
                fx(
                        () ->
                                ((SketchEditor) node("#sketch-editor"))
                                        .controller().definition().entities().stream()
                                                .filter(e -> e.kind() == SketchGeometry.Kind.ARC)
                                                .findFirst()
                                                .orElseThrow()
                                                .id());
        String splineId =
                fx(
                        () ->
                                ((SketchEditor) node("#sketch-editor"))
                                        .controller().definition().entities().stream()
                                                .filter(e -> e.kind() == SketchGeometry.Kind.SPLINE)
                                                .findFirst()
                                                .orElseThrow()
                                                .id());
        sketchReference(arcId);
        click("constraint-fix");
        sketchReady();
        click("sketch-clear-selection");
        sketchReference(arcId + ".end");
        sketchReference(splineId + ".start");
        click("constraint-curvature");
        sketchReady();
        var geometry = fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertTrue(
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().canFinish()),
                fx(() -> ((Label) node("#sketch-error")).getText()));
        assertTrue(
                geometry.constraints().stream()
                        .anyMatch(c -> c.type() == SketchGeometry.Type.CURVATURE));
        assertEquals(
                0, geometry.entity(arcId).at(1).distance(geometry.entity(splineId).at(0)), 1e-6);
        assertEquals(
                geometry.entity(arcId).curvature(1), geometry.entity(splineId).curvature(0), 1e-8);
        click("sketch-tool-line");
        sketchClick(-10, 0, false);
        sketchClick(10, 0, false);
        sketchReady();
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(), artifacts.resolve("sketch-spline-curvature.png"));
                    return null;
                });
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertTrue(volume() > 100);
        choice("appearance", 2);
        click("constrained-sketch");
        sketchReady();
        click("sketch-tool-polygon");
        sketchClick(30, 20, false);
        sketchClick(45, 20, false);
        sketchReady();
        geometry = fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertEquals(6, geometry.entities().size());
        assertEquals(SketchGeometry.Type.POLYGON, geometry.constraints().getFirst().type());
        assertEquals(
                4,
                fx(
                        () ->
                                ((SketchEditor) node("#sketch-editor"))
                                        .controller()
                                        .result()
                                        .degreesOfFreedom()));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(), artifacts.resolve("sketch-regular-polygon.png"));
                    return null;
                });
        click("constraint-finish");
        ready();
        assertEquals(3, document().features().size());
        click("undo");
        ready();
        assertEquals(2, document().features().size());
        click("redo");
        ready();
        assertEquals(3, document().features().size());
    }

    private void cubeClick(String region, ViewOrientation expected) throws Exception {
        fx(
                () -> {
                    var polygon = (Polygon) node("#view-cube-" + region);
                    double x = 0, y = 0;
                    var points = polygon.getPoints();
                    for (int i = 0; i < points.size(); i += 2) {
                        x += points.get(i);
                        y += points.get(i + 1);
                    }
                    robot.mouseMove(
                            polygon.localToScreen(x * 2 / points.size(), y * 2 / points.size()));
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(60);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(180);
        assertEquals(expected, fx(() -> workbench.viewport().orientationProperty().get()), region);
        assertEquals(expected.name(), fx(() -> ((ComboBox<?>) node("#standard-view")).getValue()));
    }

    @Test
    void viewCubeSnapsOrbitsAndFitsWithoutChangingTheDocument() throws Exception {
        rectangle(0);
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        save(directory.resolve("navigation.jcad"));
        var snapshot = fx(() -> workbench.controller().snapshot());
        click("view-cube-home");
        var point = new Point3D(60, 40, 5);
        var homeProjection = fx(() -> workbench.viewport().modelToScreen(point));
        cubeClick("front", ViewOrientation.named("Front"));
        cubeClick("front-right", ViewOrientation.lookingFrom(1, -1, 0));
        cubeClick("right", ViewOrientation.named("Right"));
        cubeClick("back-right", ViewOrientation.lookingFrom(1, 1, 0));
        cubeClick("back", ViewOrientation.named("Back"));
        cubeClick("back-left", ViewOrientation.lookingFrom(-1, 1, 0));
        cubeClick("left", ViewOrientation.named("Left"));
        cubeClick("top-left", ViewOrientation.lookingFrom(-1, 0, 1));
        cubeClick("top", ViewOrientation.named("Top"));
        cubeClick("top-front", ViewOrientation.lookingFrom(0, -1, 1));
        cubeClick("front", ViewOrientation.named("Front"));
        cubeClick("bottom-front", ViewOrientation.lookingFrom(0, -1, -1));
        cubeClick("bottom", ViewOrientation.named("Bottom"));
        cubeClick("bottom-front", ViewOrientation.lookingFrom(0, -1, -1));
        cubeClick("front", ViewOrientation.named("Front"));
        cubeClick("top-front-right", ViewOrientation.lookingFrom(1, -1, 1));
        var beforeOrbit = fx(() -> List.copyOf(((Polygon) node("#view-cube-top")).getPoints()));
        navigate(false);
        assertNotEquals(
                beforeOrbit,
                fx(() -> List.copyOf(((Polygon) node("#view-cube-top")).getPoints())),
                "Cube follows main viewport orbit");
        var orientation = fx(() -> workbench.viewport().orientationProperty().get());
        navigate(true);
        fx(
                () -> {
                    robot.mouseWheel(4);
                    return null;
                });
        Thread.sleep(200);
        assertEquals(
                orientation,
                fx(() -> workbench.viewport().orientationProperty().get()),
                "Pan and zoom do not rotate the cube");
        assertTrue(
                homeProjection.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2);
        click("view-cube-home");
        assertEquals(
                0,
                homeProjection.distance(fx(() -> workbench.viewport().modelToScreen(point))),
                0.01,
                "Home resets rotation, pan and zoom");
        var beforeDrag = fx(() -> workbench.viewport().modelToScreen(point));
        fx(
                () -> {
                    var face = node("#view-cube-front");
                    var bounds = face.localToScreen(face.getBoundsInLocal());
                    robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    robot.mouseMove(robot.getMouseX() + 30, robot.getMouseY() + 20);
                    return null;
                });
        Thread.sleep(150);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(180);
        assertTrue(
                beforeDrag.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2,
                "Dragging cube orbits the main view");
        assertEquals(
                ViewOrientation.HOME.dragged(30, 20),
                fx(() -> workbench.viewport().orientationProperty().get()),
                "Drag must not also snap on release");
        click("view-cube-menu");
        assertTrue(fx(() -> ((MenuButton) node("#view-cube-menu")).isShowing()));
        key(KeyCode.DOWN);
        key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        assertEquals(
                ViewOrientation.named("Top"),
                fx(() -> workbench.viewport().orientationProperty().get()));
        // Cube input is consumed by navigation, including while the sketch plane is active.
        click("rectangle");
        var draftX = fx(() -> ((TextField) node("#sketch-x")).getText());
        cubeClick("top-front", ViewOrientation.lookingFrom(0, -1, 1));
        assertEquals(draftX, fx(() -> ((TextField) node("#sketch-x")).getText()));
        key(KeyCode.ESCAPE);
        click("view-cube-home");
        modelClick(30, 20, 5);
        assertTrue(
                fx(
                        () ->
                                ((Label) workbench.lookup(".selection-title"))
                                        .getText()
                                        .startsWith("Face")),
                "Model picking still works around the overlay");
        assertEquals(snapshot, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(() -> workbench.controller().dirty()));
        for (int palette = 1; palette <= 2; palette++) {
            choice("appearance", palette);
            String name = palette == 1 ? "view-cube-light.png" : "view-cube-dark.png";
            fx(
                    () -> {
                        Screenshot.save(
                                stage.getScene(),
                                Path.of(System.getProperty("voxelbrew.ui.artifacts"), name));
                        return null;
                    });
        }
    }

    @Test
    void fullReferenceWorkflowWithIndependentCliAndExternalExporter() throws Exception {
        rectangle(0);
        click("extrude");
        ready();
        text("extrude-distance", "5");
        key(KeyCode.ENTER);
        ready();
        assertEquals(2, document().features().size());
        click("circle");
        modelClick(10, 10, 0);
        modelClick(12, 10, 0);
        text("sketch-x", "10");
        text("sketch-y", "10");
        text("sketch-diameter", "4");
        click("finish-sketch");
        ready();
        click("cut");
        click("cut-confirm");
        ready();
        assertEquals(11937.168146928205, volume(), 0.01);
        Path file = directory.resolve("GUI reference.jcad");
        save(file);
        var before = DocumentReader.load(file);
        Path cliFile = directory.resolve("CLI reference.jcad");
        Files.copy(file, cliFile);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertEquals(before, document());
        click("history-1");
        text("dimension-distance", "8");
        key(KeyCode.ENTER);
        ready();
        assertEquals(19099.469035085127, volume(), 0.01);
        click("history-cursor");
        key(KeyCode.HOME);
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        ready();
        assertEquals(3, document().cursor());
        assertEquals(19200, volume(), 0.01);
        key(KeyCode.RIGHT);
        ready();
        assertEquals(4, document().cursor());
        assertEquals(19099.469035085127, volume(), 0.01);
        click("undo");
        ready();
        assertEquals(11937.168146928205, volume(), 0.01);
        click("redo");
        ready();
        assertEquals(19099.469035085127, volume(), 0.01);
        click("save");
        ready();
        assertEquals(
                before.features().stream().map(Feature::id).toList(),
                document().features().stream().map(Feature::id).toList());
        Path exported = directory.resolve("GUI reference.3mf");
        click("export");
        text("export-path", exported.toString());
        click("export-confirm");
        ready();
        assertTrue(Files.size(exported) > 500);
        Path external = directory.resolve("external report.txt");
        click("export");
        choice("export-format", 2);
        text("export-option-label", "GUI external option");
        text("export-path", external.toString());
        click("export-confirm");
        ready();
        assertTrue(Files.readString(external).startsWith("GUI external option"));
        cli(
                "edit",
                cliFile.toString(),
                "--feature",
                before.features().get(1).id(),
                "--field",
                "distance",
                "--value",
                "8");
        assertEquals(DocumentReader.load(cliFile), DocumentReader.load(file));
        String inspection = cli("inspect", file.toString(), "--point", "10,10,4");
        assertTrue(inspection.contains("volume=19099.469035085127"));
        assertTrue(inspection.contains("contains=false"));
        assertTrue(
                inspection.contains(
                        "bounds=0.000000000,0.000000000,0.000000000,60.000000000,40.000000000,8.000000000"));
        assertTrue(cli("inspect", file.toString(), "--point", "30,20,4").contains("contains=true"));
        choice("standard-view", 0);
        modelClick(30, 20, 8);
        assertTrue(
                fx(
                        () ->
                                ((Label) workbench.lookup(".selection-title"))
                                        .getText()
                                        .startsWith("Face")));
        var selectedTree =
                fx(
                        () ->
                                ((TreeView<?>) node("#model-tree"))
                                        .getSelectionModel()
                                        .getSelectedItem()
                                        .getValue()
                                        .toString());
        assertEquals("Body 1", selectedTree);
        var navigationDocument = document();
        var point = new Point3D(60, 40, 8);
        var beforeOrbit = fx(() -> workbench.viewport().modelToScreen(point));
        navigate(false);
        var afterOrbit = fx(() -> workbench.viewport().modelToScreen(point));
        assertTrue(beforeOrbit.distance(afterOrbit) > 2, "Orbit changes projection");
        navigate(true);
        var afterPan = fx(() -> workbench.viewport().modelToScreen(point));
        assertTrue(afterOrbit.distance(afterPan) > 2, "Pan changes projection");
        fx(
                () -> {
                    robot.mouseWheel(4);
                    return null;
                });
        Thread.sleep(250);
        assertTrue(
                afterPan.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2,
                "Zoom changes projection");
        assertEquals(navigationDocument, document());
        choice("standard-view", 1);
        choice("standard-view", 0);
        click("fit");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        Files.createDirectories(artifacts);
        choice("appearance", 1);
        fx(
                () -> {
                    Screenshot.save(stage.getScene(), artifacts.resolve("workbench-light.png"));
                    return null;
                });
        choice("appearance", 2);
        fx(
                () -> {
                    Screenshot.save(stage.getScene(), artifacts.resolve("workbench-dark.png"));
                    return null;
                });
        assertFalse(
                Arrays.equals(
                        Files.readAllBytes(artifacts.resolve("workbench-light.png")),
                        Files.readAllBytes(artifacts.resolve("workbench-dark.png"))));
        Files.copy(
                file,
                artifacts.resolve("gui-reference-8mm.jcad"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                exported,
                artifacts.resolve("gui-reference.3mf"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                external,
                artifacts.resolve("external-provider.txt"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("cli-inspection.txt"), inspection);
    }

    @Test
    void customSpacerCreationEditingAndMissingPluginRecoveryUseRealControls() throws Exception {
        click("custom-feature");
        choice("custom-type", 1); // Sorted descriptors: failed fixture, then the spacer.
        text("custom-innerDiameter", "30");
        click("custom-add");
        assertTrue(document().features().isEmpty());
        assertTrue(fx(() -> ((Label) node("#custom-error")).getText().contains("innerDiameter")));
        assertTrue(fx(() -> node("#custom-innerDiameter").getStyleClass().contains("invalid")));
        click("custom-cancel");
        assertFalse(fx(() -> workbench.controller().snapshot().canUndo()));
        click("custom-feature");
        choice("custom-type", 1);
        text("custom-height", "5");
        click("custom-add");
        ready();
        assertEquals(1, document().features().size());
        assertEquals(Math.PI * 96 * 5, volume(), 0.01);
        assertTrue(fx(() -> ((Button) node("#history-0")).getText().contains("spacer")));
        text("dimension-height", "8");
        key(KeyCode.ENTER);
        ready();
        assertEquals(Math.PI * 96 * 8, volume(), 0.01);
        click("undo");
        ready();
        assertEquals(Math.PI * 96 * 5, volume(), 0.01);
        click("redo");
        ready();
        Path file = directory.resolve("GUI spacer.jcad");
        save(file);
        var before = DocumentReader.load(file);
        Path plugins = directory.resolve("plugins with spaces"),
                jar = plugins.resolve("provider.jar");
        fx(
                () -> {
                    workbench.close();
                    return null;
                });
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
        Files.delete(jar);
        fx(
                () -> {
                    workbench = Workbench.create(stage, plugins);
                    stage.setScene(new Scene(workbench, 964, 648));
                    stage.toFront();
                    stage.requestFocus();
                    return null;
                });
        Thread.sleep(1200);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertFalse(fx(() -> workbench.controller().build().valid()));
        assertEquals(0, volume(), 0);
        assertTrue(fx(() -> node("#export").isDisabled()));
        click("save");
        ready();
        assertEquals(before, DocumentReader.load(file));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), jar);
        click("history-0");
        click("custom-rebuild");
        ready();
        assertTrue(fx(() -> workbench.controller().canExport()));
        assertEquals(Math.PI * 96 * 8, volume(), 0.01);
        assertEquals(before, document());
        Path exported = directory.resolve("GUI spacer.3mf");
        click("export");
        text("export-path", exported.toString());
        click("export-confirm");
        ready();
        assertTrue(Files.size(exported) > 500);
        assertTrue(
                cli(
                                "inspect",
                                file.toString(),
                                "--point",
                                "0,0,4",
                                "--plugin-dir",
                                plugins.toString())
                        .contains("contains=false"));
        fx(
                () -> {
                    Screenshot.save(
                            stage.getScene(),
                            Path.of(
                                    System.getProperty("voxelbrew.ui.artifacts"),
                                    "custom-spacer.png"));
                    return null;
                });
    }

    private void navigate(boolean pan) throws Exception {
        fx(
                () -> {
                    var bounds =
                            workbench
                                    .viewport()
                                    .localToScreen(workbench.viewport().getBoundsInLocal());
                    robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                    if (pan) robot.keyPress(KeyCode.SHIFT);
                    robot.mousePress(MouseButton.MIDDLE);
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    robot.mouseMove(robot.getMouseX() + 60, robot.getMouseY() + 35);
                    return null;
                });
        Thread.sleep(150);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.MIDDLE);
                    if (pan) robot.keyRelease(KeyCode.SHIFT);
                    return null;
                });
        Thread.sleep(150);
    }

    @Test
    void signedExtrusionDragAndEscapeHaveExactUndoSemantics() throws Exception {
        rectangle(12);
        click("extrude");
        ready();
        text("extrude-distance", "-3");
        key(KeyCode.ENTER);
        ready();
        assertEquals(2, document().features().size());
        try (var evaluation = Evaluator.evaluate(document())) {
            var bounds = evaluation.bodies().getFirst().solid().bounds();
            assertEquals(9, bounds.minZ(), 0.001);
            assertEquals(12, bounds.maxZ(), 0.001);
        }
        click("undo");
        ready();
        click("history-0");
        click("extrude");
        ready();
        var before = document();
        fx(
                () -> {
                    var point = workbench.viewport().handleScreenPosition();
                    assertNotNull(point);
                    robot.mouseMove(point);
                    robot.mousePress(MouseButton.PRIMARY);
                    robot.mouseMove(point.getX(), point.getY() - 60);
                    return null;
                });
        Thread.sleep(200);
        key(KeyCode.ESCAPE);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    return null;
                });
        ready();
        assertEquals(before, document());
        assertFalse(fx(() -> workbench.controller().hasPreview()));
        click("history-0");
        click("extrude");
        ready();
        fx(
                () -> {
                    var point = workbench.viewport().handleScreenPosition();
                    robot.mouseMove(point);
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
        Thread.sleep(100);
        fx(
                () -> {
                    var point = workbench.viewport().handleScreenPosition();
                    robot.mouseMove(point.getX(), point.getY() - 70);
                    return null;
                });
        Thread.sleep(200);
        fx(
                () -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    return null;
                });
        ready();
        assertEquals(2, document().features().size());
        assertTrue(((Feature.Extrude) document().features().getLast()).distance() > 5);
        click("undo");
        ready();
        assertEquals(1, document().features().size());
        click("redo");
        ready();
        assertEquals(2, document().features().size());
    }

    @Test
    void invalidDimensionsAndBrokenInputRepairStayEditable() throws Exception {
        rectangle(0);
        var original = document();
        text("dimension-width", "NaN");
        key(KeyCode.ENTER);
        assertEquals(original, document());
        assertTrue(fx(() -> ((Label) node("#tool-error")).getText().contains("finite")));
        var sketch = (Feature.Sketch) original.features().getFirst();
        var broken =
                new CadDocument(
                        "broken",
                        List.of(
                                sketch,
                                new Feature.Extrude(
                                        "bad", new Feature.ProfileRef("missing", "none"), 8)),
                        2);
        Path file = directory.resolve("broken.jcad");
        DocumentWriter.save(broken, file, false);
        // Open through the actual file action after saving the current document to avoid an
        // unrelated prompt.
        save(directory.resolve("scratch.jcad"));
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertFalse(fx(() -> workbench.controller().build().valid()));
        assertTrue(fx(() -> workbench.controller().build().bodies().isEmpty()));
        click("history-1");
        assertFalse(
                fx(
                        () ->
                                ((javafx.scene.layout.VBox) node("#feature-diagnostics"))
                                        .getChildren()
                                        .isEmpty()));
        choice("repair-sketch", 0);
        click("repair");
        ready();
        assertEquals(19200, volume(), 0.01);
        assertEquals("bad", document().features().getLast().id());
        assertTrue(
                fx(
                        () ->
                                ((javafx.scene.layout.VBox) node("#feature-diagnostics"))
                                        .getChildren()
                                        .isEmpty()));
    }

    @Test
    void fileShortcutsAndUnsavedSaveDiscardCancelProtectDocuments() throws Exception {
        rectangle(0);
        var original = document();
        assertTrue(fx(() -> stage.getTitle().startsWith("●")));
        click("new");
        click("unsaved-cancel");
        assertEquals(original, document());
        Path file = directory.resolve("Save As ~ design.jcad");
        shortcut(KeyCode.S, true);
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertFalse(fx(() -> workbench.controller().dirty()));
        text("dimension-width", "61");
        key(KeyCode.ENTER);
        ready();
        assertTrue(fx(() -> workbench.controller().dirty()));
        shortcut(KeyCode.N, false);
        click("unsaved-save");
        ready();
        assertTrue(document().features().isEmpty());
        assertEquals(
                61,
                ((Feature.Rectangle)
                                ((Feature.Sketch) DocumentReader.load(file).features().getFirst())
                                        .profile())
                        .width());
        shortcut(KeyCode.O, false);
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        click("history-0");
        text("dimension-height", "41");
        key(KeyCode.ENTER);
        ready();
        click("new");
        click("unsaved-discard");
        ready();
        assertTrue(document().features().isEmpty());
        assertEquals(
                40,
                ((Feature.Rectangle)
                                ((Feature.Sketch) DocumentReader.load(file).features().getFirst())
                                        .profile())
                        .height());
    }

    @Test
    void printingSplitPlacementPetgAndActualPreviewMatchCli() throws Exception {
        rectangle(0);
        click("extrude");
        ready();
        text("extrude-distance", "8");
        key(KeyCode.ENTER);
        ready();
        click("circle");
        text("sketch-x", "10");
        text("sketch-y", "10");
        text("sketch-diameter", "4");
        click("finish-sketch");
        ready();
        click("cut");
        click("cut-confirm");
        ready();
        click("split-body");
        text("split-offset", "30");
        click("split-confirm");
        ready();
        assertEquals(2, fx(() -> workbench.controller().build().bodies().size()));
        assertEquals(19099.469035085127, volume(), .01);
        click("undo");
        ready();
        assertEquals(1, fx(() -> workbench.controller().build().bodies().size()));
        choice("appearance", 2);
        click("workspace-print");
        click("print-add");
        ready();
        text("print-x", "80");
        text("print-y", "30");
        text("print-rotate-z", "90");
        click("print-place");
        ready();
        click("print-duplicate");
        ready();
        assertEquals(2, document().printSetup().instances().size());
        click("print-remove");
        ready();
        assertEquals(1, document().printSetup().instances().size());
        click("print-tab-process");
        choice("print-material", 1);
        click("print-apply-process");
        ready();
        assertEquals("PETG", document().printSetup().materialId());
        Path file = directory.resolve("GUI print setup.jcad");
        save(file);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertEquals(DocumentReader.load(file), document());
        click("print-tab-job");
        Path job = directory.resolve("GUI printer job.gcode");
        text("print-output", job.toString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> node("#print-slice").isDisabled()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        click("print-slice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running())
                && System.nanoTime() < deadline) Thread.sleep(25);
        assertEquals(
                PrintController.State.SUCCEEDED,
                fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()),
                fx(() -> ((Label) node("#print-status")).getText()));
        var actual = GcodeReader.read(job);
        assertEquals(40, actual.modelLayers().size());
        assertEquals("PETG", actual.report().get("material"));
        click("print-layer");
        key(KeyCode.END);
        click("print-role-travel");
        click("print-role-perimeter");
        Path evidence = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(
                () -> {
                    Screenshot.save(stage.getScene(), evidence.resolve("printing-dark.png"));
                    return null;
                });
        click("workspace-design");
        choice("appearance", 1);
        click("workspace-print");
        fx(
                () -> {
                    Screenshot.save(stage.getScene(), evidence.resolve("printing-light.png"));
                    return null;
                });
        Path cliJob = directory.resolve("CLI printer job.gcode");
        assertTrue(cli("validate", file.toString()).contains("VALID"));
        cli(
                "slice",
                file.toString(),
                "--output",
                cliJob.toString(),
                "--slicer",
                PrusaSlicerBackend.defaultExecutable().toString());
        assertEquals(actual.segments(), GcodeReader.read(cliJob).segments());
        Files.copy(
                file,
                evidence.resolve("GUI print setup.jcad"),
                StandardCopyOption.REPLACE_EXISTING);
        Files.copy(
                job,
                evidence.resolve("GUI printer job.gcode"),
                StandardCopyOption.REPLACE_EXISTING);
        click("print-tab-process");
        text("print-layer-height", "0.3");
        click("print-apply-process");
        ready();
        assertEquals(
                PrintController.State.OUTDATED,
                fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()));
        assertNull(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().result()));
    }

    private String cli(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvoxelbrew.native.path=" + System.getProperty("voxelbrew.native.path"));
        command.add("-Dvoxelbrew.home=" + directory.resolve("cli-settings"));
        command.add("-cp");
        command.add(
                Path.of(System.getProperty("voxelbrew.cli.distribution"), "lib")
                        + java.io.File.separator
                        + "*");
        command.add("dev.voxelbrew.cli.VoxelBrew");
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(directory, "cli-", ".txt");
        var process =
                new ProcessBuilder(command)
                        .redirectErrorStream(true)
                        .redirectOutput(log.toFile())
                        .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = Files.readString(log);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    @Test
    void viewportBasisPreservesMillimetersAndOrientation() {
        var p = new Point3D(10, 20, 30);
        assertEquals(p, Viewport.toModel(Viewport.toView(p.getX(), p.getY(), p.getZ())));
        assertEquals(new Point3D(0, -1, 0), Viewport.toView(0, 0, 1));
        assertEquals(
                Viewport.toView(0, 0, 1),
                Viewport.toView(1, 0, 0).crossProduct(Viewport.toView(0, 1, 0)));
    }
}
