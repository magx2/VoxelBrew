package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentStore;
import dev.voxelbrew.engine.printing.*;
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
        Path plugins = Files.createDirectory(directory.resolve("plugins with spaces"));
        Files.copy(
                Path.of(System.getProperty("voxelbrew.test.plugin")),
                plugins.resolve("provider.jar"));
        fx(
                () -> {
                    stage = new Stage();
                    if (System.getProperty("os.name").startsWith("Windows"))
                        stage.setAlwaysOnTop(true);
                    workbench = new Workbench(stage, plugins);
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
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
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
        var before = DocumentStore.load(file);
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
        assertEquals(DocumentStore.load(cliFile), DocumentStore.load(file));
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
        var before = DocumentStore.load(file);
        Path plugins = directory.resolve("plugins with spaces"),
                jar = plugins.resolve("provider.jar");
        fx(
                () -> {
                    workbench.close();
                    return null;
                });
        Files.delete(jar);
        fx(
                () -> {
                    workbench = new Workbench(stage, plugins);
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
        assertEquals(before, DocumentStore.load(file));
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
        DocumentStore.save(broken, file, false);
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
                                ((Feature.Sketch) DocumentStore.load(file).features().getFirst())
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
                                ((Feature.Sketch) DocumentStore.load(file).features().getFirst())
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
        assertEquals(DocumentStore.load(file), document());
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
        var actual = GcodeJob.read(job);
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
        assertEquals(actual.segments(), GcodeJob.read(cliJob).segments());
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
