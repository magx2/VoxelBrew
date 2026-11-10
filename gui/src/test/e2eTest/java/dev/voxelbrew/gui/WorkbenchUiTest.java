package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.persistence.proto.DocumentProto;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.SketchGeometry;
import dev.voxelbrew.engine.sketch.SketchTools;
import dev.voxelbrew.engine.sketch.Sketches;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.importer.GcodeReader;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import dev.voxelbrew.util.Version;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javafx.application.*;
import javafx.event.EventHandler;
import javafx.geometry.Point2D;
import javafx.geometry.Point3D;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.robot.Robot;
import javafx.scene.shape.Polygon;
import javafx.stage.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;

/** Actual JavaFX windows, rendering, picking, keyboard input and mouse gestures on both OSes. */
@ExtendWith(UiTestShard.class)
class WorkbenchUiTest {
    @Test
    void printerSelectionPersistsAndUndoRestoresBuildVolume() throws Exception {
        ready();
        fx(() -> {
            var menu = ((MenuBar) node("#main-menu"))
                    .getMenus().stream()
                            .filter(item -> "menu-printers".equals(item.getId()))
                            .findFirst()
                            .orElseThrow();
            assertEquals(
                    List.of("printers-export-defaults", "printers-open-folder", "printers-reload"),
                    menu.getItems().stream().map(MenuItem::getId).toList());
            menu.getItems().getLast().fire();
            return null;
        });
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        var original = document().printSetup();
        text("print-printer-search", "A1 mini");
        click("print-printer");
        key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        ready();
        assertEquals("bambu-lab-a1-mini", document().printSetup().printerId());
        assertEquals(
                180, PrintProfile.resolve(document().printSetup()).printer().usableHeight());
        assertTrue(fx(() -> ((Label) node("#print-printer-details")).getText()).contains("180.0"));
        click("print-add");
        ready();
        assertFalse(fx(() -> node("#print-slice").isDisabled()), "Selected printer permits slicing");
        shortcut(KeyCode.Z, false);
        ready();
        click("print-reload-printers");
        ready();
        var selected = document().printSetup();
        assertEquals("bambu-lab-a1-mini", selected.printerId());
        shortcut(KeyCode.Z, false);
        ready();
        assertEquals(original, document().printSetup());
        shortcut(KeyCode.Z, true);
        ready();
        assertEquals(selected, document().printSetup());
        Path saved = directory.resolve("printer-snapshot.vxb");
        save(saved);
        open(saved);
        ready();
        assertEquals(selected, document().printSetup());
        click("workspace-print");
        ready();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printer-selector.png"));
            return null;
        });
    }

    @Test
    void slicerSelectedObjectNameStaysAbovePlateObject() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        fx(() -> {
            workbench
                    .controller()
                    .renameBody(document().printSetup().instances().getFirst().bodyId(), "Foo");
            return null;
        });
        ready();
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            ready();
            fx(() -> {
                robot.mouseMove(centerOf("print-canvas"));
                robot.mouseClick(MouseButton.PRIMARY);
                return null;
            });
            ready();
            fx(() -> {
                robot.mouseMove(centerOf("print-add"));
                return null;
            });
            Thread.sleep(1200);
            fx(() -> {
                var label = (Label) node("#print-selected-object-name");
                assertTrue(label.isVisible(), "Selection name stays visible without hovering");
                assertEquals("Foo (1)", label.getText());
                var bounds = label.localToScreen(label.getBoundsInLocal());
                var object = centerOf("print-canvas");
                assertTrue(bounds.getMaxY() < object.getY(), "Name appears above the selected object");
                assertTrue(bounds.getMinX() < object.getX() && bounds.getMaxX() > object.getX());
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve(view + "-selected-object-name.png"));
                return null;
            });
        }
        click("print-add-plate");
        ready();
        assertFalse(
                fx(() -> workbench.lookup("#print-selected-object-name").isVisible()), "Empty plate clears the name");
    }

    @Test
    void slicerSelectedObjectTooltipAppearsOnHover() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            ready();
            String id = "print-canvas";
            fx(() -> {
                robot.mouseMove(centerOf(id));
                return null;
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (!fx(() -> Window.getWindows().stream()
                            .anyMatch(window -> window instanceof Tooltip tip
                                    && tip.isShowing()
                                    && tip.getText().equals("Body 1 (1)")))
                    && System.nanoTime() < deadline) Thread.sleep(40);
            assertTrue(
                    fx(() -> Window.getWindows().stream()
                            .anyMatch(window -> window instanceof Tooltip tip
                                    && tip.isShowing()
                                    && tip.getText().equals("Body 1 (1)"))),
                    id + " shows the selected object name");
            fx(() -> {
                for (var window : List.copyOf(Window.getWindows()))
                    if (window instanceof Tooltip && window.isShowing())
                        Screenshot.save(
                                window.getScene(),
                                Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                        .resolve(view + "-object-tooltip.png"));
                robot.mouseMove(centerOf("print-add"));
                return null;
            });
            Thread.sleep(300);
            fx(() -> {
                var canvas = node("#print-canvas");
                robot.mouseMove(canvas.localToScreen(15, 15));
                return null;
            });
            Thread.sleep(1200);
            assertFalse(
                    fx(() -> Window.getWindows().stream()
                            .anyMatch(window -> window instanceof Tooltip tip
                                    && tip.isShowing()
                                    && tip.getText().equals("Body 1 (1)"))),
                    "Empty plate space has no object tooltip");
        }
    }

    @Test
    void slicerGeometryExportUsesGcodeFolderAndPlateName() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        Path saved = directory.resolve("geometry.part.vxb");
        save(saved);
        click("print-tab-job");
        var gcode = Path.of(fx(() -> ((FormulaField) node("#print-output")).getText()));
        click("print-export");
        chooseExporter("stl");
        var expected = ExportDialog.destinationForFormat(gcode, "stl");
        assertEquals(expected.toString(), fx(() -> ((FormulaField) node("#export-path")).getText()));
        click("export-confirm");
        ready();
        assertTrue(Files.exists(expected));
        var custom = directory.resolve("custom.name.gcode");
        text("print-output", custom.toString());
        click("print-export");
        chooseExporter("3mf");
        assertEquals(
                directory.resolve("custom.name.3mf").toString(),
                fx(() -> ((FormulaField) node("#export-path")).getText()));
        key(KeyCode.ESCAPE);
    }

    @Test
    void sliceAllPlatesWritesSeparateJobsWithoutChangingSelection() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        click("print-add-plate");
        ready();
        click("print-add");
        ready();
        Path saved = directory.resolve("all-plates.vxb");
        save(saved);
        var original = document().printSetup();
        click("print-tab-job");
        text("print-output", directory.resolve("batch.gcode").toString());
        click("print-slice-all");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertEquals(
                PrintController.State.SUCCEEDED,
                fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()));
        fx(() -> {
            var slider = (Slider) node("#print-layer");
            assertTrue(slider.getMax() > 0);
            assertEquals(slider.getMax(), slider.getValue(), "New slice opens at the final layer");
            return null;
        });
        assertEquals(original, document().printSetup());
        assertTrue(Files.exists(directory.resolve("all-plates-1-Plate 1.gcode")));
        assertTrue(Files.exists(directory.resolve("all-plates-2-Plate 2.gcode")));
    }

    @Test
    void autoArrangeMovesActivePlateAndUndoesTogether() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        click("print-add");
        ready();
        var original = document().printSetup();
        click("print-auto-arrange");
        ready();
        var arranged = document().printSetup();
        assertNotEquals(original, arranged);
        assertEquals(original.instances().size(), arranged.instances().size());
        var a = arranged.instances().get(0).transform().matrix();
        var b = arranged.instances().get(1).transform().matrix();
        assertTrue(Math.abs(a[3] - b[3]) >= 65 - 1e-7 || Math.abs(a[7] - b[7]) >= 45 - 1e-7);
        shortcut(KeyCode.Z, false);
        ready();
        assertEquals(original, document().printSetup());
    }

    @Test
    void slicerInstanceNamesFollowBodyRenamesAndUndo() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var setup = document().printSetup();
        var body = setup.instances().getFirst().bodyId();
        fx(() -> {
            workbench.controller().renameBody(body, "Foo");
            return null;
        });
        ready();
        assertEquals(setup, document().printSetup(), "Renaming preserves instance identities");
        assertEquals(
                "Foo (1)",
                fx(() -> ((ComboBox<?>) node("#print-instance")).getButtonCell().getText()));
        shortcut(KeyCode.Z, false);
        ready();
        assertEquals(
                "Body 1 (1)",
                fx(() -> ((ComboBox<?>) node("#print-instance")).getButtonCell().getText()));
    }

    @Test
    void slicerRotationGizmosPreviewCommitUndoAndCancel() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var original = document().printSetup();
        assertFalse(fx(() -> ((CheckBox) node("#print-show-rotation-gizmos")).isSelected()));
        assertFalse(slicerHasRing("#728ef5"));
        click("print-show-rotation-gizmos");
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            assertTrue(slicerHasRing("#728ef5"));
            assertEquals(view.equals("print-view-3d"), slicerHasRing("#e46969"));
            assertEquals(view.equals("print-view-3d"), slicerHasRing("#57ad74"));
            var start = fx(() -> {
                var canvas = (javafx.scene.canvas.Canvas) node("#print-canvas");
                var image = canvas.snapshot(null, null);
                Point2D best = null;
                for (int y = 0; y < image.getHeight(); y++)
                    for (int x = 0; x < image.getWidth(); x++) {
                        var color = image.getPixelReader().getColor(x, y);
                        if (Math.abs(color.getRed() - 114 / 255.0) < .025
                                && Math.abs(color.getGreen() - 142 / 255.0) < .025
                                && color.getBlue() > .94
                                && (best == null || x - y > best.getX() - best.getY())) best = new Point2D(x, y);
                    }
                assertNotNull(best, "Z ring is rendered");
                return canvas.localToScreen(best);
            });
            for (boolean cancel : List.of(false, true)) {
                fx(() -> {
                    robot.mouseMove(start);
                    robot.keyPress(KeyCode.SHIFT);
                    robot.mousePress(MouseButton.PRIMARY);
                    return null;
                });
                Thread.sleep(100);
                fx(() -> {
                    robot.mouseMove(start.add(10, 45));
                    return null;
                });
                Thread.sleep(150);
                assertEquals(original, document().printSetup(), "Rotation preview is transient");
                if (cancel) key(KeyCode.ESCAPE);
                fx(() -> {
                    robot.mouseRelease(MouseButton.PRIMARY);
                    robot.keyRelease(KeyCode.SHIFT);
                    return null;
                });
                ready();
                if (cancel) assertEquals(original, document().printSetup());
                else {
                    assertNotEquals(original, document().printSetup(), "Ring drag commits rotation");
                    var matrix = document()
                            .printSetup()
                            .instances()
                            .getFirst()
                            .transform()
                            .matrix();
                    double angle = Math.toDegrees(Math.atan2(matrix[4], matrix[0]));
                    assertEquals(Math.rint(angle / 15) * 15, angle, 1e-7);
                    Path saved = directory.resolve("rotated-" + view + ".vxb");
                    save(saved);
                    assertEquals(
                            document().printSetup(), DocumentReader.load(saved).printSetup());
                    fx(() -> {
                        Screenshot.save(
                                stage.getScene(),
                                Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                        .resolve("slicer-gizmos-" + view + ".png"));
                        return null;
                    });
                    shortcut(KeyCode.Z, false);
                    ready();
                    assertEquals(original, document().printSetup(), "One undo restores rotation");
                }
            }
        }
        text("print-rotate-x", "30");
        click("print-place");
        ready();
        var tilted = document().printSetup();
        long worldFrame = slicerCameraFrame();
        choice("print-rotation-space", 1);
        assertNotEquals(worldFrame, slicerCameraFrame(), "Local rings follow object orientation");
        assertEquals(tilted, document().printSetup(), "Coordinate mode is transient");
        assertTrue(fx(() -> ((CheckBox) node("#print-gizmo-local")).isSelected()));
        click("print-gizmo-local");
        assertEquals(
                PrintRotationGizmo.Space.WORLD, fx(() -> ((ComboBox<?>) node("#print-rotation-space")).getValue()));
        assertEquals(worldFrame, slicerCameraFrame(), "World rings keep their plate alignment");
        click("print-gizmo-local");
        assertEquals(
                PrintRotationGizmo.Space.LOCAL, fx(() -> ((ComboBox<?>) node("#print-rotation-space")).getValue()));
        assertEquals(tilted, document().printSetup());
        choice("print-rotation-space", 0);
        shortcut(KeyCode.Z, false);
        ready();
        click("print-show-rotation-gizmos");
        assertFalse(slicerHasRing("#728ef5"));
        assertFalse(slicerHasRing("#e46969"));
        assertFalse(slicerHasRing("#57ad74"));
        assertEquals(original, document().printSetup());
    }

    @Test
    void plateNamesEditInlineCancelUndoAndPersist() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var original = document().printSetup();
        click("print-tab-job");
        String originalDestination = fx(() -> ((FormulaField) node("#print-output")).getText());
        doubleClickPlate("plate-1");
        key(KeyCode.ENTER);
        assertEquals(original, document().printSetup(), "Unchanged names do not modify the design");
        doubleClickPlate("plate-1");
        text("print-plate-name", "Assembly");
        key(KeyCode.ENTER);
        ready();
        assertEquals("Assembly", document().printSetup().activePlate().name());
        assertTrue(fx(() -> ((FormulaField) node("#print-output")).getText()).endsWith("-1-Assembly.gcode"));
        assertEquals(original.instances(), document().printSetup().instances());
        assertTrue(
                fx(() -> ((ToggleButton) node("#print-plate-plate-1")).getText().startsWith("Assembly")));
        shortcut(KeyCode.Z, false);
        ready();
        assertEquals(original, document().printSetup());
        assertEquals(originalDestination, fx(() -> ((FormulaField) node("#print-output")).getText()));
        doubleClickPlate("plate-1");
        text("print-plate-name", "Discarded");
        key(KeyCode.ESCAPE);
        assertEquals(original, document().printSetup());
        assertNull(fx(() -> workbench.lookup("#print-plate-name")));
        doubleClickPlate("plate-1");
        text("print-plate-name", "");
        key(KeyCode.ENTER);
        assertNotNull(fx(() -> node("#print-plate-name")));
        assertEquals(original, document().printSetup());
        click("print-view-2d");
        assertNull(fx(() -> workbench.lookup("#print-plate-name")));
        click("print-add-plate");
        ready();
        doubleClickPlate("plate-1");
        text("print-plate-name", "Assembly");
        key(KeyCode.ENTER);
        ready();
        assertEquals(
                "Assembly",
                document().printSetup().selectPlate("plate-1").activePlate().name());
        Path saved = directory.resolve("named-plates.vxb");
        save(saved);
        var expected = document().printSetup();
        assertEquals(expected, DocumentReader.load(saved).printSetup());
        open(saved);
        ready();
        assertEquals(expected, document().printSetup());
        assertTrue(
                fx(() -> ((ToggleButton) node("#print-plate-plate-1")).getText().startsWith("Assembly")));
    }

    private boolean slicerHasRing(String hex) throws Exception {
        return fx(() -> {
            var expected = javafx.scene.paint.Color.web(hex);
            var image = node("#print-canvas").snapshot(null, null);
            for (int y = 0; y < image.getHeight(); y++)
                for (int x = 0; x < image.getWidth(); x++) {
                    var color = image.getPixelReader().getColor(x, y);
                    if (Math.abs(color.getRed() - expected.getRed()) < .01
                            && Math.abs(color.getGreen() - expected.getGreen()) < .01
                            && Math.abs(color.getBlue() - expected.getBlue()) < .01) return true;
                }
            return false;
        });
    }

    private void doubleClickPlate(String id) throws Exception {
        var point = centerOf("print-plate-" + id);
        fx(() -> {
            robot.mouseMove(point);
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(90);
        fx(() -> {
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
        assertNotNull(fx(() -> node("#print-plate-name")));
    }

    @Test
    void slicerCameraMatchesDesignerGesturesWithoutEditingPlacement() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var initial = document();
        for (String view : List.of("print-view-2d", "print-view-3d")) {
            click(view);
            for (var button : List.of(MouseButton.SECONDARY, MouseButton.MIDDLE, MouseButton.PRIMARY)) {
                long before = slicerCameraFrame();
                slicerCameraGesture(button, false);
                assertNotEquals(before, slicerCameraFrame(), view + " / " + button);
                assertEquals(initial, document(), "Camera changes are transient");
            }
            long before = slicerCameraFrame();
            slicerCameraGesture(MouseButton.MIDDLE, true);
            assertNotEquals(before, slicerCameraFrame(), "Shift+middle pans");
            before = slicerCameraFrame();
            var center = centerOf("print-canvas");
            fx(() -> {
                robot.mouseMove(center);
                robot.mouseWheel(-4);
                return null;
            });
            Thread.sleep(150);
            assertNotEquals(before, slicerCameraFrame(), "Wheel zooms");
            assertEquals(initial, document());
            click("print-fit-view");
            var part = fx(() -> {
                var canvas = (javafx.scene.canvas.Canvas) node("#print-canvas");
                var image = canvas.snapshot(null, null);
                double x = 0, y = 0;
                int count = 0;
                for (int row = 0; row < image.getHeight(); row++)
                    for (int col = 0; col < image.getWidth(); col++) {
                        var color = image.getPixelReader().getColor(col, row);
                        if (color.getBlue() > .5 && color.getGreen() > .4 && color.getRed() < .4) {
                            x += col;
                            y += row;
                            count++;
                        }
                    }
                assertTrue(count > 10, "Object is visible after camera navigation");
                return canvas.localToScreen(x / count, y / count);
            });
            fx(() -> {
                robot.mouseMove(part);
                robot.mousePress(MouseButton.PRIMARY);
                return null;
            });
            Thread.sleep(80);
            fx(() -> {
                robot.mouseMove(part.add(25, -15));
                return null;
            });
            Thread.sleep(80);
            fx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
            ready();
            assertNotEquals(
                    initial.printSetup(), document().printSetup(), "Object dragging works after camera navigation");
            shortcut(KeyCode.Z, false);
            ready();
            assertEquals(initial, document(), "Camera gestures do not add undo steps");
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve("slicer-camera-" + view + ".png"));
                return null;
            });
        }
    }

    private void slicerCameraGesture(MouseButton button, boolean shift) throws Exception {
        var start = fx(() -> node("#print-canvas").localToScreen(15, 15));
        fx(() -> {
            robot.mouseMove(start);
            if (shift) robot.keyPress(KeyCode.SHIFT);
            robot.mousePress(button);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mouseMove(start.add(40, 20));
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseRelease(button);
            if (shift) robot.keyRelease(KeyCode.SHIFT);
            return null;
        });
        Thread.sleep(100);
    }

    private long slicerCameraFrame() throws Exception {
        return fx(() -> {
            var image = node("#print-canvas").snapshot(null, null);
            long hash = 1;
            for (int y = 0; y < image.getHeight(); y += 2)
                for (int x = 0; x < image.getWidth(); x += 2)
                    hash = 31 * hash + image.getPixelReader().getArgb(x, y);
            return hash;
        });
    }

    @Test
    void slicerPlateDraggingAndMultiplePlatesPersist() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        click("workspace-print");
        ready();
        click("print-add");
        ready();
        var before = document().printSetup().instances().getFirst();
        var center = centerOf("print-canvas");
        fx(() -> {
            robot.mouseMove(center);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(120);
        fx(() -> {
            robot.mouseMove(center.add(35, -20));
            return null;
        });
        Thread.sleep(120);
        assertEquals(before, document().printSetup().instances().getFirst(), "Drag stays transient");
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        ready();
        var moved = document().printSetup().instances().getFirst();
        assertTrue(moved.transform().matrix()[3] > before.transform().matrix()[3]);
        assertTrue(moved.transform().matrix()[7] > before.transform().matrix()[7]);
        shortcut(KeyCode.Z, false);
        ready();
        assertEquals(before, document().printSetup().instances().getFirst(), "One undo restores placement");
        final var dragCenter = centerOf("print-canvas");
        fx(() -> {
            robot.mouseMove(dragCenter);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        fx(() -> {
            robot.mouseMove(dragCenter.add(25, 20));
            return null;
        });
        key(KeyCode.ESCAPE);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        assertEquals(before, document().printSetup().instances().getFirst(), "Escape discards placement");
        click("print-add-plate");
        ready();
        assertEquals(2, document().printSetup().plates().size());
        assertTrue(document().printSetup().instances().isEmpty());
        click("print-add");
        ready();
        assertEquals(1, document().printSetup().instances().size());
        click("print-plate-plate-1");
        ready();
        assertEquals(before, document().printSetup().instances().getFirst());
        Path saved = directory.resolve("multiple-plates.vxb");
        save(saved);
        assertEquals(document().printSetup(), DocumentReader.load(saved).printSetup());
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve("slicer-plates-" + palette + ".png"));
                return null;
            });
        }
        click("print-tab-process");
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts")).resolve("slicer-process-help.png"));
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> node("#print-slice").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        click("print-slice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertEquals(
                PrintController.State.SUCCEEDED,
                fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()));
        assertTrue(Files.exists(saved.resolveSibling("multiple-plates-1-Plate 1.gcode")));
        click("print-legend");
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"))
                                .resolve("slicer-preview-" + palette + ".png"));
                return null;
            });
        }
        click("print-preview");
        assertFalse(fx(() -> node("#print-layer").getParent().isVisible()));
    }

    @Test
    void slicerMenuPersistsSeparateProviderSettingsAndDiscardsUnsavedEdits() throws Exception {
        ready();
        click("menu-slicer");
        click("slicer-settings");
        click("slicer-settings-prusa-slicer");
        text("slicer-option-prusa-slicer-executable", "C:/Slicers/prusa.exe");
        click("slicer-settings-cura");
        text("slicer-option-cura-executable", "C:/Slicers/CuraEngine.exe");
        text("slicer-option-cura-definition", "C:/Slicers/machine.json");
        text("slicer-option-cura-settings", "C:/Slicers/process.properties");
        click("slicer-settings-save");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(() -> node("#slicer-settings-save").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(fx(() -> ((Label) node("#slicer-settings-status")).getText().contains("saved")));
        long probeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> ((Label) node("#slicer-status-cura")).getText().contains("Checking"))
                && System.nanoTime() < probeDeadline) Thread.sleep(25);
        assertTrue(fx(() -> ((Label) node("#slicer-status-cura")).getText().contains("Not working")));
        fx(() -> {
            var statusPanel = node("#slicer-status-cura").getParent();
            assertTrue(statusPanel.getStyleClass().contains("unavailable"));
            var saveButton = (Button) node("#slicer-settings-save");
            var cancelButton = (Button) node("#slicer-settings-close");
            assertSame(saveButton.getParent(), cancelButton.getParent());
            assertTrue(saveButton.isDefaultButton());
            assertEquals("Cancel", cancelButton.getText());
            assertEquals(
                    saveButton.localToScene(saveButton.getBoundsInLocal()).getCenterY(),
                    cancelButton.localToScene(cancelButton.getBoundsInLocal()).getCenterY(),
                    1.0);
            return null;
        });
        assertTrue(fx(() -> node("#print-slice").isDisabled()));
        assertTrue(fx(() -> node("#print-slice-all").isDisabled()));
        assertEquals(
                "C:/Slicers/prusa.exe",
                configuration.section("slicer", "prusa-slicer").get("executable", ""));
        assertEquals(
                "C:/Slicers/CuraEngine.exe",
                configuration.section("slicer", "cura").get("executable", ""));
        assertEquals(
                "C:/Slicers/machine.json",
                configuration.section("slicer", "cura").get("definition", ""));
        assertEquals(
                "C:/Slicers/process.properties",
                configuration.section("slicer", "cura").get("settings", ""));
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "slicer-settings.png"));
            return null;
        });
        click("slicer-settings-close");
        click("menu-slicer");
        click("slicer-settings");
        click("slicer-settings-prusa-slicer");
        assertEquals(
                "C:/Slicers/prusa.exe",
                fx(() -> ((TextField) node("#slicer-option-prusa-slicer-executable")).getText()));
        text("slicer-option-prusa-slicer-executable", "discarded");
        key(KeyCode.ESCAPE);
        assertEquals(
                "C:/Slicers/prusa.exe",
                configuration.section("slicer", "prusa-slicer").get("executable", ""));
        assertFalse(fx(() -> workbench.controller().dirty()));
    }

    @Test
    void extrusionRegionChoiceKeepsOnlyRectangle() throws Exception {
        var rectangle = Sketches.rectangle("r", 0, 0, 20, 10, false);
        var entities = new ArrayList<>(rectangle.entities());
        entities.add(Sketches.entity("roof-left", SketchGeometry.Kind.LINE, false, 0, 10, 10, 20));
        entities.add(Sketches.entity("roof-right", SketchGeometry.Kind.LINE, false, 10, 20, 20, 10));
        var geometry = new SketchGeometry(
                entities,
                rectangle.constraints(),
                entities.stream().map(SketchGeometry.Entity::id).toList());
        var sketch = new Feature.Sketch(
                "house", "profile", new Feature.Plane("origin-xy", 0), new Feature.Constrained(geometry));
        var file = directory.resolve("adjacent-regions.vxb");
        DocumentWriter.save(new CadDocument("regions", List.of(sketch), 1), file, false);
        open(file);
        click("history-0");
        click("extrude");
        ready();
        assertEquals(1500, volume(), .001);
        choice("extrude-region", 1);
        ready();
        assertEquals(1000, volume(), .001);
        choice("extrude-region", 2);
        ready();
        assertEquals(500, volume(), .001);
        choice("extrude-region", 1);
        text("extrude-distance", "8");
        key(KeyCode.ENTER);
        ready();
        assertEquals(1600, volume(), .001);
        assertEquals(sketch, document().features().getFirst());
        click("undo");
        ready();
        assertEquals(List.of(sketch), document().features());
    }

    @Test
    void constructionMenuCreatesEditablePlaneAndCancelsDraftWithoutHistory() throws Exception {
        ready();
        click("construction-menu");
        assertTrue(
                fx(() -> ((MenuButton) node("#construction-menu")).isShowing()),
                "Construct menu stays open after mouse click");
        assertEquals(
                20,
                fx(() -> ((MenuButton) node("#construction-menu"))
                                .getItems().stream()
                                        .filter(item -> !(item instanceof SeparatorMenuItem))
                                        .count())
                        .intValue());
        click("construct-offset-plane");
        choice("construction-input-0", 4);
        text("construction-value", "12");
        click("construction-preview");
        Thread.sleep(500);
        assertTrue(document().features().isEmpty(), "Preview does not create a history entry");
        click("construction-apply");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (document().features().isEmpty() && System.nanoTime() < deadline) Thread.sleep(40);
        ready();
        assertEquals(1, document().features().size());
        var feature = (ConstructionFeature) document().features().getFirst();
        assertEquals(12, feature.value());
        assertEquals(
                12,
                fx(() -> ((dev.voxelbrew.engine.construction.ConstructionGeometry.Plane)
                                workbench.controller().build().construction().get(feature.id()))
                        .origin()
                        .z()));
        click("history-0");
        click("construction-edit");
        text("construction-value", "20");
        click("construction-cancel");
        assertEquals(feature, document().features().getFirst());
        click("construction-menu");
        click("construct-point-vertex");
        key(KeyCode.ESCAPE);
        assertEquals(1, document().features().size());
        shortcut(KeyCode.Z, false);
        ready();
        assertTrue(document().features().isEmpty());
        shortcut(KeyCode.Z, true);
        ready();
        assertEquals(feature, document().features().getFirst());
        fx(() -> {
            workbench
                    .controller()
                    .replaceFeature(new ConstructionFeature(
                            feature.id(),
                            feature.tool(),
                            List.of(new ConstructionFeature.Input(
                                    ConstructionFeature.Selection.DATUM,
                                    "missing",
                                    "",
                                    0,
                                    new dev.voxelbrew.engine.construction.ConstructionGeometry.Vector(0, 0, 0))),
                            12,
                            false));
            return null;
        });
        ready();
        assertFalse(fx(() -> workbench.controller().build().valid()));
        click("history-0");
        click("construction-edit");
        choice("construction-input-0", 4);
        click("construction-apply");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!fx(() -> workbench.controller().build().valid()) && System.nanoTime() < deadline) Thread.sleep(40);
        ready();
        assertTrue(
                fx(() -> workbench.controller().build().valid()), "Broken construction can be repaired in its editor");
        assertEquals(feature, document().features().getFirst());
    }

    @Test
    void fullyConstrainedLinesAndPointsRenderBlackWhileFreeGeometryStaysBlue() throws Exception {
        var a = Sketches.entity("fixed", SketchGeometry.Kind.LINE, false, 10, 10, 30, 10);
        var b = Sketches.entity("free", SketchGeometry.Kind.LINE, false, 10, 30, 30, 30);
        var c = Sketches.entity("partial", SketchGeometry.Kind.LINE, false, 40, 10, 60, 10);
        var g = new SketchGeometry(List.of(a, b, c), List.of(), List.of());
        for (var constraint : List.of(
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g,
                        "x",
                        SketchGeometry.Type.DISTANCE_X,
                        List.of(SketchGeometry.ORIGIN, a.ref(SketchGeometry.Part.START)),
                        10),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g,
                        "y",
                        SketchGeometry.Type.DISTANCE_Y,
                        List.of(SketchGeometry.ORIGIN, a.ref(SketchGeometry.Part.START)),
                        10),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g, "length", SketchGeometry.Type.LENGTH, List.of(a.ref()), 20),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g, "horizontal", SketchGeometry.Type.HORIZONTAL, List.of(a.ref()), 0),
                dev.voxelbrew.engine.sketch.SketchConstraints.create(
                        g, "partial-anchor", SketchGeometry.Type.FIX, List.of(c.ref(SketchGeometry.Part.START)), 0)))
            g = g.add(constraint);
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("sketch", "profile", Feature.Plane.origin("XY"), new Feature.Constrained(g)));
        Path file = directory.resolve("constraint-colors.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        choice("appearance", 1);
        click("history-0");
        click("edit-sketch-constraints");
        sketchReady();
        assertTrue(fx(() ->
                ((SketchEditor) node("#sketch-editor")).controller().result().degreesOfFreedom() > 0));
        assertSketchGeometryColor(20, 10, true);
        assertSketchGeometryColor(10, 10, true);
        assertSketchGeometryColor(20, 30, false);
        assertSketchGeometryColor(40, 10, true);
        assertSketchGeometryColor(50, 10, false);
        sketchReference("fixed");
        assertFalse(fx(() -> ((Label) node("#sketch-selection")).getText().isBlank()));
        click("sketch-clear-selection");
        assertSketchGeometryColor(20, 10, true);
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "sketch-constraint-colors.png"));
            return null;
        });
        click("constraint-cancel");
    }

    private void assertSketchGeometryColor(double x, double y, boolean black) throws Exception {
        boolean matches = fx(() -> {
            var canvas = (javafx.scene.canvas.Canvas) node("#sketch-canvas");
            var pixel = canvas.screenToLocal(
                    ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y)));
            var image = canvas.snapshot(null, null);
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -2; dy <= 2; dy++) {
                    var color = image.getPixelReader().getColor((int) pixel.getX() + dx, (int) pixel.getY() + dy);
                    if (black
                            ? color.getBrightness() < .4 && Math.abs(color.getRed() - color.getBlue()) < .04
                            : color.getBlue() > color.getRed() + .2 && color.getBlue() > .4) return true;
                }
            return false;
        });
        assertTrue(matches, (black ? "Constrained black" : "Unconstrained blue") + " geometry at " + x + ", " + y);
    }

    @Test
    void dimensionLabelsAndLinesDragWithoutChangingGeometryAndEscapeRestoresPlacement() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(0, 0, false);
        sketchClick(0, 40, false);
        text("sketch-inline-dimension", "40");
        key(KeyCode.ENTER);
        sketchReady();
        key(KeyCode.ESCAPE);
        var before = sketchGeometry();
        double x = fx(() -> {
            var editor = (SketchEditor) node("#sketch-editor");
            return 20
                    / (editor.modelToScreen(new SketchGeometry.Vec(1, 20)).getX()
                            - editor.modelToScreen(new SketchGeometry.Vec(0, 20))
                                    .getX());
        });
        sketchDrag(x, 20, x + 10, 25, false);
        assertDimensionLineInk(x + 10, 8, true);
        assertDimensionLineInk(x, 8, false);
        assertEquals(before, sketchGeometry());
        sketchDrag(x + 10, 8, x + 20, 8, false);
        assertDimensionLineInk(x + 20, 8, true);
        assertDimensionLineInk(x + 10, 8, false);
        sketchDrag(x + 20, 8, x + 30, 8, true);
        assertDimensionLineInk(x + 20, 8, true);
        assertDimensionLineInk(x + 30, 8, false);
        assertEquals(before, sketchGeometry());
        click("sketch-undo");
        sketchReady();
        assertFalse(sketchGeometry().constraints().stream().anyMatch(c -> c.type() == SketchGeometry.Type.DISTANCE_Y));
        click("constraint-cancel");
    }

    private void assertDimensionLineInk(double x, double y, boolean expected) throws Exception {
        boolean ink = fx(() -> {
            var canvas = (javafx.scene.canvas.Canvas) node("#sketch-canvas");
            var editor = (SketchEditor) node("#sketch-editor");
            var pixel = canvas.screenToLocal(editor.modelToScreen(new SketchGeometry.Vec(x, y)));
            var image = canvas.snapshot(null, null);
            for (int dx = -2; dx <= 2; dx++)
                for (int dy = -2; dy <= 2; dy++)
                    if (image.getPixelReader()
                                    .getColor((int) pixel.getX() + dx, (int) pixel.getY() + dy)
                                    .getBrightness()
                            < .65) return true;
            return false;
        });
        assertEquals(expected, ink, "Dimension line at " + x + ", " + y);
    }

    @Test
    void standalonePointsOpenAndDriveActualPointToPointDistance() throws Exception {
        ready();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-point");
        sketchClick(10, 10, false);
        sketchReady();
        click("sketch-tool-point");
        sketchClick(40, 50, false);
        sketchReady();
        assertEquals(2, sketchGeometry().entities().size());
        var placed = sketchGeometry().entities();
        double initialDistance = placed.get(0).at(0).distance(placed.get(1).at(0));
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(10, 10, false);
        sketchClick(40, 50, false);
        assertEquals(initialDistance, fx(() -> ((FormulaField) node("#sketch-inline-dimension")).value()), 1e-4);
        text("sketch-inline-dimension", "60");
        key(KeyCode.ENTER);
        sketchReady();
        var g = sketchGeometry();
        var dimension = g.constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE)
                .findFirst()
                .orElseThrow();
        assertEquals(
                60,
                g.point(dimension.refs().get(0))
                        .distance(g.point(dimension.refs().get(1))),
                1e-6);
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
    }

    @Test
    void dimensionSnapsAndHighlightsNearbyLinesAndPointsWithoutAutoConstraints() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-auto");
        click("sketch-tool-select");
        key(KeyCode.D);
        dimensionSnapClick(60, 40, 7, 0);
        String pointSelection = fx(() -> ((Label) node("#sketch-selection")).getText());
        assertTrue(pointSelection.contains(".start") || pointSelection.contains(".end"), pointSelection);
        assertNull(
                fx(() -> stage.getScene().lookup("#sketch-inline-dimension")),
                "Snapping selects the point instead of placing a label");
        click("sketch-clear-selection");
        dimensionSnapClick(0, 20, -7, 0);
        assertTrue(fx(() -> ((Label) node("#sketch-selection")).getText().contains("left")));
        dimensionSnapClick(60, 20, 7, 0);
        assertTrue(fx(() -> ((Label) node("#sketch-selection")).getText().contains("right")));
        assertNotNull(fx(() -> node("#sketch-inline-dimension")), "Second line opens the field immediately");
        String pair = fx(() -> ((Label) node("#sketch-selection")).getText());
        sketchClick(30, 0, false);
        assertEquals(
                pair,
                fx(() -> ((Label) node("#sketch-selection")).getText()),
                "Third line cannot change the active dimension");
        text("sketch-inline-dimension", "80");
        key(KeyCode.ENTER);
        sketchReady();
        assertTrue(fx(() ->
                ((SketchEditor) node("#sketch-editor")).controller().result().solved()));
        assertTrue(sketchGeometry().constraints().stream()
                .anyMatch(c ->
                        c.type() == SketchGeometry.Type.DISTANCE && c.values().getFirst() == 80));
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
    }

    @Test
    void deletingClickedDimensionPreservesGeometryAndSupportsUndoRedo() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(0, 0, false);
        sketchClick(0, 40, false);
        text("sketch-inline-dimension", "40");
        key(KeyCode.ENTER);
        sketchReady();
        key(KeyCode.ESCAPE);
        var before = sketchGeometry();
        var dimension = before.constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE_Y)
                .findFirst()
                .orElseThrow();
        // The point-pair dimension label is 20 screen pixels right of the midpoint.
        fx(() -> {
            var midpoint = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(0, 20));
            robot.mouseMove(midpoint.add(20, 0));
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(150);
        assertEquals(
                dimension,
                fx(() -> ((ListView<?>) node("#sketch-constraints"))
                        .getSelectionModel()
                        .getSelectedItem()));
        key(KeyCode.DELETE);
        sketchReady();
        var after = sketchGeometry();
        assertEquals(before.entities(), after.entities(), "Deleting a dimension must preserve its geometry");
        assertEquals(before.boundary(), after.boundary());
        assertEquals(
                before.constraints().stream()
                        .filter(c -> !c.id().equals(dimension.id()))
                        .toList(),
                after.constraints());
        click("sketch-undo");
        sketchReady();
        assertEquals(before, sketchGeometry());
        click("sketch-redo");
        sketchReady();
        assertEquals(after, sketchGeometry());
        sketchClick(30, 0, false);
        key(KeyCode.DELETE);
        sketchReady();
        assertEquals(
                before.entities().size() - 1,
                sketchGeometry().entities().size(),
                "Selecting geometry afterwards must still allow geometry deletion");
        click("constraint-cancel");
    }

    private void dimensionSnapClick(double x, double y, double dx, double dy) throws Exception {
        fx(() -> {
            var target = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y));
            robot.mouseMove(target.add(dx, dy));
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        boolean highlighted;
        do {
            Thread.sleep(30);
            highlighted = fx(() -> {
                var canvas = (javafx.scene.canvas.Canvas) node("#sketch-canvas");
                var screen = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y));
                var pixel = canvas.screenToLocal(screen);
                var image = canvas.snapshot(null, null);
                for (int ox = -2; ox <= 2; ox++)
                    for (int oy = -2; oy <= 2; oy++) {
                        var color = image.getPixelReader().getColor((int) pixel.getX() + ox, (int) pixel.getY() + oy);
                        if (color.getBlue() > .8 && color.getRed() < .3 && color.getGreen() > .4) return true;
                    }
                return false;
            });
        } while (!highlighted && System.nanoTime() < deadline);
        assertTrue(highlighted, "Nearby dimension reference must visibly highlight before clicking");
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(150);
    }

    @Test
    void twoParallelLinesDimensionUsesInlineFormulaParametersAndPersists() throws Exception {
        ready();
        click("parameters");
        parameter("plateWidth", "80");
        click("parameters-apply");
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        sketchClick(0, 20, false);
        sketchClick(60, 20, true);
        key(KeyCode.D);
        var field = fx(() -> node("#sketch-inline-dimension"));
        assertInstanceOf(FormulaField.class, field);
        assertEquals(FormulaField.ExpressionType.NUMBER, ((FormulaField) field).expressionType());
        assertTrue(fx(() -> field.lookup(".formula-mark") != null));
        assertTrue(
                fx(() -> field.getBoundsInLocal().getWidth() >= 200),
                "Inline input must display the expression, not just the fx mark");
        text("sketch-inline-dimension", "=missingParameter");
        key(KeyCode.ENTER);
        assertNotNull(fx(() -> node("#sketch-inline-dimension")), "Invalid input stays open for repair");
        assertFalse(fx(() -> ((Label) node("#sketch-error")).getText().isBlank()));
        focus(stage);
        text("sketch-inline-dimension", "=$plateW");
        key(KeyCode.TAB);
        assertEquals("=$plateWidth", fx(() -> ((TextField) field).getText()));
        fx(() -> {
            assertEquals(80, ((FormulaField) field).value());
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "inline-dimension-formula.png"));
            return null;
        });
        key(KeyCode.ENTER);
        sketchReady();
        assertNull(fx(() -> stage.getScene().lookup("#sketch-inline-dimension")));
        var geometry = sketchGeometry();
        var distance = geometry.constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE)
                .findFirst()
                .orElseThrow();
        assertEquals(80, distance.values().getFirst(), 1e-6);
        assertTrue(fx(() ->
                ((SketchEditor) node("#sketch-editor")).controller().result().solved()));
        dev.voxelbrew.engine.sketch.SketchConstraints.requireSatisfied(geometry);
        assertEquals("", fx(() -> ((Label) node("#sketch-error")).getText()));
        click("sketch-undo");
        sketchReady();
        assertFalse(sketchGeometry().constraints().stream().anyMatch(c -> c.id().equals(distance.id())));
        click("sketch-redo");
        sketchReady();
        key(KeyCode.ESCAPE);
        click("constraint-finish");
        ready();
        var feature = (Feature.Sketch) document().features().getFirst();
        assertEquals(
                "=$plateWidth",
                document().parameters().binding(new ParameterSet.Target(feature.id(), "constraint:" + distance.id())));
        Path saved = directory.resolve("line-distance-formula.vxb");
        save(saved);
        open(saved);
        assertEquals(
                "=$plateWidth",
                document().parameters().binding(new ParameterSet.Target(feature.id(), "constraint:" + distance.id())));
    }

    @Test
    void secondPointImmediatelyOpensVerticalDimensionAndRejectsThirdReference() throws Exception {
        ready();
        rectangleDraft(60, 40, 0);
        click("sketch-tool-select");
        key(KeyCode.D);
        sketchClick(0, 0, false);
        assertNull(fx(() -> stage.getScene().lookup("#sketch-inline-dimension")));
        sketchClick(0, 40, false);
        assertEquals(40, fx(() -> ((FormulaField) node("#sketch-inline-dimension")).value()), 1e-6);
        String pair = fx(() -> ((Label) node("#sketch-selection")).getText());
        sketchClick(60, 0, false);
        assertEquals(pair, fx(() -> ((Label) node("#sketch-selection")).getText()));
        text("sketch-inline-dimension", "50");
        key(KeyCode.ENTER);
        sketchReady();
        var dimension = sketchGeometry().constraints().stream()
                .filter(c -> c.type() == SketchGeometry.Type.DISTANCE_Y)
                .findFirst()
                .orElseThrow();
        assertEquals(2, dimension.refs().size());
        assertEquals(50, dimension.values().getFirst(), 1e-6);
        assertTrue(fx(() ->
                ((SketchEditor) node("#sketch-editor")).controller().result().solved()));
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
    }

    @Test
    void technicalDrawingRenderingHidesRearEdgesKeepsPickingAndRemembersStyle() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "plate", "profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 60, 40)));
        session.add(new Feature.Extrude("thickness", new Feature.ProfileRef("plate", "profile"), 8));
        session.add(new Feature.Sketch("hole", "profile", Feature.Plane.origin("XY"), new Feature.Circle(10, 10, 4)));
        session.add(new Feature.ThroughCut(
                "cut", new Feature.ProfileRef("hole", "profile"), new Feature.BodyRef("thickness", "body")));
        // A separate body completely behind the plate tests occlusion across bodies.
        session.add(new Feature.Sketch(
                "hidden", "profile", new Feature.Plane("offset", -5), new Feature.Rectangle(20, 10, 10, 10)));
        session.add(new Feature.Extrude("hidden-solid", new Feature.ProfileRef("hidden", "profile"), 2));
        session.setBodyStyle("thickness.body", new BodyStyle("PETG", "#FF0000"));
        session.add(new Feature.Sketch("boss", "profile", new Feature.Plane("top", 8), new Feature.Circle(42, 22, 12)));
        session.add(new Feature.Extrude("boss-solid", new Feature.ProfileRef("boss", "profile"), 10));
        Path file = directory.resolve("technical rendering.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        var snapshot = fx(() -> workbench.controller().snapshot());
        var bytes = Files.readAllBytes(file);
        renderStyle("technical");
        standardView(1);
        click("history-3");
        Thread.sleep(250);
        fx(() -> {
            var view = workbench.viewport();
            assertEquals(Rendering.Style.TECHNICAL, view.rendering());
            var scene = (SubScene) node("#viewport-scene");
            assertEquals(javafx.scene.paint.Color.WHITE, scene.getFill());
            var image = scene.snapshot(null, null);
            for (var point : List.of(new Point3D(30, 20, 8), new Point3D(20, 15, -3), new Point3D(25, 10, -3))) {
                var screen = view.modelToScreen(point);
                var pixel = scene.screenToLocal(screen);
                var color = image.getPixelReader().getColor((int) pixel.getX(), (int) pixel.getY());
                assertTrue(
                        color.getBrightness() > .97,
                        "White face conceals internal triangles, grid and rear-body edges: " + color);
            }
            var start = scene.screenToLocal(view.modelToScreen(new Point3D(0, 0, 8)));
            var end = scene.screenToLocal(view.modelToScreen(new Point3D(60, 0, 8)));
            for (int step = 1; step < 20; step++) {
                var pixel = start.add(end.subtract(start).multiply(step / 20.0));
                boolean ink = false;
                for (int dx = -1; dx <= 1; dx++)
                    for (int dy = -1; dy <= 1; dy++)
                        ink |= image.getPixelReader()
                                        .getColor((int) pixel.getX() + dx, (int) pixel.getY() + dy)
                                        .getBrightness()
                                < .6;
                assertTrue(ink, "Visible outline is continuous at sample " + step);
            }
            var outline = findBodyView(view, "outline-thickness.body");
            assertNotNull(outline);
            assertTrue(((javafx.scene.shape.TriangleMesh) outline.getMesh())
                            .getFaces()
                            .size()
                    > 0);
            return null;
        });
        assertEquals(snapshot, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(() -> workbench.controller().dirty()));
        modelClick(35, 25, 8);
        assertNotNull(fx(() -> node("#body-material-name")), "Visible solid remains pickable through its outlines");
        click("history-3");
        standardView(0);
        fx(() -> {
            var bounds = Screen.getPrimary().getVisualBounds();
            stage.setWidth(Math.min(1400, bounds.getWidth() - 60));
            stage.setHeight(Math.min(900, bounds.getHeight() - 80));
            stage.centerOnScreen();
            return null;
        });
        Thread.sleep(250);
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "technical-drawing-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
        // The normal creation path remains available while this style is active.
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("constraint-cancel");
        assertEquals(snapshot, fx(() -> workbench.controller().snapshot()));
        renderStyle("shaded");
        assertEquals(Rendering.Style.SHADED, fx(() -> workbench.viewport().rendering()));
        assertEquals(
                javafx.scene.paint.Color.RED,
                fx(() -> ((javafx.scene.paint.PhongMaterial) findBodyView(workbench.viewport(), "body-thickness.body")
                                .getMaterial())
                        .getDiffuseColor()));
        renderStyle("technical");
        assertEquals("TECHNICAL", configuration.get("rendering.style", ""));
        assertEquals("preserve me", configuration.get("external.setting", ""));
        fx(() -> {
            workbench.close();
            return null;
        });
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
        fx(() -> {
            workbench = Workbench.create(
                    stage,
                    directory.resolve("plugins with spaces"),
                    new dev.voxelbrew.util.di.ApplicationModule(configuration, updates::service),
                    new dev.voxelbrew.gui.FileIoModule());
            stage.setScene(workbench.createScene(964, 648));
            stage.toFront();
            stage.requestFocus();
            return null;
        });
        Thread.sleep(400);
        open(file);
        assertEquals(Rendering.Style.TECHNICAL, fx(() -> workbench.viewport().rendering()));
        assertArrayEquals(bytes, Files.readAllBytes(file));
    }

    private void renderStyle(String style) throws Exception {
        click("menu-view");
        click("menu-rendering");
        click("rendering-" + style);
        Thread.sleep(200);
        assertTrue(fx(() -> ((RadioMenuItem) command(workbench, "rendering-" + style)).isSelected()));
    }

    @Test
    void compactHistorySupportsPickingDraggingPlaybackAndIndependentUndo() throws Exception {
        openPrepared("rectangle-0.vxb");
        click("extrude");
        text("extrude-distance", "8");
        click("commit-extrusion");
        ready();
        circle();
        click("cut");
        click("cut-confirm");
        ready();
        var complete = document();
        assertEquals(4, complete.cursor());
        click("history-1");
        assertEquals(complete, document(), "Selecting an icon must not roll back the model");
        assertTrue(fx(() -> node("#history-1").getStyleClass().contains("history-selected")));
        assertEquals(ContentDisplay.GRAPHIC_ONLY, fx(() -> ((Button) node("#history-1")).getContentDisplay()));
        assertTrue(fx(() -> ((Button) node("#history-1")).getTooltip().getText().contains("2 ·")));
        assertTrue(fx(() -> node("#feature-timeline").getBoundsInParent().getHeight()) < 80);
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "timeline-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
        click("timeline-first");
        ready();
        assertEquals(0, document().cursor());
        assertTrue(fx(() -> node("#constrained-sketch").isDisabled()));
        assertTrue(fx(() -> node("#history-0").getStyleClass().contains("history-future")));
        click("timeline-next");
        ready();
        assertEquals(1, document().cursor());
        click("timeline-last");
        ready();
        click("timeline-previous");
        ready();
        assertEquals(3, document().cursor());
        assertEquals(19200, volume(), .01);
        var start = fx(() -> {
            var bounds = node("#history-cursor")
                    .localToScreen(node("#history-cursor").getBoundsInLocal());
            return new Point2D(bounds.getCenterX(), bounds.getCenterY());
        });
        fx(() -> {
            robot.mouseMove(start);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        try {
            for (int step = 1; step <= 8; step++) {
                double x = start.getX() - 76.0 * step / 8;
                fx(() -> {
                    robot.mouseMove(x, start.getY());
                    return null;
                });
                Thread.sleep(50);
            }
        } finally {
            fx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
        }
        ready();
        assertEquals(1, document().cursor(), "Dragging the marker snaps between feature icons");
        key(KeyCode.END);
        ready();
        assertEquals(complete, document());
        click("undo");
        ready();
        assertEquals(3, document().features().size(), "Rollback must not create undo entries");
        click("redo");
        ready();
        assertEquals(complete, document());
        click("timeline-play");
        click("timeline-play");
        int paused = document().cursor();
        Thread.sleep(1400);
        assertEquals(paused, document().cursor(), "Pause cancels the pending playback tick");
        click("history-cursor");
        key(KeyCode.HOME);
        ready();
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        key(KeyCode.RIGHT);
        ready();
        assertEquals(3, document().cursor(), "Arrow navigation keeps focus on the marker");
        key(KeyCode.LEFT);
        ready();
        assertEquals(2, document().cursor());
        key(KeyCode.HOME);
        ready();
        click("timeline-play");
        var visited = new HashSet<Integer>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            visited.add(document().cursor());
            if (document().cursor() == 4
                    && fx(() -> !node("#timeline-play")
                            .getPseudoClassStates()
                            .contains(javafx.css.PseudoClass.getPseudoClass("playing")))) break;
            Thread.sleep(40);
        }
        ready();
        assertEquals(Set.of(0, 1, 2, 3, 4), visited);
        assertEquals(complete, document());
        assertEquals(19099.469035085127, volume(), .01);
    }

    @Test
    void historyScrollsLongDesignsAndStopsAtFailedOperations() throws Exception {
        ready();
        click("menu-fit");
        var session = new ModelSession(CadDocument.empty());
        for (int i = 0; i < 26; i++)
            session.add(new Feature.Sketch(
                    "s" + i, "p" + i, Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 20, 10)));
        session.add(new Feature.Extrude("broken", new Feature.ProfileRef("missing", "missing"), 5));
        Path file = directory.resolve("long history.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        assertTrue(fx(() -> node("#timeline-play").isDisabled()));
        assertTrue(fx(() -> node("#history-26").getStyleClass().contains("history-error")));
        click("timeline-previous");
        ready();
        assertEquals(26, document().cursor());
        assertFalse(fx(() -> node("#timeline-play").isDisabled()));
        assertTrue(fx(() -> ((ScrollPane) node("#timeline-scroll")).getHvalue()) > .9);
        click("history-25");
        assertTrue(fx(() -> node("#history-25").getStyleClass().contains("history-selected")));
        click("timeline-play");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (document().cursor() < 27 && System.nanoTime() < deadline) Thread.sleep(40);
        ready();
        assertEquals(27, document().cursor());
        assertFalse(fx(() -> node("#timeline-play")
                .getPseudoClassStates()
                .contains(javafx.css.PseudoClass.getPseudoClass("playing"))));
        assertTrue(fx(() -> node("#timeline-play").isDisabled()));
        click("timeline-first");
        ready();
        assertEquals(0, fx(() -> ((ScrollPane) node("#timeline-scroll")).getHvalue()), .01);
        click("history-0");
        assertEquals(0, document().cursor());
        click("timeline-play");
        click("new");
        click("unsaved-discard");
        ready();
        assertTrue(document().features().isEmpty());
        Thread.sleep(900);
        assertEquals(0, document().cursor(), "Changing documents cancels history playback");
    }

    @Test
    void sketchPlanePickerRejectsCurvedFacesAndObsoleteDocumentRevisions() throws Exception {
        ready();
        click("menu-fit");
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch("round", "profile", Feature.Plane.origin("XY"), new Feature.Circle(40, 30, 20)));
        session.add(new Feature.Extrude("cylinder", new Feature.ProfileRef("round", "profile"), 15));
        var file = directory.resolve("curved support.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        var before = fx(() -> workbench.controller().snapshot());
        click("constrained-sketch");
        standardView(2);
        modelClick(40, 20, 8);
        assertNull(fx(() -> stage.getScene().lookup("#sketch-editor")));
        assertNotNull(fx(() -> node("#sketch-plane-hint")));
        assertEquals(before, fx(() -> workbench.controller().snapshot()));
        // An asynchronous external change must invalidate all picks, including origin targets.
        fx(() -> {
            workbench.controller().edit("cylinder", "distance", 20);
            return null;
        });
        ready();
        assertNull(fx(() -> workbench.lookup("#sketch-plane-hint")));
        assertNull(fx(() -> ((SubScene) node("#viewport-scene")).getRoot().lookup("#sketch-plane-xy")));
        assertEquals(2, document().features().size());
        assertEquals(20, ((Feature.Extrude) document().features().getLast()).distance());
    }

    @Test
    void sketchPlanePickerCreatesAllOriginOrientationsAndCancelsWithoutHistory() throws Exception {
        ready();
        var empty = fx(() -> workbench.controller().snapshot());
        click("constrained-sketch");
        assertNotNull(fx(() -> node("#sketch-plane-hint")));
        assertNull(fx(() -> stage.getScene().lookup("#sketch-editor")));
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "sketch-origin-planes.png"));
            return null;
        });
        key(KeyCode.ESCAPE);
        assertEquals(empty, fx(() -> workbench.controller().snapshot()));
        int index = 0;
        for (String name : List.of("XY", "XZ", "YZ")) {
            click("constrained-sketch");
            // View perpendicular to the actual 3D pick target, then use a native mouse click.
            standardView(++index);
            var pick = Feature.Plane.origin(name).frame().apply(12, 12, 0);
            modelClick(pick[0], pick[1], pick[2]);
            sketchReady();
            sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
            sketchNumericPoint(0, 0);
            sketchNumericPoint(20, 10);
            click("constraint-finish");
            ready();
            assertEquals(
                    Feature.Plane.origin(name),
                    ((Feature.Sketch) document().features().getFirst()).plane());
            click("extrude");
            text("extrude-distance", "-5");
            click("commit-extrusion");
            ready();
            assertEquals(1000, volume(), .01);
            assertEquals(2, document().features().size());
            var p = Feature.Plane.origin(name).frame().apply(10, 5, -2);
            try (var result = new ModelSession(document()).evaluate()) {
                assertTrue(result.bodies().getFirst().solid().contains(p[0], p[1], p[2]));
            }
            click("undo");
            ready();
            click("undo");
            ready();
            assertTrue(document().features().isEmpty());
        }
    }

    @Test
    void bodyFaceSketchFollowsUpstreamEditAndSupportsPlaneRepair() throws Exception {
        openPrepared("plate-5mm.vxb");
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            click("constrained-sketch");
            standardView(1);
            fx(() -> {
                robot.mouseMove(workbench.viewport().modelToScreen(new Point3D(40, 30, 5)));
                return null;
            });
            Thread.sleep(180);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "sketch-plane-picker-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
            if (palette == 1) key(KeyCode.ESCAPE);
        }
        modelClick(40, 30, 5);
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        sketchNumericPoint(30, 25);
        sketchNumericPoint(40, 35);
        click("constraint-finish");
        ready();
        var attached = (Feature.Sketch) document().features().get(2);
        assertNotNull(attached.plane().support());
        click("extrude");
        text("extrude-distance", "3");
        click("commit-extrusion");
        ready();
        Path saved = directory.resolve("face sketch.vxb");
        save(saved);
        open(saved);
        click("history-1");
        text("dimension-distance", "8");
        click("apply-distance");
        ready();
        assertEquals(19500, volume(), .01);
        try (var result = new ModelSession(document()).evaluate()) {
            var boss = result.bodies().getLast().solid();
            assertEquals(8, boss.bounds().minZ(), .001);
            assertEquals(11, boss.bounds().maxZ(), .001);
        }
        click("history-2");
        click("change-sketch-plane");
        click("choose-plane-yz");
        ready();
        var repaired = (Feature.Sketch) document().features().get(2);
        assertEquals(attached.id(), repaired.id());
        assertEquals(attached.profile(), repaired.profile());
        assertEquals(Feature.Plane.origin("YZ"), repaired.plane());
        click("undo");
        ready();
        assertEquals(attached.plane(), ((Feature.Sketch) document().features().get(2)).plane());
        click("redo");
        ready();
        assertEquals(repaired, document().features().get(2));
    }

    @TempDir
    Path directory;

    private Stage stage;
    private Workbench workbench;
    private static Robot robot;
    private static Appearance.Choice originalAppearance;
    private UpdateFixture updates;
    private dev.voxelbrew.util.config.UserConfiguration configuration;

    @BeforeAll
    static void toolkit() throws Exception {
        originalAppearance = new Appearance().load();
        var started = new CountDownLatch(1);
        Launcher.configureGraphics();
        Platform.startup(() -> {
            Platform.setImplicitExit(false);
            started.countDown();
        });
        assertTrue(started.await(20, TimeUnit.SECONDS));
        fx(() -> {
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
    void window(TestInfo info) throws Exception {
        updates = new UpdateFixture(directory.resolve("update fixture"));
        configuration = new dev.voxelbrew.util.config.UserConfiguration(directory.resolve("preferences.properties"));
        String testName = info.getTestMethod().orElseThrow().getName();
        boolean firstLaunch = testName.startsWith("firstLaunch");
        if (!firstLaunch) configuration.set("language", testName.startsWith("polish") ? "pl" : "en");
        configuration.set("external.setting", "preserve me");
        Path plugins = Files.createDirectory(directory.resolve("plugins with spaces"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
        fx(() -> {
            stage = new Stage();
            if (System.getProperty("os.name").startsWith("Windows")) stage.setAlwaysOnTop(true);
            Locale previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
            try {
                if (firstLaunch) Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
                workbench = Workbench.create(
                        stage,
                        plugins,
                        new dev.voxelbrew.util.di.ApplicationModule(configuration, updates::service),
                        new dev.voxelbrew.gui.FileIoModule());
            } finally {
                Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
            }
            var screen = Screen.getPrimary().getVisualBounds();
            // Match the hosted Windows runner so clipped inspector fields require
            // scrolling.
            stage.setScene(workbench.createScene(
                    Math.min(964, screen.getWidth() - 60), Math.min(648, screen.getHeight() - 80)));
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
        try {
            fx(() -> {
                if (stage != null && stage.isShowing())
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
                if (workbench != null) workbench.close();
                if (stage != null) stage.hide();
                return null;
            });
            if (workbench != null) workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
        } finally {
            if (updates != null) updates.close();
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    @Test
    void integratedHeaderMovesResizesMaximizesAndMinimizesWithoutEditing() throws Exception {
        ready();
        assertEquals(StageStyle.UNDECORATED, fx(stage::getStyle));
        assertEquals(0, fx(() -> stage.getScene().getY()), 0.1, "No duplicate native title row");
        var snapshot = fx(() -> workbench.controller().snapshot());
        var start = windowBounds();
        // Leave room for the bottom-right resize on the runner's 1024 x 768 display.
        // Moving right would put that corner off screen, beyond the native mouse's reach.
        primaryDrag(centerOf("document-title"), -20, -10);
        var moved = windowBounds();
        assertEquals(start.getMinX() - 20, moved.getMinX(), 2);
        assertEquals(start.getMinY() - 10, moved.getMinY(), 2);
        var screen = fx(() -> Screen.getPrimary().getVisualBounds());
        assertTrue(
                screen.contains(moved.getMaxX() + 33, moved.getMaxY() + 23),
                "The complete resize gesture must stay inside the primary display");
        primaryDrag(new Point2D(moved.getMaxX() - 2, moved.getMaxY() - 2), 35, 25);
        var resized = windowBounds();
        assertEquals(moved.getWidth() + 35, resized.getWidth(), 2);
        assertEquals(moved.getHeight() + 25, resized.getHeight(), 2);
        assertEquals(moved.getMinX(), resized.getMinX(), 2);
        primaryDrag(new Point2D(resized.getMinX() + 2, resized.getMinY() + 2), 15, 10);
        var restored = windowBounds();
        assertEquals(resized.getMaxX(), restored.getMaxX(), 2);
        assertEquals(resized.getMaxY(), restored.getMaxY(), 2);
        assertEquals(resized.getWidth() - 15, restored.getWidth(), 2);
        click("window-maximize");
        assertWindowBounds(screen);
        assertTrue(fx(() -> node("#window-maximize").getAccessibleHelp().startsWith("Restore")));
        doubleClickHeader();
        assertWindowBounds(restored);
        doubleClickHeader();
        assertWindowBounds(screen);
        primaryDrag(centerOf("document-title"), 35, 40);
        assertEquals(restored.getWidth(), windowBounds().getWidth(), 2);
        assertEquals(restored.getHeight(), windowBounds().getHeight(), 2);
        // Recenter before native input on the mixed-DPI Windows desktop.
        fx(() -> {
            stage.centerOnScreen();
            return null;
        });
        Thread.sleep(250);
        click("window-minimize");
        assertTrue(fx(stage::isIconified));
        fx(() -> {
            stage.setIconified(false);
            stage.toFront();
            stage.requestFocus();
            return null;
        });
        Thread.sleep(400);
        assertFalse(fx(stage::isIconified));
        assertEquals(snapshot, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(() -> workbench.controller().dirty()));
        assertButtonDescriptions();
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "window-header-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
    }

    @Test
    void integratedCloseRespectsModalCancelSketchDraftsAndSave() throws Exception {
        rectangle(0);
        var original = document();
        click("window-close");
        assertNotNull(fx(() -> node("#unsaved-cancel")));
        assertFalse(fx(() -> node("#window-close").isDisabled()));
        assertTrue(fx(() -> node("#main-menu").isDisabled()));
        click("window-close"); // The topmost dialog handles Close as Cancel.
        assertTrue(fx(stage::isShowing));
        assertFalse(fx(workbench::isDisabled));
        assertEquals(original, document());
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertFalse(fx(() -> node("#window-minimize").isDisabled()));
        click("window-close"); // Dismiss the uncommitted sketch, keeping the document open.
        assertTrue(fx(stage::isShowing));
        assertEquals(original, document());
        click("save-as");
        assertNotNull(fx(() -> node("#file-path")));
        click("window-close");
        assertTrue(fx(stage::isShowing));
        assertTrue(fx(() -> workbench.controller().dirty()));
        click("window-close");
        click("unsaved-cancel");
        assertEquals(original, document());
        click("window-close");
        click("unsaved-save");
        Path file = directory.resolve("Saved by integrated Close.vxb");
        text("file-path", file.toString());
        click("file-confirm");
        awaitWindowClosed();
        assertEquals(original, DocumentReader.load(file));
    }

    @Test
    void integratedCloseDiscardsOnlyAfterTheExplicitChoice() throws Exception {
        rectangle(0);
        click("window-close");
        assertTrue(fx(stage::isShowing));
        click("unsaved-discard");
        awaitWindowClosed();
    }

    private void awaitWindowClosed() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (fx(stage::isShowing) && System.nanoTime() < deadline) Thread.sleep(30);
        assertFalse(fx(stage::isShowing));
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
    }

    private javafx.geometry.Rectangle2D windowBounds() throws Exception {
        return fx(
                () -> new javafx.geometry.Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()));
    }

    private void assertWindowBounds(javafx.geometry.Rectangle2D expected) throws Exception {
        // Native window managers acknowledge move/resize requests asynchronously.
        // Wait for all four bounds rather than sampling during a partial update.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var actual = windowBounds();
        while ((Math.abs(expected.getMinX() - actual.getMinX()) > 2
                        || Math.abs(expected.getMinY() - actual.getMinY()) > 2
                        || Math.abs(expected.getWidth() - actual.getWidth()) > 2
                        || Math.abs(expected.getHeight() - actual.getHeight()) > 2)
                && System.nanoTime() < deadline) {
            Thread.sleep(30);
            actual = windowBounds();
        }
        assertEquals(expected.getMinX(), actual.getMinX(), 2);
        assertEquals(expected.getMinY(), actual.getMinY(), 2);
        assertEquals(expected.getWidth(), actual.getWidth(), 2);
        assertEquals(expected.getHeight(), actual.getHeight(), 2);
    }

    private Point2D centerOf(String id) throws Exception {
        return fx(() -> {
            var control = node("#" + id);
            var bounds = control.localToScreen(control.getBoundsInLocal());
            return new Point2D(bounds.getCenterX(), bounds.getCenterY());
        });
    }

    private void doubleClickHeader() throws Exception {
        var point = centerOf("document-title");
        fx(() -> {
            robot.mouseMove(point);
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(250);
    }

    @Test
    void bodyContextRenameAndInlineEditingPersistWithoutChangingGeometry() throws Exception {
        createRenameBodies();
        var original = document();
        long before = fx(() -> workbench.controller().snapshot().revision());
        var ids = fx(() -> workbench.controller().build().bodies().stream()
                .map(DocumentController.Body::id)
                .toList());
        double volume =
                fx(() -> workbench.controller().build().bodies().getFirst().volume());
        bodyTreeClick("Body 1", MouseButton.SECONDARY, 1);
        assertFalse(fx(() -> node("#body-rename").getAccessibleHelp().isBlank()));
        click("body-rename");
        assertEquals("Body 1", fx(() -> ((TextField) node("#body-rename-field")).getText()));
        text("body-rename-field", "Mounting plate");
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-rename-light.png"));
            return null;
        });
        key(KeyCode.ENTER);
        ready();
        assertEquals(Map.of("named0.body", "Mounting plate"), document().bodyNames());
        assertEquals(before + 1, fx(() -> workbench.controller().snapshot().revision()));
        assertEquals(original.features(), document().features());
        assertEquals(
                ids,
                fx(() -> workbench.controller().build().bodies().stream()
                        .map(DocumentController.Body::id)
                        .toList()));
        assertEquals(
                volume,
                fx(() -> workbench.controller().build().bodies().getFirst().volume()),
                0.000001);
        click("undo");
        ready();
        assertTrue(document().bodyNames().isEmpty());
        click("redo");
        ready();
        long renamed = fx(() -> workbench.controller().snapshot().revision());
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 1);
        assertNull(
                fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()),
                "A single click must not resize or scroll the selected row before a double-click");
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        key(KeyCode.ENTER);
        ready();
        assertEquals(
                renamed,
                fx(() -> workbench.controller().snapshot().revision()),
                "Accepting an unchanged name creates no undo entry");
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        text("body-rename-field", "");
        key(KeyCode.ENTER);
        assertFalse(fx(() -> ((Label) node("#body-rename-error")).getText().isBlank()));
        assertEquals(renamed, fx(() -> workbench.controller().snapshot().revision()));
        text("body-rename-field", "Discard this");
        key(KeyCode.ESCAPE);
        assertEquals(renamed, fx(() -> workbench.controller().snapshot().revision()));
        assertEquals("Mounting plate", document().bodyNames().get("named0.body"));
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        text("body-rename-field", "Uncommitted");
        click("body-material-name");
        assertNull(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()));
        assertEquals(renamed, fx(() -> workbench.controller().snapshot().revision()));
        Path file = directory.resolve("named bodies.vxb");
        click("save-as");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertEquals("Mounting plate", document().bodyNames().get("named0.body"));
        assertEquals(
                ids,
                fx(() -> workbench.controller().build().bodies().stream()
                        .map(DocumentController.Body::id)
                        .toList()));
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        text("body-rename-field", "Stale draft");
        fx(() -> {
            workbench.controller().cursor(3);
            return null;
        });
        ready();
        assertNull(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()));
        bodyTreeClick("Mounting plate", MouseButton.SECONDARY, 1);
        assertTrue(
                fx(() -> Window.getWindows().stream()
                        .filter(ContextMenu.class::isInstance)
                        .map(ContextMenu.class::cast)
                        .filter(Window::isShowing)
                        .flatMap(menu -> menu.getItems().stream())
                        .filter(item -> "body-rename".equals(item.getId()))
                        .findFirst()
                        .orElseThrow()
                        .isDisable()),
                "Cannot rename while rolled back");
        key(KeyCode.ESCAPE);
        bodyTreeClick("Mounting plate", MouseButton.PRIMARY, 2);
        assertNull(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()));
        fx(() -> {
            workbench.controller().cursor(4);
            return null;
        });
        ready();
        assertEquals("Mounting plate", document().bodyNames().get("named0.body"));
        String featureLabel = fx(() -> ((TreeView<?>) node("#model-tree"))
                .getRoot()
                .getChildren()
                .getFirst()
                .getChildren()
                .getFirst()
                .getValue()
                .toString());
        bodyTreeClick(featureLabel, MouseButton.PRIMARY, 2);
        assertNotNull(
                fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()),
                "Feature rows support their own name editing");
        text("feature-rename-field", "Base profile");
        key(KeyCode.ENTER);
        ready();
        assertEquals(
                "Base profile",
                document().featureNames().get(document().features().getFirst().id()));
        assertEquals("Mounting plate", document().bodyNames().get("named0.body"));
    }

    @Test
    void polishBodyRenameTargetsTheClickedBodyAndSupportsDarkPalette() throws Exception {
        createRenameBodies();
        choice("appearance", 2);
        var translations = new dev.voxelbrew.util.i18n.Translations(Locale.forLanguageTag("pl"));
        bodyTreeClick(translations.text("feature.body", 2), MouseButton.SECONDARY, 1);
        assertEquals("Zmień nazwę", fx(() -> ((Label) node("#body-rename").lookup(".label")).getText()));
        fx(() -> {
            Screenshot.save(
                    node("#body-rename").getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-context-pl.png"));
            return null;
        });
        click("body-rename");
        text("body-rename-field", "Obudowa");
        fx(() -> {
            var editor = node("#body-rename-field");
            var clip = node("#model-tree").lookup(".clipped-container");
            assertNotNull(clip);
            var fieldBounds = editor.localToScene(editor.getLayoutBounds());
            var visibleBounds = clip.localToScene(clip.getLayoutBounds());
            assertTrue(fieldBounds.getMinY() >= visibleBounds.getMinY() - 1);
            assertTrue(
                    fieldBounds.getMaxY() <= visibleBounds.getMaxY() + 1,
                    "The entire inline editor must remain visible on the last row");
            return null;
        });
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-rename-dark.png"));
            return null;
        });
        key(KeyCode.ENTER);
        ready();
        assertEquals(Map.of("named1.body", "Obudowa"), document().bodyNames());
        long revision = fx(() -> workbench.controller().snapshot().revision());
        bodyTreeClick("Obudowa", MouseButton.SECONDARY, 1);
        click("body-fit");
        assertEquals(revision, fx(() -> workbench.controller().snapshot().revision()));
        bodyTreeClick("Obudowa", MouseButton.PRIMARY, 2);
        fx(() -> {
            workbench.controller().newDocument();
            return null;
        });
        ready();
        assertTrue(document().bodyNames().isEmpty());
        assertNull(fx(() -> ((TreeView<?>) node("#model-tree")).getEditingItem()));
    }

    private void createRenameBodies() throws Exception {
        fx(() -> {
            for (int i = 0; i < 2; i++) {
                workbench
                        .controller()
                        .add(new Feature.Sketch(
                                "named-sketch" + i,
                                "profile" + i,
                                new Feature.Plane("origin-xy", 0),
                                new Feature.Rectangle(i * 40, 0, 30, 30)));
                workbench
                        .controller()
                        .add(new Feature.Extrude(
                                "named" + i, new Feature.ProfileRef("named-sketch" + i, "profile" + i), 5));
            }
            return null;
        });
        ready();
    }

    private void bodyTreeClick(String label, MouseButton button, int count) throws Exception {
        focus(stage);
        int targetRow = fx(() -> {
            var view = (TreeView<?>) node("#model-tree");
            for (int row = 0; row < view.getExpandedItemCount(); row++) {
                if (label.equals(view.getTreeItem(row).getValue().toString())) {
                    view.scrollTo(row);
                    return row;
                }
            }
            throw new AssertionError("Missing body item " + label);
        });
        Thread.sleep(150);
        var point = fx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            var cell = node("#model-tree").lookupAll(".tree-cell").stream()
                    .filter(n ->
                            n instanceof TreeCell<?> row && row.getIndex() == targetRow && label.equals(row.getText()))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing body label " + label));
            var bounds = cell.localToScreen(cell.getBoundsInLocal());
            return new Point2D(bounds.getCenterX(), bounds.getCenterY());
        });
        for (int i = 0; i < count; i++) {
            fx(() -> {
                robot.mouseMove(point);
                robot.mouseClick(button);
                return null;
            });
            Thread.sleep(80);
        }
        Thread.sleep(250);
        assertEquals(
                label,
                fx(() -> ((TreeView<?>) node("#model-tree"))
                        .getSelectionModel()
                        .getSelectedItem()
                        .getValue()
                        .toString()),
                "Native click selects the intended row");
    }

    @Test
    void bodyMaterialAndColorPickingUndoSaveAndBothPalettes() throws Exception {
        var session = new ModelSession(CadDocument.empty());
        for (int i = 0; i < 2; i++) {
            session.add(new Feature.Sketch(
                    "sketch" + i,
                    "profile" + i,
                    new Feature.Plane("origin-xy", 0),
                    new Feature.Rectangle(i * 40, 0, 30, 30)));
            session.add(new Feature.Extrude("solid" + i, new Feature.ProfileRef("sketch" + i, "profile" + i), 5));
        }
        Path file = directory.resolve("red PETG and blue PLA.vxb");
        DocumentWriter.save(session.document(), file, false);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        modelClick(15, 15, 5);
        text("body-material-name", "PETG");
        pickBodyColor(javafx.scene.paint.Color.RED);
        assertTrue(document().bodyStyles().isEmpty(), "Editing controls is only a draft");
        click("body-style-apply");
        ready();
        var red = new BodyStyle("PETG", "#FF0000");
        assertEquals(red, document().bodyStyle("solid0.body"));
        assertTrue(fx(() -> workbench.controller().dirty()));
        click("undo");
        ready();
        assertTrue(document().bodyStyles().isEmpty());
        click("redo");
        ready();
        assertEquals(red, document().bodyStyle("solid0.body"));
        modelClick(55, 15, 5);
        text("body-material-name", "PLA");
        pickBodyColor(javafx.scene.paint.Color.BLUE);
        click("body-style-apply");
        ready();
        assertEquals(new BodyStyle("PLA", "#0000FF"), document().bodyStyle("solid1.body"));
        click("save");
        ready();
        assertEquals(document(), DocumentReader.load(file));
        click("new");
        ready();
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            // Selecting a feature clears body highlighting and exposes the exact chosen colors.
            click("history-0");
            fx(() -> {
                for (int i = 0; i < 2; i++) {
                    var view = findBodyView(workbench.viewport(), "body-solid" + i + ".body");
                    assertNotNull(view);
                    assertEquals(
                            i == 0 ? javafx.scene.paint.Color.RED : javafx.scene.paint.Color.BLUE,
                            ((javafx.scene.paint.PhongMaterial) view.getMaterial()).getDiffuseColor());
                }
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"), "body-colors-" + palette + ".png"));
                return null;
            });
        }
        modelClick(55, 15, 5);
        assertEquals("PLA", fx(() -> ((TextField) node("#body-material-name")).getText()));
        assertButtonDescriptions();
        click("body-style-reset");
        ready();
        assertEquals(BodyStyle.DEFAULT, document().bodyStyle("solid1.body"));
        assertEquals(red, document().bodyStyle("solid0.body"));
    }

    private static javafx.scene.shape.MeshView findBodyView(Node node, String id) {
        if (node instanceof javafx.scene.shape.MeshView mesh && id.equals(mesh.getId())) return mesh;
        if (node instanceof SubScene scene) return findBodyView(scene.getRoot(), id);
        if (node instanceof Parent parent) {
            for (var child : parent.getChildrenUnmodifiable()) {
                var found = findBodyView(child, id);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void pickBodyColor(javafx.scene.paint.Color color) throws Exception {
        click("body-color");
        fx(() -> {
            Node square = Window.getWindows().stream()
                    .filter(Window::isShowing)
                    .filter(w -> w.getScene() != null)
                    .flatMap(w -> w.getScene().getRoot().lookupAll(".color-square").stream())
                    .filter(n -> n instanceof Parent p
                            && p.getChildrenUnmodifiable().stream()
                                    .anyMatch(c ->
                                            c instanceof javafx.scene.shape.Rectangle r && color.equals(r.getFill())))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Missing palette color " + color));
            var bounds = square.localToScreen(square.getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(150);
        assertEquals(color, fx(() -> ((ColorPicker) node("#body-color")).getValue()));
    }

    @Test
    void firstLaunchUsesSystemDisplayLanguageAndShowsSystemPreference() throws Exception {
        ready();
        assertEquals("Zapisz", fx(() -> command(workbench, "save").getText()));
        assertEquals("Widok: Góra", fx(() -> node("#view-cube-top").getAccessibleText()));
        assertFalse(configuration.read().containsKey("language"));
        click("language");
        assertEquals("system", fx(() -> ((ComboBox<?>) node("#language-choice")).getValue()));
        assertEquals("Zapisz", fx(() -> ((Button) node("#language-save")).getText()));
        click("language-close");
        assertFalse(configuration.read().containsKey("language"));
        assertEquals("preserve me", configuration.get("external.setting", ""));
        assertFalse(fx(() -> workbench.controller().dirty()));
    }

    @Test
    void polishWorkbenchDialogsSketchPrintingAndLanguagePreferenceUseRealControls() throws Exception {
        ready();
        // One translated dialog wiring check; message/resource matrices live in UiTextTest.
        click("about");
        waitForAbout("Dostępna nowa wersja: 1.1.0");
        assertEquals("Wersja " + Version.version(), fx(() -> ((Label) node("#about-version")).getText()));
        assertEquals("Otwórz aktualizator…", fx(() -> ((Hyperlink) node("#about-updates")).getText()));
        assertTrue(fx(() ->
                ((Hyperlink) node("#about-license")).getTooltip().getText().contains("licencję")));
        click("about-close");
        ready();
        click("keyboard-shortcuts");
        assertNotNull(fx(() -> node("#keyboard-shortcuts-list")));
        assertTrue(fx(() -> node("#keyboard-shortcuts-list").lookupAll(".label").stream()
                .filter(Label.class::isInstance)
                .map(Label.class::cast)
                .anyMatch(label -> label.getText().equals("PROJEKTOWANIE 3D"))));
        closeShortcutHelp();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$");
        assertEquals(List.of(), completionNames());
        assertEquals(
                "Brak pasujących parametrów",
                fx(() -> ((Label) ((ListView<?>) node("#formula-suggestions")).getPlaceholder()).getText()));
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertNotNull(fx(() -> node("#sketch-point-x")), "Escape closes suggestions before the tool");
        click("constraint-cancel");
        assertEquals("Zapisz", fx(() -> command(workbench, "save").getText()));
        assertButtonDescriptions();
        assertEquals("Widok: Góra", fx(() -> node("#view-cube-top").getAccessibleText()));
        choice("appearance", 1);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertButtonDescriptions();
        assertEquals("Prostokąt", fx(() -> ((Button) node("#sketch-tool-rectangle")).getText()));
        assertShortcutCaption("sketch-tool-rectangle", "Prostokąt", "r");
        assertShortcutCaption("sketch-tool-circle", "Okrąg (C)", "C");
        assertShortcutCaption("sketch-tool-arc", "Łuk (A)", "A");
        saveShortcutLabels("pl-light");
        assertEquals("Zakończ szkic", fx(() -> ((Button) node("#constraint-finish")).getText()));
        assertTrue(fx(() ->
                ((Button) node("#constraint-horizontal")).getTooltip().getText().contains("Wybierz linię")));
        click("sketch-create-type");
        text("sketch-type-search", "Elipsa");
        assertEquals(
                List.of(SketchTools.Tool.ELLIPSE), fx(() -> ((ListView<?>) node("#sketch-type-results")).getItems()));
        assertTrue(fx(() -> node("#sketch-type-results").lookupAll(".list-cell").stream()
                .filter(ListCell.class::isInstance)
                .map(ListCell.class::cast)
                .anyMatch(cell -> "Elipsa".equals(cell.getText()))));
        key(KeyCode.ENTER);
        click("constraint-cancel");
        click("parameters");
        assertEquals("Zastosuj", fx(() -> ((Button) node("#parameters-apply")).getText()));
        assertButtonDescriptions();
        click("parameters-cancel");
        rectangle(0);
        click("extrude");
        text("extrude-distance", "=1+2/3");
        assertTrue(
                fx(() -> ((TextField) node("#extrude-distance")).getPromptText().startsWith("Liczba")));
        assertTrue(fx(() ->
                ((TextField) node("#extrude-distance")).getTooltip().getText().startsWith("ƒx  Wynik:")));
        click("commit-extrusion");
        ready();
        var part = document();
        Path file = directory.resolve("polish-model.vxb");
        save(file);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertEquals(part, document());
        click("export");
        assertEquals("Eksportuj", fx(() -> ((Button) node("#export-confirm")).getText()));
        chooseExporter("3mf");
        assertPathRowLabels("export-path");
        assertEquals("PROJEKT", fx(() -> {
            @SuppressWarnings("unchecked")
            var combo = (ComboBox<dev.voxelbrew.exporter.ExportSelection.Space>) node("#export-space");
            return combo.getConverter().toString(combo.getValue());
        }));
        key(KeyCode.ESCAPE);
        click("workspace-print");
        click("print-tab-process");
        assertEquals("Podpory automatyczne", fx(() -> ((CheckBox) node("#print-auto-supports")).getText()));
        click("workspace-design");
        for (int palette : new int[] {1, 2}) {
            choice("appearance", palette);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(
                                System.getProperty("voxelbrew.ui.artifacts"),
                                "polish-" + (palette == 1 ? "light" : "dark") + ".png"));
                return null;
            });
        }
        click("language");
        choice("language-choice", 1);
        click("language-save");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(() -> ((Label) node("#language-status")).getText().startsWith("Zapisano"))) {
            assertTrue(System.nanoTime() < deadline, "Language preference saved");
            Thread.sleep(30);
        }
        assertEquals("en", configuration.get("language", ""));
        assertEquals("preserve me", configuration.get("external.setting", ""));
        click("language-close");
        assertEquals(part, document());
        assertFalse(fx(() -> workbench.controller().dirty()));
        assertEquals("Zapisz", fx(() -> command(workbench, "save").getText()));
        assertButtonDescriptions();
        // A new window reads the saved preference; the existing one retains its own catalog.
        fx(() -> {
            var reopenedStage = new Stage();
            var reopened = Workbench.create(
                    reopenedStage,
                    directory.resolve("plugins with spaces"),
                    new dev.voxelbrew.util.di.ApplicationModule(configuration, updates::service),
                    new dev.voxelbrew.gui.FileIoModule());
            try {
                reopenedStage.setScene(reopened.createScene(964, 648));
                reopenedStage.show();
                assertEquals("Save", command(reopened, "save").getText());
            } finally {
                reopened.close();
                reopenedStage.hide();
            }
            return null;
        });
    }

    @Test
    void daggerGraphsIsolateWindowsPreferencesAndWorkerLifetimes() throws Exception {
        fx(() -> {
            var config = new dev.voxelbrew.util.config.UserConfiguration(directory.resolve("isolated.properties"));
            config.set("language", "pl");
            config.set("appearance", "DARK");
            var secondStage = new Stage();
            var graph = DaggerGuiComponent.factory()
                    .create(
                            secondStage,
                            directory.resolve("isolated plugins"),
                            new dev.voxelbrew.util.di.ApplicationModule(config, () -> {
                                throw new AssertionError("Startup must not create an updater");
                            }),
                            new dev.voxelbrew.gui.FileIoModule());
            var second = graph.workbench();
            try {
                secondStage.setScene(second.createScene(964, 648));
                secondStage.show();
                assertSame(second, graph.workbench());
                assertNotSame(workbench.controller(), second.controller());
                assertNotSame(
                        workbench.controller().snapshot().document(),
                        second.controller().snapshot().document());
                assertNotSame(
                        workbench.controller().snapshot().document().uuidGenerator(),
                        second.controller().snapshot().document().uuidGenerator());
                assertEquals(
                        new dev.voxelbrew.util.i18n.Translations(Locale.forLanguageTag("pl")).text("action.save"),
                        command(second, "save").getText());
                assertTrue(second.getStyleClass().contains("dark"));
                second.close();
                second.close();
                var resources = graph.resources();
                assertTrue(List.of(
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

    private static MenuItem command(Workbench workbench, String id) {
        return ((MenuBar) workbench.getScene().lookup("#main-menu"))
                .getMenus().stream()
                        .flatMap(WorkbenchUiTest::menuItems)
                        .filter(item -> id.equals(item.getId()))
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("Missing command: " + id));
    }

    @Test
    void recentMenuReopensSavedDocument() throws Exception {
        ready();
        openPrepared("plate-5mm.vxb");
        Path saved = directory.resolve("recent design.vxb");
        save(saved);
        var savedDocument = document();
        shortcut(KeyCode.N, false);
        ready();
        assertTrue(document().features().isEmpty());
        click("menu-file");
        fx(() -> {
            var recent = (Menu) command(workbench, "menu-open-recent");
            recent.show();
            assertEquals(saved.toString(), recent.getItems().getFirst().getText());
            recent.getItems().getFirst().fire();
            recent.hide();
            return null;
        });
        ready();
        assertEquals(savedDocument, document());
        assertEquals(saved, fx(() -> workbench.controller().path()));
        Path second = directory.resolve("second recent.vxb");
        Path third = directory.resolve("third recent.vxb");
        save(second);
        save(third);
        shortcut(KeyCode.N, false);
        ready();
        click("open");
        assertEquals(third.toString(), fx(() -> ((Hyperlink) node("#file-recent-0")).getText()));
        assertEquals(second.toString(), fx(() -> ((Hyperlink) node("#file-recent-1")).getText()));
        assertEquals(saved.toString(), fx(() -> ((Hyperlink) node("#file-recent-2")).getText()));
        assertEquals(
                4,
                fx(() -> ((javafx.scene.layout.VBox) node("#file-recent-projects"))
                        .getChildren()
                        .size()));
        click("file-recent-2");
        ready();
        assertEquals(savedDocument, document());
        assertEquals(saved, fx(() -> workbench.controller().path()));
    }

    @Test
    void menusDescribeActionsAndProtectModalEditing() throws Exception {
        var artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        ready();
        assertButtonDescriptions();
        assertNull(fx(() -> workbench.lookup("#modeling-toolbar").lookup("#save")));
        click("menu-file");
        assertEquals("Save", fx(() -> command(workbench, "save").getText()));
        assertNotNull(fx(() -> command(workbench, "save-as").getAccelerator()));
        assertNotNull(fx(() -> node("#save").getAccessibleHelp()), "Menu description is attached to the popup node");
        fx(() -> {
            var bounds = node("#save").localToScreen(node("#save").getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> Window.getWindows().stream()
                        .anyMatch(window -> window instanceof Tooltip tip
                                && tip.isShowing()
                                && tip.getText().contains("features and parameters")))
                && System.nanoTime() < deadline) Thread.sleep(40);
        assertTrue(
                fx(() -> Window.getWindows().stream()
                        .anyMatch(window -> window instanceof Tooltip tip
                                && tip.isShowing()
                                && tip.getText().contains("features and parameters"))),
                "Save description is a real hover popup");
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-menu-light.png"));
            for (var window : Window.getWindows())
                if (window instanceof Tooltip && window.isShowing())
                    Screenshot.save(window.getScene(), artifacts.resolve("save-tooltip.png"));
            return null;
        });
        key(KeyCode.ESCAPE);
        assertFalse(fx(() -> ((MenuBar) workbench.getScene().lookup("#main-menu"))
                .getMenus()
                .getFirst()
                .isShowing()));
        String documentId = document().id();
        click("save-as");
        assertButtonDescriptions();
        assertTrue(
                fx(() -> command(workbench, "new").isDisable()),
                "Document commands are disabled behind a modal dialog");
        shortcut(KeyCode.N, false);
        assertEquals(documentId, document().id());
        assertNotNull(fx(() -> node("#file-path")));
        key(KeyCode.ESCAPE);
        hoverHelp("constrained-sketch");
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertNotNull(fx(() -> node("#sketch-editor")), "Help must not consume the modeling click");
        click("constraint-cancel");
        hoverHelp("menu-file");
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertNotNull(fx(() -> node("#sketch-editor")), "Menu help must not cover a neighboring action");
        click("constraint-cancel");
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertButtonDescriptions();
        click("constraint-cancel");
        save(directory.resolve("help-workspace.vxb"));
        click("workspace-print");
        for (String tab : List.of("placement", "process", "supports", "job")) {
            click("print-tab-" + tab);
            assertButtonDescriptions();
        }
        click("workspace-design");
        choice("appearance", 2);
        assertButtonDescriptions();
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-dark.png"));
            return null;
        });
        click("menu-file");
        fx(() -> {
            var popup = node("#save").getScene();
            assertTrue(popup.getRoot().getStyleClass().contains("dark"));
            Screenshot.save(popup, artifacts.resolve("file-menu-dark.png"));
            return null;
        });
        key(KeyCode.ESCAPE);
    }

    private static java.util.stream.Stream<MenuItem> menuItems(Menu menu) {
        return menu.getItems().stream()
                .flatMap(item -> item instanceof Menu child
                        ? java.util.stream.Stream.concat(java.util.stream.Stream.of(item), menuItems(child))
                        : java.util.stream.Stream.of(item));
    }

    private void assertButtonDescriptions() throws Exception {
        fx(() -> {
            for (var node : stage.getScene().getRoot().lookupAll("*")) {
                if (node instanceof ButtonBase button && button.isVisible()) {
                    assertNotNull(
                            button.getAccessibleHelp(),
                            "Missing description: " + button.getId() + " " + button.getText());
                    assertFalse(button.getAccessibleHelp().isBlank());
                    assertTrue(
                            button.getTooltip() != null
                                    || button.getProperties().containsKey(UiHelp.class),
                            "Missing popup: " + button.getId());
                }
            }
            return null;
        });
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
        if (id.equals("undo") || id.equals("redo")) {
            click("menu-edit");
            click("menu-" + id);
            return;
        }
        String menu = switch (id) {
            case "extrude", "cut", "fillet", "chamfer", "shell", "split-body" -> "operations-menu";
            case "new", "open", "import", "save", "save-as", "export" -> "menu-file";
            case "language", "updates", "about", "keyboard-shortcuts" -> "menu-settings";
            case "appearance", "menu-fit" -> "menu-view";
            default -> null;
        };
        if (menu != null) click(menu);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!fx(() -> {
                    try {
                        node("#" + id);
                        return true;
                    } catch (AssertionError missing) {
                        return false;
                    }
                })
                && System.nanoTime() < deadline) Thread.sleep(40);
        var window = fx(() -> node("#" + id).getScene().getWindow());
        if (window instanceof ContextMenu) {
            // Native popup coordinates can belong to a different DPI monitor in Windows Robot.
            // Navigate the visible popup with real keys and observe focus before accepting it.
            var item = fx(() -> node("#" + id));
            assertFalse(fx(item::isDisabled), id + " is disabled");
            for (int step = 0; step < 100 && !fx(item::isFocused); step++) key(KeyCode.DOWN);
            assertTrue(fx(item::isFocused), "Native menu selection reached " + id);
            key(KeyCode.ENTER);
            Thread.sleep(180);
            return;
        }
        if (!(window instanceof javafx.stage.PopupWindow)) focus(window);
        reveal(id);
        fx(() -> {
            window.getScene().getRoot().applyCss();
            window.getScene().getRoot().layout();
            var node = node("#" + id);
            assertFalse(node.isDisabled(), id + " is disabled");
            var bounds = node.localToScreen(node.getBoundsInLocal());
            assertNotNull(bounds);
            assertTrue(bounds.getCenterY() < window.getY() + window.getHeight(), "Control outside window: " + id);
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(120);
        var target = fx(() -> node("#" + id));
        // Native menu rows may act on hover and redirect input into their submenu popup.
        int expected = target instanceof ButtonBase ? 1 : 0;
        var pressed = new CountDownLatch(expected);
        // Native menu popups can receive the release after the button receives the press.
        var released = new CountDownLatch(target instanceof MenuButton ? 0 : expected);
        boolean[] releaseSent = {false};
        EventHandler<MouseEvent> onPress = e -> pressed.countDown();
        EventHandler<MouseEvent> onRelease = e -> released.countDown();
        fx(() -> {
            target.addEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
            target.addEventFilter(MouseEvent.MOUSE_RELEASED, onRelease);
            return null;
        });
        try {
            // Robot queues native input. Wait for delivery rather than assuming the OS and
            // renderer will process each event within a fixed delay; never replay a click.
            fx(() -> {
                robot.mousePress(MouseButton.PRIMARY);
                return null;
            });
            assertTrue(pressed.await(3, TimeUnit.SECONDS), "Mouse press reached " + id);
            Thread.sleep(60);
            fx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                releaseSent[0] = true;
                return null;
            });
            assertTrue(released.await(3, TimeUnit.SECONDS), "Mouse release reached " + id);
        } finally {
            fx(() -> {
                if (!releaseSent[0]) robot.mouseRelease(MouseButton.PRIMARY);
                target.removeEventFilter(MouseEvent.MOUSE_PRESSED, onPress);
                target.removeEventFilter(MouseEvent.MOUSE_RELEASED, onRelease);
                return null;
            });
        }
        Thread.sleep(180);
    }

    private void hoverHelp(String id) throws Exception {
        var control = fx(() -> (Control) node("#" + id));
        fx(() -> {
            var bounds = control.localToScreen(control.getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (!fx(() -> control.getTooltip().isShowing()) && System.nanoTime() < deadline) Thread.sleep(40);
        assertTrue(fx(() -> control.getTooltip().isShowing()), "Hover help for " + id);
    }

    private void reveal(String id) throws Exception {
        for (int attempt = 0; attempt < 20; attempt++) {
            int direction = fx(() -> {
                var target = node("#" + id);
                target.getScene().getRoot().applyCss();
                target.getScene().getRoot().layout();
                var bounds = target.localToScreen(target.getBoundsInLocal());
                for (var parent = target.getParent(); parent != null; parent = parent.getParent()) {
                    if (!(parent instanceof ScrollPane scroll)) continue;
                    var viewport = scroll.lookup(".viewport");
                    var visible = viewport.localToScreen(viewport.getBoundsInLocal());
                    if (bounds.getMinY() >= visible.getMinY() && bounds.getMaxY() <= visible.getMaxY()) continue;
                    robot.mouseMove(visible.getCenterX(), visible.getCenterY());
                    return bounds.getCenterY() < visible.getCenterY() ? -3 : 3;
                }
                return 0;
            });
            if (direction == 0) return;
            Thread.sleep(100);
            fx(() -> {
                robot.mouseWheel(direction);
                return null;
            });
            Thread.sleep(150);
        }
        fail("Could not scroll control into view: " + id);
    }

    private void focus(Window window) throws Exception {
        if (!fx(window::isFocused)) {
            fx(() -> {
                if (window instanceof Stage target) target.toFront();
                window.requestFocus();
                return null;
            });
            Thread.sleep(200);
            if (!fx(window::isFocused)) {
                fx(() -> {
                    var point = window.getScene().getRoot().localToScreen(20, 20);
                    robot.mouseMove(point);
                    robot.mouseClick(MouseButton.PRIMARY);
                    return null;
                });
                Thread.sleep(150);
                fx(() -> {
                    window.requestFocus();
                    return null;
                });
                Thread.sleep(200);
            }
        }
    }

    private void key(KeyCode code) throws Exception {
        fx(() -> {
            robot.keyType(code);
            return null;
        });
        Thread.sleep(100);
    }

    private void openShortcutHelp() throws Exception {
        focus(stage);
        if (fx(() -> stage.getScene().lookup("#sketch-editor") == null)) click("keyboard-shortcuts");
        else key(KeyCode.F1);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fx(() -> node("#keyboard-shortcuts-close").isFocused()) && System.nanoTime() < deadline)
            Thread.sleep(20);
        assertTrue(
                fx(() -> node("#keyboard-shortcuts-close").isFocused()),
                "The shortcut reference gives its Close button keyboard focus");
    }

    private void closeShortcutHelp() throws Exception {
        key(KeyCode.ESCAPE);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (fx(() -> stage.getScene().lookup("#keyboard-shortcuts-list") != null) && System.nanoTime() < deadline)
            Thread.sleep(20);
        assertNull(
                fx(() -> stage.getScene().lookup("#keyboard-shortcuts-list")),
                "One Escape closes the shortcut reference");
    }

    private void shortcut(KeyCode code, boolean shift) throws Exception {
        KeyCode modifier = System.getProperty("os.name").startsWith("Mac") ? KeyCode.META : KeyCode.CONTROL;
        focus(stage);
        assertTrue(fx(stage::isFocused), "Window focus before shortcut");
        fx(() -> {
            robot.keyPress(modifier);
            return null;
        });
        Thread.sleep(60);
        if (shift) {
            fx(() -> {
                robot.keyPress(KeyCode.SHIFT);
                return null;
            });
            Thread.sleep(60);
        }
        key(code);
        if (shift) {
            fx(() -> {
                robot.keyRelease(KeyCode.SHIFT);
                return null;
            });
            Thread.sleep(60);
        }
        fx(() -> {
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
            fx(() -> {
                robot.keyType(KeyCode.DELETE);
                return null;
            });
            Thread.sleep(20);
        }
        Thread.sleep(150);
        assertEquals("", fx(() -> ((TextField) node("#" + id)).getText()), "Robot field clearing: " + id);
        var entered = new StringBuilder();
        for (char c : value.toCharArray()) {
            fx(() -> {
                boolean shift = Character.isUpperCase(c) || ":_~+$\"()*^".indexOf(c) >= 0;
                KeyCode code;
                if (Character.isLetter(c)) code = KeyCode.valueOf(Character.toString(Character.toUpperCase(c)));
                else if (Character.isDigit(c)) code = KeyCode.valueOf("DIGIT" + c);
                else
                    code = switch (c) {
                        case '.' -> KeyCode.PERIOD;
                        case '-', '_' -> KeyCode.MINUS;
                        case '/' -> KeyCode.SLASH;
                        case '\\' -> KeyCode.BACK_SLASH;
                        case ':' -> KeyCode.SEMICOLON;
                        case '`', '~' -> KeyCode.BACK_QUOTE;
                        case ' ' -> KeyCode.SPACE;
                        case '=', '+' -> KeyCode.EQUALS;
                        case '$' -> KeyCode.DIGIT4;
                        case '"' -> KeyCode.QUOTE;
                        case '(' -> KeyCode.DIGIT9;
                        case ')' -> KeyCode.DIGIT0;
                        case '*' -> KeyCode.DIGIT8;
                        case '^' -> KeyCode.DIGIT6;
                        case ',' -> KeyCode.COMMA;
                        default -> throw new IllegalArgumentException("Unsupported test character " + c);
                    };
                if (shift) robot.keyPress(KeyCode.SHIFT);
                robot.keyType(code);
                if (shift) robot.keyRelease(KeyCode.SHIFT);
                return null;
            });
            entered.append(c);
            // Some layouts compose these keys with the next character. Verify the combined
            // prefix after that keystroke; the final field value is always checked exactly.
            if ("~`^".indexOf(c) >= 0) {
                Thread.sleep(30);
                continue;
            }
            String expected = entered.toString();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!expected.equals(fx(target::getText)) && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(expected, fx(target::getText), "Delivered keyboard input: " + id);
        }
        Thread.sleep(120);
        assertEquals(value, fx(() -> ((TextField) node("#" + id)).getText()), "Robot text entry: " + id);
        if (Set.of("file-path", "import-path", "export-path").contains(id)) assertPathRowLabels(id);
    }

    private void assertPathRowLabels(String fieldId) throws Exception {
        fx(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            var field = (TextField) node("#" + fieldId);
            var browse = (Button) node("#file-browse");
            var row = browse.getParent();
            // Use control layout bounds rather than shadows for the overlap check.
            var fieldBounds = field.localToParent(field.getLayoutBounds());
            var buttonBounds = browse.localToParent(browse.getLayoutBounds());
            assertTrue(browse.getWidth() > 0);
            assertTrue(fieldBounds.getMaxX() <= buttonBounds.getMinX(), "Path field must not overlap Browse");
            Node panel = row;
            while (!panel.getStyleClass().contains("dialog-overlay")) panel = panel.getParent();
            for (var control : panel.lookupAll(".button")) {
                // Formula skin help may scroll out of view; check translated dialog actions.
                if (control instanceof Button button
                        && !button.getText().isEmpty()
                        && !button.getStyleClass().contains("formula-help")) {
                    var rendered = assertInstanceOf(javafx.scene.text.Text.class, button.lookup(".text"));
                    assertEquals(
                            button.getText(),
                            rendered.getText(),
                            "The full translated button label must be displayed: " + button.getId());
                    var textBounds = rendered.localToScene(rendered.getLayoutBounds());
                    assertTrue(
                            panel.localToScene(panel.getLayoutBounds()).contains(textBounds),
                            "Button label must stay visible inside the dialog");
                    for (var parent = button.getParent(); parent != panel; parent = parent.getParent()) {
                        if (parent instanceof ScrollPane scroll) {
                            var viewport = scroll.lookup(".viewport");
                            assertTrue(
                                    viewport.localToScene(viewport.getLayoutBounds())
                                            .contains(textBounds),
                                    "Button label must not be clipped by the scroll viewport");
                        }
                    }
                }
            }
            Screenshot.save(
                    stage.getScene(),
                    Path.of(
                            System.getProperty("voxelbrew.ui.artifacts"),
                            "dialog-"
                                    + fieldId
                                    + "-"
                                    + (browse.getText().startsWith("Browse") ? "en" : "pl")
                                    + ".png"));
            return null;
        });
    }

    private void standardView(int index) throws Exception {
        click("menu-view");
        click("view-"
                + List.of("isometric", "top", "front", "right", "back", "left", "bottom")
                        .get(index));
    }

    private void chooseExporter(String id) throws Exception {
        int index = fx(() -> {
            var choices = ((ComboBox<?>) node("#export-format")).getItems();
            for (int i = 0; i < choices.size(); i++) {
                if (choices.get(i) instanceof dev.voxelbrew.exporter.api.ExporterDescriptor choice
                        && choice.id().equals(id)) return i;
            }
            throw new AssertionError("Missing exporter " + id);
        });
        choice("export-format", index);
        click("export-confirm");
    }

    private void assertDesignRejectsMeshExport(String diagnostic) throws Exception {
        var before = document();
        assertFalse(fx(() -> workbench.controller().canExport()));
        Path target = directory.resolve("invalid-design.stl");
        click("export");
        chooseExporter("stl");
        text("export-path", target.toString());
        click("export-confirm");
        ready();
        assertFalse(Files.exists(target), "Broken geometry must not produce an export");
        String error = fx(() -> ((Label) node("#status")).getText());
        assertTrue(error.contains(diagnostic), error);
        assertEquals(before, document());
    }

    private void choice(String id, int index) throws Exception {
        if (id.equals("appearance")) {
            click("menu-view");
            // A submenu opens on hover. Clicking it after the hover delay can close it again.
            fx(() -> {
                var item = node("#menu-appearance");
                var bounds = item.localToScreen(item.getBoundsInLocal());
                robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
                return null;
            });
            long submenuDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!fx(() -> ((Menu) command(workbench, "menu-appearance")).isShowing())
                    && System.nanoTime() < submenuDeadline) Thread.sleep(25);
            assertTrue(
                    fx(() -> ((Menu) command(workbench, "menu-appearance")).isShowing()),
                    "Appearance submenu opens on native hover");
            click("appearance-" + List.of("system", "light", "dark").get(index));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (fx(() -> ((MenuBar) workbench.getScene().lookup("#main-menu"))
                            .getMenus().stream().anyMatch(Menu::isShowing))
                    && System.nanoTime() < deadline) Thread.sleep(25);
            assertFalse(
                    fx(() -> ((MenuBar) workbench.getScene().lookup("#main-menu"))
                            .getMenus().stream().anyMatch(Menu::isShowing)),
                    "Appearance menus closed after selection");
            assertTrue(fx(() -> ((RadioMenuItem) command(
                            workbench,
                            "appearance-" + List.of("system", "light", "dark").get(index)))
                    .isSelected()));
            return;
        }
        click(id);
        // The native popup can appear after a later pulse when switching workspace tabs.
        // Wait for the observed popup; do not replay the click or set its selection in code.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fx(() -> ((ComboBox<?>) node("#" + id)).isShowing()) && System.nanoTime() < deadline) Thread.sleep(25);
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
            if (fx(() -> !workbench.controller().rebuilding()
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
    void aboutDetailsAndUpdateLinkInBothPalettes() throws Exception {
        assertEquals(0, updates.requests.get(), "Startup must not contact the release service");
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            click("about");
            waitForAbout("New version available: 1.1.0");
            assertEquals(
                    "Version " + dev.voxelbrew.util.Version.version(),
                    fx(() -> ((Label) node("#about-version")).getText()));
            fx(() -> {
                for (String id :
                        List.of("about-check", "about-updates", "about-license", "about-notices", "about-close")) {
                    var control = (Control) node("#" + id);
                    assertNotNull(control.getTooltip(), id);
                    assertFalse(control.getAccessibleHelp().isBlank(), id);
                }
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"), "about-" + palette + ".png"));
                return null;
            });
            click("about-license");
            assertTrue(fx(() ->
                    ((TextArea) node("#about-legal-text")).getText().contains("GNU AFFERO GENERAL PUBLIC LICENSE")));
            assertFalse(fx(() -> ((TextArea) node("#about-legal-text")).isEditable()));
            click("about-legal-close");
            click("about-notices");
            assertTrue(fx(() -> ((TextArea) node("#about-legal-text")).getText().contains("Open CASCADE")));
            click("about-legal-close");
            click("about-updates");
            assertNotNull(fx(() -> node("#update-check")));
            click("update-cancel");
            ready();
        }
    }

    @Test
    void aboutCheckFailureRetryAndCancellation() throws Exception {
        updates.slow = true;
        click("about");
        waitForAbout("Checking for updates");
        // Synchronize with the server: initial HTTP-client startup can exceed five seconds on CI.
        assertTrue(
                updates.requestReceived.await(30, TimeUnit.SECONDS), "Cancel a lookup already waiting for a response");
        assertTrue(fx(() -> node("#about-check").isDisabled()));
        click("about-close");
        ready();
        updates.failureStatus = 503;
        updates.slow = false;
        click("about");
        waitForAbout("Could not check for updates");
        assertFalse(fx(() -> node("#about-check").isDisabled()));
        updates.failureStatus = 0;
        updates.current = true;
        click("about-check");
        waitForAbout("You are up to date. Latest published version: 1.0.0");
        updates.current = false;
        updates.development = true;
        click("about-check");
        waitForAbout("Development runs are not replaced");
        assertTrue(fx(() -> ((Label) node("#about-status")).getText().startsWith("New version available: 1.1.0")));
        click("about-close");
        ready();
    }

    private void waitForAbout(String message) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!fx(() -> ((Label) node("#about-status")).getText().contains(message)) && System.nanoTime() < deadline)
            Thread.sleep(50);
        assertTrue(fx(() -> ((Label) node("#about-status")).getText().contains(message)), message);
    }

    @Test
    void updatesCheckCancelAndProtectUnsavedDocument() throws Exception {
        for (int palette : List.of(1, 2)) {
            choice("appearance", palette);
            updates.slow = true;
            click("updates");
            click("update-check");
            int request = updates.requests.get();
            click("update-cancel");
            updates.slow = false;
            click("updates");
            text("update-version", "1.1.0");
            click("update-force");
            click("update-check");
            waitForUpdate("Update available");
            assertTrue(updates.requests.get() > request);
            assertEquals(dev.voxelbrew.util.update.ReleaseVersion.release("1.1.0"), updates.requested);
            fx(() -> {
                Screenshot.save(
                        stage.getScene(),
                        Path.of(System.getProperty("voxelbrew.ui.artifacts"), "updates-" + palette + ".png"));
                return null;
            });
            click("update-cancel");
        }
        fx(() -> {
            workbench
                    .controller()
                    .add(new Feature.Sketch(
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
                exists = files.anyMatch(path -> path.getFileName().toString().startsWith(".VoxelBrew-update-"));
            }
            if (exists) Thread.sleep(50);
        } while (exists && System.nanoTime() < deadline);
        assertFalse(exists);
    }

    @Test
    void nativeAndJsonImportsOpenUnsavedEditableDesignsAndCancelWithoutChanges() throws Exception {
        Path original = PreparedDocuments.copy("plate-5mm.vxb", directory);
        var expected = DocumentReader.load(original);
        Path json = directory.resolve("editable plate.json");
        Files.writeString(json, dev.voxelbrew.engine.persistence.DocumentCodec.encodeJson(expected));
        ready();
        for (var source : List.of(original, json)) {
            var before = document();
            click("import");
            choice("import-format", source.equals(json) ? 1 : 3);
            click("import-confirm");
            key(KeyCode.ESCAPE);
            ready();
            assertEquals(before, document());
            assertFalse(fx(() -> workbench.controller().dirty()));
            click("import");
            choice("import-format", source.equals(json) ? 1 : 3);
            click("import-confirm");
            text("import-path", source.toString());
            click("import-confirm");
            ready();
            assertEquals(expected.features(), document().features());
            assertTrue(fx(() -> workbench.controller().dirty()));
            assertNull(fx(() -> workbench.controller().path()));
            assertEquals(12000, volume(), .01);
            save(directory.resolve(source.equals(json) ? "json-copy.vxb" : "native-copy.vxb"));
        }
        assertEquals(expected.features(), DocumentReader.load(original).features());
        assertEquals(dev.voxelbrew.engine.persistence.DocumentCodec.encodeJson(expected), Files.readString(json));
    }

    @Test
    void externalImportDialogUsesOptionsAndProtectsSourceInBothPalettes() throws Exception {
        Path source = directory.resolve("external plate.vbplate");
        Files.writeString(source, "60,40,4");
        ready();
        choice("appearance", 1);
        shortcut(KeyCode.I, false);
        choice("import-format", 2); // failing, json, plate, vxb; toolpaths are excluded.
        click("import-confirm");
        text("import-path", source.toString());
        text("import-option-scale", "2");
        assertButtonDescriptions();
        click("import-confirm");
        ready();
        assertEquals("imported-plate", document().id());
        assertTrue(fx(() -> workbench.controller().dirty()));
        assertNull(fx(() -> workbench.controller().path()));
        assertEquals(
                76800,
                fx(() -> workbench.controller().build().bodies().getFirst().volume()),
                .01);
        Path saved = directory.resolve("imported design.vxb");
        save(saved);
        assertFalse(fx(() -> workbench.controller().dirty()));
        var before = document();
        choice("appearance", 2);
        click("import");
        click("import-confirm");
        assertButtonDescriptions();
        text("import-path", source.toString());
        click("import-confirm"); // First provider deliberately fails.
        ready();
        assertEquals(before, document());
        assertTrue(fx(() -> workbench.controller().error().contains("Deliberate importer failure")));
        assertEquals("60,40,4", Files.readString(source));
        assertEquals(before, DocumentReader.load(saved));
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
        return fx(() -> workbench.controller().build().bodies().stream()
                .mapToDouble(DocumentController.Body::volume)
                .sum());
    }

    private void modelClick(double x, double y, double z) throws Exception {
        fx(() -> {
            var point = workbench.viewport().modelToScreen(new Point3D(x, y, z));
            assertNotNull(point);
            robot.mouseMove(point);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
    }

    private void openPrepared(String name) throws Exception {
        Path copy = PreparedDocuments.copy(name, directory);
        open(copy);
        click("history-0");
        standardView(1);
        click("menu-fit");
    }

    private void rectangle(double offset) throws Exception {
        rectangleDraft(60, 40, 0);
        click("constraint-finish");
        ready();
        if (offset != 0) {
            text("dimension-offsetZ", Double.toString(offset));
            key(KeyCode.ENTER);
            ready();
        }
        standardView(1);
        click("menu-fit");
    }

    private void rectangleDraft(double width, double height, double offset) throws Exception {
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertEquals(0, offset);
        assertNull(fx(() -> stage.getScene().lookup("#constraint-plane")));
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(width, height);
        assertEquals(4, sketchGeometry().entities().size());
    }

    private void circle() throws Exception {
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.CIRCLE_CENTER);
        sketchNumericPoint(10, 10);
        sketchNumericPoint(12, 10);
        click("constraint-finish");
        ready();
    }

    private void openLegacyRectangle() throws Exception {
        // Existing primitive sketches remain editable and convertible to constraints.
        // New profiles are always created through the sketch editor in the other workflows.
        var session = new ModelSession(CadDocument.empty());
        session.add(new Feature.Sketch(
                "legacy", "legacy.profile", new Feature.Plane("origin-xy", 0), new Feature.Rectangle(0, 0, 60, 40)));
        Path file = directory.resolve("existing rectangle.vxb");
        DocumentWriter.save(session.document(), file, false);
        open(file);
        click("history-0");
    }

    private void save(Path path) throws Exception {
        click("save-as");
        text("file-path", path.toString());
        click("file-confirm");
        ready();
        assertTrue(Files.exists(path));
    }

    private void sketchReady() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> ((SketchEditor) node("#sketch-editor")).controller().busy()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertFalse(
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().busy()));
        Thread.sleep(100);
    }

    private void sketchClick(double x, double y, boolean shift) throws Exception {
        fx(() -> {
            var point = ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(x, y));
            assertNotNull(point);
            robot.mouseMove(point);
            if (shift) robot.keyPress(KeyCode.SHIFT);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
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
        click("sketch-create-type");
        fx(() -> {
            ((ListView<?>) node("#sketch-type-results")).getSelectionModel().select(tool.ordinal());
            return null;
        });
        click("sketch-type-confirm");
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
        var position = fx(() -> {
            var tabs = (TabPane) node("#parameters-tabs");
            var headers = tabs.lookupAll(".tab").stream()
                    .filter(Node::isVisible)
                    .sorted(java.util.Comparator.comparingDouble(
                            n -> n.localToScreen(n.getBoundsInLocal()).getMinX()))
                    .toList();
            return headers.get(index).localToScreen(headers.get(index).getBoundsInLocal());
        });
        fx(() -> {
            robot.mouseMove(position.getCenterX(), position.getCenterY());
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(200);
        assertEquals(
                index,
                fx(() ->
                        ((TabPane) node("#parameters-tabs")).getSelectionModel().getSelectedIndex()));
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
    void parameterCompletionFiltersAtCaretAndInsertsWithoutCommittingAcrossEditors() throws Exception {
        choice("appearance", 1);
        click("parameters");
        parameter("fooWidth", "60");
        parameter("fooHeight", "40");
        parameter("bar", "10");
        click("parameters-apply");
        ready();
        var original = document();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$");
        assertEquals(List.of("bar", "fooHeight", "fooWidth"), completionNames());
        key(KeyCode.F);
        key(KeyCode.O);
        key(KeyCode.O);
        assertEquals("=$foo", fx(() -> ((TextField) node("#sketch-point-x")).getText()));
        assertEquals(List.of("fooHeight", "fooWidth"), completionNames());
        completionScreenshot("parameter-completion-light.png");
        key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        assertNoCompletion();
        assertEquals("=$fooWidth", fx(() -> ((TextField) node("#sketch-point-x")).getText()));
        assertEquals(original, document(), "Accepting a suggestion creates no feature or history entry");
        shortcut(KeyCode.Z, false);
        assertEquals("=$foo", fx(() -> ((TextField) node("#sketch-point-x")).getText()));
        assertNoCompletion(); // Text undo selects the replaced reference; resume at its end.
        key(KeyCode.RIGHT);
        assertEquals(List.of("fooHeight", "fooWidth"), completionNames());
        shortcut(KeyCode.Y, false);
        assertEquals("=$fooWidth", fx(() -> ((TextField) node("#sketch-point-x")).getText()));
        completionNames();
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertNotNull(fx(() -> node("#sketch-point-x")));
        text("sketch-point-x", "=$fooH");
        assertEquals(List.of("fooHeight"), completionNames());
        key(KeyCode.TAB);
        assertEquals("=$fooHeight", fx(() -> ((TextField) node("#sketch-point-x")).getText()));
        assertTrue(fx(() -> node("#sketch-point-x").isFocused()));
        assertEquals(original, document());
        sketchNumericPoint(0, 0);
        sketchNumericPoint(60, 40);
        click("constraint-finish");
        ready();
        assertEquals(1, document().features().size());
        var part = document();

        text("dimension-offsetZ", "=$fooWidth/2+1");
        key(KeyCode.HOME);
        for (int i = 0; i < 5; i++) key(KeyCode.RIGHT);
        assertEquals(List.of("fooHeight", "fooWidth"), completionNames());
        click("formula-suggestion-fooHeight");
        assertNoCompletion();
        assertEquals("=$fooHeight/2+1", fx(() -> ((TextField) node("#dimension-offsetZ")).getText()));
        assertEquals(part, document(), "Mouse completion also preserves uncommitted editing");
        text("dimension-offsetZ", "=$missing");
        assertEquals(List.of(), completionNames());
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertEquals("=$missing", fx(() -> ((TextField) node("#dimension-offsetZ")).getText()));
        click("history-0");

        choice("appearance", 2);
        click("parameters");
        click("parameter-new");
        text("parameter-comment", "=\"Width: \" + $fooW");
        assertEquals(List.of("fooWidth"), completionNames());
        completionScreenshot("parameter-completion-dark.png");
        key(KeyCode.ENTER);
        assertNoCompletion();
        assertEquals("=\"Width: \" + $fooWidth", fx(() -> ((TextField) node("#parameter-comment")).getText()));
        assertEquals("Width: 60", fx(() -> ((FormulaField) node("#parameter-comment")).evaluatedText()));
        assertNotNull(fx(() -> node("#parameters-apply")), "Completing text does not apply or close a dialog");
        text("parameter-comment", "=\"literal $foo\"");
        assertNoCompletion();
        parameter("fooLength", "5");
        text("parameter-comment", "=$foo");
        assertEquals(List.of("fooHeight", "fooLength", "fooWidth"), completionNames());
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertNotNull(fx(() -> node("#parameters-cancel")), "Escape does not cancel the parameter draft");
        click("parameters-cancel");
        assertEquals(part, document());

        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$foo");
        assertEquals(List.of("fooHeight", "fooWidth"), completionNames());
        key(KeyCode.ESCAPE);
        assertNoCompletion();
        assertEquals("=$foo", fx(() -> ((TextField) node("#sketch-point-x")).getText()));
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        text("sketch-point-x", "=$fooW");
        completionNames();
        click("constraint-cancel");
        assertNoCompletion();
        assertEquals(part, document(), "Closing a sketch hides completion and discards the draft");
    }

    private List<String> completionNames() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!fx(this::completionShowing) && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(fx(this::completionShowing), "Parameter suggestions are visible");
        return fx(() -> ((ListView<?>) node("#formula-suggestions"))
                .getItems().stream().map(Object::toString).toList());
    }

    private boolean completionShowing() {
        return Window.getWindows().stream()
                .anyMatch(window -> window instanceof PopupWindow
                        && window.isShowing()
                        && window.getScene().lookup("#formula-suggestions") != null);
    }

    private void assertNoCompletion() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (fx(this::completionShowing) && System.nanoTime() < deadline) Thread.sleep(20);
        assertFalse(fx(this::completionShowing), "Suggestions must close without another key or click");
    }

    private void completionScreenshot(String name) throws Exception {
        fx(() -> {
            Screenshot.save(
                    node("#formula-suggestions").getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), name));
            return null;
        });
    }

    @Test
    void formulaFieldsPreserveNumbersTextParametersHistoryAndFiles() throws Exception {
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        choice("appearance", 1);
        click("parameters");
        parameter("width", "=60");
        click("parameters-apply");
        ready();
        var before = document();
        rectangleDraft(30, 40, 0);
        sketchReference(sketchGeometry().entities().getFirst().id());
        text("sketch-dimension-value", "=$width/2");
        fx(() -> {
            var field = (FormulaField) node("#sketch-dimension-value");
            assertEquals(30, field.value());
            assertTrue(field.getPseudoClassStates().contains(javafx.css.PseudoClass.getPseudoClass("formula")));
            assertEquals(FormulaField.ExpressionType.NUMBER, field.expressionType());
            assertEquals("ƒx", ((Label) field.lookup(".formula-mark")).getText());
            assertTrue(field.getTooltip().getText().contains("30"));
            var centimeters = FormulaField.number(
                    "=$width/2",
                    () -> workbench.controller().snapshot().document().parameters(),
                    ParameterUnit.CM);
            assertEquals(30, centimeters.value());
            assertEquals(3, centimeters.declaredValue());
            Screenshot.save(stage.getScene(), artifacts.resolve("formulas-light.png"));
            return null;
        });
        sketchDimension(0, "=$width/2");
        click("sketch-clear-selection");
        sketchReference(sketchGeometry().entities().get(1).id());
        text("sketch-dimension-value", "=1/0");
        click("sketch-apply-dimension");
        assertEquals(before, document());
        assertTrue(fx(() -> node("#sketch-dimension-value")
                .getPseudoClassStates()
                .contains(javafx.css.PseudoClass.getPseudoClass("formula-invalid"))));
        sketchDimension(0, "=20+20");
        click("constraint-finish");
        ready();
        assertEquals(2, document().parameters().bindings().size());
        click("extrude");
        text("extrude-distance", "=1+2/3");
        click("commit-extrusion");
        ready();
        assertEquals(2000, volume(), .01);
        click("undo");
        ready();
        assertEquals(1, document().features().size());
        click("redo");
        ready();
        choice("appearance", 2);
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        sketchType(SketchTools.Tool.TEXT);
        text("sketch-text-content", "=\"Width: \" + 5");
        text("sketch-text-height", "=$width/12");
        fx(() -> {
            assertEquals("Width: 5", ((FormulaField) node("#sketch-text-content")).evaluatedText());
            var stringField = (FormulaField) node("#sketch-text-content");
            assertEquals(FormulaField.ExpressionType.STRING, stringField.expressionType());
            assertTrue(stringField.getStyleClass().contains("string-expression"));
            assertEquals("ƒs", ((Label) stringField.lookup(".formula-mark")).getText());
            Screenshot.save(stage.getScene(), artifacts.resolve("formulas-dark.png"));
            return null;
        });
        sketchNumericPoint(0, 50);
        var textEntity = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.TEXT)
                .findFirst()
                .orElseThrow();
        assertEquals("Width: 5", textEntity.text().content());
        assertEquals(5, textEntity.value(2), .00001);
        sketchReference(textEntity.id());
        click("sketch-edit-text");
        assertEquals("=\"Width: \" + 5", fx(() -> ((TextField) node("#sketch-edit-text-content")).getText()));
        text("sketch-edit-text-content", "=\"Width: \" + $missing");
        assertEquals(
                javafx.scene.paint.Color.web("#d65c46"),
                fx(() -> ((TextField) node("#sketch-edit-text-content"))
                        .getBorder()
                        .getStrokes()
                        .getFirst()
                        .getTopStroke()));
        key(KeyCode.ENTER);
        assertEquals("Width: 5", sketchGeometry().entity(textEntity.id()).text().content());
        text("sketch-edit-text-content", "=\"Width: \" + $width");
        key(KeyCode.ENTER); // Accept the parameter suggestion first; the next Enter applies.
        key(KeyCode.ENTER);
        sketchReady();
        assertEquals(
                "Width: 60", sketchGeometry().entity(textEntity.id()).text().content());
        click("sketch-undo");
        sketchReady();
        assertEquals("Width: 5", sketchGeometry().entity(textEntity.id()).text().content());
        click("sketch-redo");
        sketchReady();
        click("constraint-finish");
        ready();
        click("parameters");
        parameter("width", "=80");
        click("parameters-apply");
        ready();
        assertEquals(8000.0 / 3, volume(), .01);
        assertEquals(
                5, // Four rectangle edges and one text entity are rendered as sketch curves.
                fx(() -> ((SubScene) workbench.viewport().getChildren().getFirst())
                        .getRoot()
                        .lookupAll(".sketch-curves")
                        .size()));
        var file = directory.resolve("formula fields.vxb");
        save(file);
        var saved = document();
        open(file);
        assertEquals(saved, document());
        assertTrue(DocumentReader.load(file).parameters().bindings().stream()
                .anyMatch(b -> b.expression().equals("=\"Width: \" + $width")));
        click("history-2");
        click("edit-sketch-constraints");
        sketchReady();
        assertEquals(
                "Width: 80", sketchGeometry().entity(textEntity.id()).text().content());
        assertEquals(80.0 / 12, sketchGeometry().entity(textEntity.id()).value(2), .00001);
        sketchReference(textEntity.id());
        click("sketch-edit-text");
        text("sketch-edit-text-content", "=\"Cancelled\"");
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
        ready();
        assertEquals(saved, document());
        Files.copy(file, artifacts.resolve("formula-fields.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("formula-cli-inspection.txt"), cli("inspect", file.toString()));
    }

    @Test
    void namedSketchParametersAnd3dConfigurationsDriveOneDesign() throws Exception {
        choice("appearance", 2);
        click("constrained-sketch");
        click("choose-plane-xy");
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
                fx(() -> ((SketchEditor) node("#sketch-editor"))
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
        String body =
                fx(() -> workbench.controller().build().bodies().getFirst().id());
        var featureIds = fx(() -> workbench.controller().snapshot().document().features().stream()
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
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("parameter-configurations-dark.png"));
            return null;
        });
        click("parameters-apply");
        ready();
        assertEquals(25600, volume(), .02);
        assertEquals(
                body,
                fx(() -> workbench.controller().build().bodies().getFirst().id()));
        click("undo");
        ready();
        assertEquals(12000, volume(), .02);
        click("redo");
        ready();
        assertEquals(25600, volume(), .02);
        assertEquals(
                featureIds,
                fx(() -> workbench.controller().snapshot().document().features().stream()
                        .map(Feature::id)
                        .toList()));
        Path file = directory.resolve("parameter versions.vxb");
        save(file);
        open(file);
        ready();
        assertEquals(
                "Large",
                fx(() -> workbench
                        .controller()
                        .snapshot()
                        .document()
                        .parameters()
                        .activeName()));
        choice("active-configuration", 0);
        ready();
        assertEquals(12000, volume(), .02);
        choice("active-configuration", 1);
        ready();
        assertEquals(25600, volume(), .02);
        click("save");
        ready();
        String inspection = cli("inspect", file.toString());
        var measured = java.util.regex.Pattern.compile("volume=([0-9.eE+\\-]+)").matcher(inspection);
        assertTrue(measured.find(), inspection);
        assertEquals(25600, Double.parseDouble(measured.group(1)), .02, inspection);
        Files.copy(file, artifacts.resolve("gui-parameter-variants.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("parameter-cli-inspection.txt"), inspection);
    }

    @Test
    void parameterRepairRenameAndSketchCancellationUseRealControls() throws Exception {
        choice("appearance", 1);
        openLegacyRectangle();
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
        assertTrue(fx(() -> ((Label) node("#parameters-error")).getText().contains("Unknown parameter")));
        text("parameter-expression", "60");
        choice("parameter-unit", 5); // reject an angle parameter bound to a length
        click("parameter-save");
        assertTrue(fx(() -> ((Label) node("#parameters-error"))
                .getText()
                .toLowerCase(java.util.Locale.ROOT)
                .contains("incompatible")));
        click("parameters-cancel");
        assertEquals(before, fx(() -> workbench.controller().snapshot()));
        click("parameters");
        tableRow("parameter-table", 0);
        text("parameter-name", "plateWidth");
        click("parameter-save");
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "parameters-light.png"));
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
                fx(() -> workbench
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
        assertDesignRejectsMeshExport("Rebuild failed");
        choice("active-configuration", 0);
        ready();
        assertEquals(12000, volume(), .01);
        assertTrue(fx(() -> workbench.controller().canExport()));
    }

    @Test
    void ellipseSlotsAndConicsUseRealSketchTools() throws Exception {
        choice("appearance", 1);
        click("constrained-sketch");
        click("choose-plane-xy");
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
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-types-ellipse-slot-conic.png"));
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
        var file = directory.resolve("ellipse slot conic.vxb");
        save(file);
        open(file);
        ready();
        assertEquals(3, fx(() -> workbench.controller().build().bodies().size()));
    }

    @Test
    void fitControlSplinesAndTextUseRealSketchTools() throws Exception {
        choice("appearance", 2);
        click("constrained-sketch");
        click("choose-plane-xy");
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
        var control = sketchGeometry().entities().stream()
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
        String textId = sketchGeometry().entities().stream()
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
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-types-splines-text-dark.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        assertEquals(4, fx(() -> workbench.controller().build().bodies().size()));
        var file = directory.resolve("splines and editable text.vxb");
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
        click("choose-plane-xy");
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
        var circle = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.CIRCLE)
                .findFirst()
                .orElseThrow();
        assertEquals(0, circle.point(SketchGeometry.Part.CENTER).distance(new SketchGeometry.Vec(5, 5)), .05);
        click("sketch-construction");
        click("sketch-tool-arc");
        sketchClick(30, 20, false);
        sketchClick(50, 20, false);
        sketchClick(30, 40, false);
        sketchReady();
        click("sketch-construction");
        String arc = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.ARC)
                .findFirst()
                .orElseThrow()
                .id();
        sketchReference(arc);
        sketchType(SketchTools.Tool.TEXT_ON_PATH);
        text("sketch-text-content", "CAD");
        text("sketch-text-height", "4");
        sketchNumericPoint(50, 20);
        var text = sketchGeometry().entities().stream()
                .filter(e -> e.kind() == SketchGeometry.Kind.TEXT)
                .findFirst()
                .orElseThrow();
        assertEquals(arc, text.text().pathId());
        var before = sketchGeometry().curves(text).getFirst().at(0);
        sketchDrag(30, 20, 35, 20, false);
        assertTrue(before.distance(sketchGeometry().curves(text).getFirst().at(0)) > 4);
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-types-tangent-and-path-text.png"));
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
        click("choose-plane-xy");
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
        click("choose-plane-xy");
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
        assertEquals("Fully constrained", fx(() -> ((Label) node("#sketch-solve-status")).getText()));
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
        // Dimension annotations can overlap the origin; choose the point references explicitly.
        click("sketch-clear-selection");
        var circleId = sketchGeometry().entities().stream()
                .filter(entity -> entity.kind() == SketchGeometry.Kind.CIRCLE)
                .findFirst()
                .orElseThrow()
                .id();
        sketchReference(circleId + ".center");
        sketchReference("origin.point");
        sketchDimension(2, "-10");
        sketchDimension(3, "-10");
        assertEquals("Fully constrained", fx(() -> ((Label) node("#sketch-solve-status")).getText()));
        assertEquals(0, document().features().size(), "Sketch editing stays transient until Finish");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-constraints-light.png"));
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
        Path file = directory.resolve("GUI constrained plate.vxb");
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
        int widthIndex = fx(() -> {
            var values = ((SketchEditor) node("#sketch-editor"))
                    .controller()
                    .definition()
                    .constraints();
            for (int i = 0; i < values.size(); i++)
                if (values.get(i).type() == SketchGeometry.Type.LENGTH
                        && values.get(i).refs().getFirst().entityId().endsWith("bottom")) return i;
            throw new AssertionError("Missing width dimension");
        });
        click("sketch-constraints");
        key(KeyCode.HOME);
        for (int i = 0; i < widthIndex; i++) key(KeyCode.DOWN);
        text("sketch-dimension-value", "80");
        click("sketch-apply-dimension");
        sketchReady();
        assertEquals("Fully constrained", fx(() -> ((Label) node("#sketch-solve-status")).getText()));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-constraints-dark.png"));
            return null;
        });
        click("constraint-finish");
        ready();
        assertEquals((80 * 40 - 4 * Math.PI) * 8, volume(), 0.01);
        assertEquals(
                saved.features().getFirst().id(),
                document().features().getFirst().id());
        click("undo");
        ready();
        assertEquals(saved, document());
        click("redo");
        ready();
        assertEquals((80 * 40 - 4 * Math.PI) * 8, volume(), 0.01);
        var beforeCancel = fx(() -> workbench.controller().snapshot());
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-line");
        sketchClick(10, 10, false);
        key(KeyCode.ESCAPE);
        assertTrue(fx(() -> ((SketchEditor) node("#sketch-editor"))
                .controller()
                .definition()
                .entities()
                .isEmpty()));
        click("constraint-cancel");
        assertEquals(beforeCancel, fx(() -> workbench.controller().snapshot()));
        Files.copy(file, artifacts.resolve("gui-constrained-plate.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(artifacts.resolve("constrained-cli-inspection.txt"), inspection);
    }

    private void sketchReference(String ref) throws Exception {
        int index = fx(() -> {
            var items = ((ComboBox<?>) node("#sketch-reference")).getItems();
            for (int i = 0; i < items.size(); i++) if (items.get(i).toString().equals(ref)) return i;
            throw new AssertionError("Missing sketch reference " + ref);
        });
        choice("sketch-reference", index);
        click("sketch-select-ref");
    }

    @Test
    void sketchDirectionalSelectionAndControlClicksUseRealGestures() throws Exception {
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        fx(() -> {
            var editor = (SketchEditor) node("#sketch-editor");
            editor.controller()
                    .edit(new SketchGeometry(
                            List.of(
                                    Sketches.entity("inside", SketchGeometry.Kind.LINE, false, 20, 15, 30, 25),
                                    Sketches.entity("crossing", SketchGeometry.Kind.LINE, false, 5, 20, 50, 20)),
                            List.of(),
                            List.of("inside", "crossing")));
            return null;
        });
        sketchReady();
        click("sketch-tool-select");
        var emptySelection = fx(() -> ((Label) node("#sketch-selection")).getText());
        sketchDrag(15, 30, 25, 10, false);
        assertEquals(emptySelection, fx(() -> ((Label) node("#sketch-selection")).getText()));
        sketchDrag(25, 10, 15, 30, false);
        assertEquals("inside\ncrossing", fx(() -> ((Label) node("#sketch-selection")).getText()));
        sketchDrag(15, 30, 40, 10, false);
        assertEquals("inside", fx(() -> ((Label) node("#sketch-selection")).getText()));
        sketchDrag(40, 10, 15, 30, false);
        assertEquals("inside\ncrossing", fx(() -> ((Label) node("#sketch-selection")).getText()));
        fx(() -> {
            robot.keyPress(KeyCode.CONTROL);
            return null;
        });
        try {
            sketchClick(23, 18, false);
        } finally {
            fx(() -> {
                robot.keyRelease(KeyCode.CONTROL);
                return null;
            });
        }
        assertEquals("crossing", fx(() -> ((Label) node("#sketch-selection")).getText()));
        click("sketch-tool-line");
        sketchClick(55, 35, false);
        sketchDrag(15, 30, 40, 10, false);
        assertEquals("inside", fx(() -> ((Label) node("#sketch-selection")).getText()));
        assertEquals(
                2,
                fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .definition()
                        .entities()
                        .size()));
        click("constraint-cancel");
    }

    private void sketchDrag(double x, double y, double toX, double toY, boolean cancel) throws Exception {
        var positions = fx(() -> {
            var editor = (SketchEditor) node("#sketch-editor");
            return new javafx.geometry.Point2D[] {
                editor.modelToScreen(new SketchGeometry.Vec(x, y)),
                editor.modelToScreen(new SketchGeometry.Vec(toX, toY))
            };
        });
        fx(() -> {
            robot.mouseMove(positions[0]);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseMove(positions[1]);
            return null;
        });
        Thread.sleep(180);
        if (cancel) key(KeyCode.ESCAPE);
        var released = new CountDownLatch(1);
        javafx.event.EventHandler<MouseEvent> releaseObserved = event -> {
            if (event.getButton() == MouseButton.PRIMARY) released.countDown();
        };
        var canvas = fx(() -> node("#sketch-canvas"));
        try {
            fx(() -> {
                canvas.addEventFilter(MouseEvent.MOUSE_RELEASED, releaseObserved);
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
            assertTrue(released.await(10, TimeUnit.SECONDS), "Native sketch mouse release was not delivered");
        } finally {
            // This FX dispatch also waits until the release handler has submitted its solve.
            fx(() -> {
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
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-line");
        sketchClick(10, 10, false);
        sketchClick(30, 10, false);
        sketchReady();
        var before =
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        sketchDrag(30, 10, 35, 15, true);
        assertEquals(
                before,
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition()));
        sketchDrag(30, 10, 35, 15, false);
        var moved =
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertEquals(35, moved.entities().getFirst().at(1).x(), 0.3);
        assertEquals(15, moved.entities().getFirst().at(1).y(), 0.3);
        assertEquals(
                moved.entities().getFirst().at(0).y(),
                moved.entities().getFirst().at(1).y(),
                1e-6);
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
        click("choose-plane-xy");
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
        String arcId = fx(() -> ((SketchEditor) node("#sketch-editor"))
                .controller().definition().entities().stream()
                        .filter(e -> e.kind() == SketchGeometry.Kind.ARC)
                        .findFirst()
                        .orElseThrow()
                        .id());
        String splineId = fx(() -> ((SketchEditor) node("#sketch-editor"))
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
        var geometry =
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertTrue(
                fx(() -> ((SketchEditor) node("#sketch-editor")).controller().canFinish()),
                fx(() -> ((Label) node("#sketch-error")).getText()));
        assertTrue(geometry.constraints().stream().anyMatch(c -> c.type() == SketchGeometry.Type.CURVATURE));
        assertEquals(
                0,
                geometry.entity(arcId).at(1).distance(geometry.entity(splineId).at(0)),
                1e-6);
        assertEquals(
                geometry.entity(arcId).curvature(1), geometry.entity(splineId).curvature(0), 1e-8);
        click("sketch-tool-line");
        sketchClick(-10, 0, false);
        sketchClick(10, 0, false);
        sketchReady();
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-spline-curvature.png"));
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
        click("choose-plane-xy");
        sketchReady();
        click("sketch-tool-polygon");
        sketchClick(30, 20, false);
        sketchClick(45, 20, false);
        sketchReady();
        geometry = fx(() -> ((SketchEditor) node("#sketch-editor")).controller().definition());
        assertEquals(6, geometry.entities().size());
        assertEquals(
                SketchGeometry.Type.POLYGON, geometry.constraints().getFirst().type());
        assertEquals(
                4,
                fx(() -> ((SketchEditor) node("#sketch-editor"))
                        .controller()
                        .result()
                        .degreesOfFreedom()));
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("sketch-regular-polygon.png"));
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
        fx(() -> {
            var polygon = (Polygon) node("#view-cube-" + region);
            double x = 0, y = 0;
            var points = polygon.getPoints();
            for (int i = 0; i < points.size(); i += 2) {
                x += points.get(i);
                y += points.get(i + 1);
            }
            robot.mouseMove(polygon.localToScreen(x * 2 / points.size(), y * 2 / points.size()));
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(60);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
        assertEquals(
                expected, fx(() -> workbench.viewport().orientationProperty().get()), region);
    }

    @Test
    void gridDragRotatesWithoutTakingOverPickingSketchesOrDocumentHistory() throws Exception {
        ready();
        var empty = fx(() -> workbench.controller().snapshot());
        var background = fx(() -> {
            var viewport = workbench.viewport();
            return viewport.localToScreen(viewport.getWidth() * 0.25, viewport.getHeight() * 0.7);
        });
        primaryDrag(background, 40, 24);
        assertOrbit(new ViewOrientation(40, -55), "An empty document's grid can be grabbed and rotated right/down");
        assertEquals(
                Cursor.DEFAULT,
                fx(() -> node("#viewport-scene").getCursor()),
                "Releasing a grid drag restores the cursor");
        primaryDrag(background, -40, -24);
        assertOrbit(ViewOrientation.HOME, "Reverse drag returns to the original view");
        assertEquals(empty, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(() -> workbench.controller().dirty()));

        openPrepared("rectangle-0.vxb");
        var top = fx(() -> workbench.viewport().orientationProperty().get());
        double fittedWidth = fx(() -> workbench
                .viewport()
                .modelToScreen(new Point3D(0, 0, 0))
                .distance(workbench.viewport().modelToScreen(new Point3D(60, 0, 0))));
        // At Fit the sketch stroke is subpixel on the Linux test display. Zoom in
        // before picking its curve so Robot rounding cannot hit the adjacent grid.
        fx(() -> {
            robot.mouseMove(workbench.viewport().modelToScreen(new Point3D(30, 20, 0)));
            robot.mouseWheel(-8);
            return null;
        });
        Thread.sleep(200);
        assertTrue(
                fx(() -> workbench
                                .viewport()
                                .modelToScreen(new Point3D(0, 0, 0))
                                .distance(workbench.viewport().modelToScreen(new Point3D(60, 0, 0))))
                        > fittedWidth,
                "Zoom enlarges the curve before native picking");
        primaryDrag(fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 0, .04))), 80, 24);
        assertEquals(
                top,
                fx(() -> workbench.viewport().orientationProperty().get()),
                "A drag that starts on a sketch does not become a grid orbit");
        primaryDrag(
                fx(() -> workbench
                        .viewport()
                        .modelToScreen(new Point3D(30, 0, .04))
                        .add(0, 2)),
                40,
                12);
        assertEquals(
                top,
                fx(() -> workbench.viewport().orientationProperty().get()),
                "A small screen-space picking tolerance protects thin curves after viewport resizing");
        click("menu-fit");
        click("extrude");
        ready();
        key(KeyCode.ENTER);
        ready();
        save(directory.resolve("grid navigation.vxb"));
        click("view-cube-home");
        modelClick(30, 20, 5);
        var selection = fx(() -> ((Label) workbench.lookup(".selection-title")).getText());
        assertTrue(selection.startsWith("Face"), "Single-click still picks a face");
        var saved = fx(() -> workbench.controller().snapshot());
        primaryDrag(fx(() -> workbench.viewport().modelToScreen(new Point3D(30, 20, 5))), 40, 24);
        assertEquals(
                ViewOrientation.HOME,
                fx(() -> workbench.viewport().orientationProperty().get()),
                "Dragging from a body does not orbit, even when leaving its surface");
        primaryDrag(fx(() -> workbench.viewport().modelToScreen(new Point3D(-15, 10, 0))), 40, 24);
        assertOrbit(new ViewOrientation(40, -55), "Grid rotation also works with a model present");
        assertEquals(
                selection,
                fx(() -> ((Label) workbench.lookup(".selection-title")).getText()),
                "Releasing a grid drag does not change selection");
        assertEquals(saved, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(() -> workbench.controller().dirty()));
        fx(() -> {
            Screenshot.save(stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "grid-orbit.png"));
            return null;
        });

        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        var drawingView = fx(() -> workbench.viewport().orientationProperty().get());
        click("sketch-tool-rectangle");
        var drawingPoint =
                fx(() -> ((SketchEditor) node("#sketch-editor")).modelToScreen(new SketchGeometry.Vec(0, 0)));
        primaryDrag(drawingPoint, 40, 24);
        assertEquals(
                drawingView,
                fx(() -> workbench.viewport().orientationProperty().get()),
                "Drawing in the sketch editor cannot orbit the underlying model");
        key(KeyCode.ESCAPE);
        click("constraint-cancel");
        assertEquals(saved, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(() -> workbench.controller().dirty()));
    }

    @Test
    void viewCubeSnapsOrbitsAndFitsWithoutChangingTheDocument() throws Exception {
        openPrepared("plate-5mm.vxb");
        save(directory.resolve("navigation.vxb"));
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
        fx(() -> {
            robot.mouseWheel(4);
            return null;
        });
        Thread.sleep(200);
        assertEquals(
                orientation,
                fx(() -> workbench.viewport().orientationProperty().get()),
                "Pan and zoom do not rotate the cube");
        assertTrue(homeProjection.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2);
        click("view-cube-home");
        assertEquals(
                0,
                homeProjection.distance(fx(() -> workbench.viewport().modelToScreen(point))),
                0.01,
                "Home resets rotation, pan and zoom");
        var beforeDrag = fx(() -> workbench.viewport().modelToScreen(point));
        fx(() -> {
            var face = node("#view-cube-front");
            var bounds = face.localToScreen(face.getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseMove(robot.getMouseX() + 30, robot.getMouseY() + 20);
            return null;
        });
        Thread.sleep(150);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(180);
        assertTrue(
                beforeDrag.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2,
                "Dragging cube orbits the main view");
        assertOrbit(
                new ViewOrientation(38, -50), "Right/down drag follows the pointer and must not also snap on release");
        click("view-cube-menu");
        assertTrue(fx(() -> ((MenuButton) node("#view-cube-menu")).isShowing()));
        key(KeyCode.DOWN);
        key(KeyCode.DOWN);
        key(KeyCode.ENTER);
        assertEquals(
                ViewOrientation.named("Top"),
                fx(() -> workbench.viewport().orientationProperty().get()));
        // The sketch editor owns input while the design viewport is disabled.
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertTrue(fx(workbench::isDisabled));
        assertEquals(snapshot, fx(() -> workbench.controller().snapshot()));
        click("constraint-cancel");
        click("view-cube-home");
        modelClick(30, 20, 5);
        assertTrue(
                fx(() ->
                        ((Label) workbench.lookup(".selection-title")).getText().startsWith("Face")),
                "Model picking still works around the overlay");
        assertEquals(snapshot, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(() -> workbench.controller().dirty()));
        for (int palette = 1; palette <= 2; palette++) {
            choice("appearance", palette);
            String name = palette == 1 ? "view-cube-light.png" : "view-cube-dark.png";
            fx(() -> {
                Screenshot.save(stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), name));
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
        circle();
        click("cut");
        click("cut-confirm");
        ready();
        assertEquals(11937.168146928205, volume(), 0.01);
        Path file = directory.resolve("GUI reference.vxb");
        save(file);
        var before = DocumentReader.load(file);
        Path cliFile = directory.resolve("CLI reference.vxb");
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
        chooseExporter("3mf");
        text("export-path", exported.toString());
        click("export-confirm");
        ready();
        assertTrue(Files.size(exported) > 500);
        Path external = directory.resolve("external report.txt");
        click("export");
        chooseExporter("document-probe");
        text("file-path", external.toString());
        click("file-confirm");
        ready();
        assertTrue(Files.readString(external).startsWith("document="));
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
        assertTrue(inspection.contains(
                "bounds=0.000000000,0.000000000,0.000000000,60.000000000,40.000000000,8.000000000"));
        assertTrue(cli("inspect", file.toString(), "--point", "30,20,4").contains("contains=true"));
        standardView(0);
        modelClick(30, 20, 8);
        assertTrue(fx(
                () -> ((Label) workbench.lookup(".selection-title")).getText().startsWith("Face")));
        var selectedTree = fx(() -> ((TreeView<?>) node("#model-tree"))
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
        fx(() -> {
            robot.mouseWheel(4);
            return null;
        });
        Thread.sleep(250);
        assertTrue(
                afterPan.distance(fx(() -> workbench.viewport().modelToScreen(point))) > 2, "Zoom changes projection");
        assertEquals(navigationDocument, document());
        standardView(1);
        standardView(0);
        click("menu-fit");
        Path artifacts = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        Files.createDirectories(artifacts);
        choice("appearance", 1);
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-light.png"));
            return null;
        });
        choice("appearance", 2);
        fx(() -> {
            Screenshot.save(stage.getScene(), artifacts.resolve("workbench-dark.png"));
            return null;
        });
        assertFalse(Arrays.equals(
                Files.readAllBytes(artifacts.resolve("workbench-light.png")),
                Files.readAllBytes(artifacts.resolve("workbench-dark.png"))));
        Files.copy(file, artifacts.resolve("gui-reference-8mm.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(exported, artifacts.resolve("gui-reference.3mf"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(external, artifacts.resolve("external-provider.txt"), StandardCopyOption.REPLACE_EXISTING);
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
        Path file = directory.resolve("GUI spacer.vxb");
        save(file);
        var before = DocumentReader.load(file);
        Path plugins = directory.resolve("plugins with spaces"), jar = plugins.resolve("provider.jar");
        fx(() -> {
            workbench.close();
            return null;
        });
        workbench.whenClosed().toCompletableFuture().get(20, TimeUnit.SECONDS);
        Files.delete(jar);
        fx(() -> {
            workbench = Workbench.create(stage, plugins);
            stage.setScene(workbench.createScene(964, 648));
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
        assertDesignRejectsMeshExport("Rebuild failed");
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
        chooseExporter("3mf");
        text("export-path", exported.toString());
        click("export-confirm");
        ready();
        assertTrue(Files.size(exported) > 500);
        assertTrue(cli("inspect", file.toString(), "--point", "0,0,4", "--plugin-dir", plugins.toString())
                .contains("contains=false"));
        fx(() -> {
            Screenshot.save(
                    stage.getScene(), Path.of(System.getProperty("voxelbrew.ui.artifacts"), "custom-spacer.png"));
            return null;
        });
    }

    private void assertOrbit(ViewOrientation expected, String message) throws Exception {
        var actual = fx(() -> workbench.viewport().orientationProperty().get());
        // Native mouse coordinates can accumulate sub-pixel floating-point rounding.
        assertEquals(expected.tilt(), actual.tilt(), 1e-9, message + " (tilt)");
        assertEquals(expected.orbit(), actual.orbit(), 1e-9, message + " (orbit)");
    }

    private void primaryDrag(Point2D start, double dx, double dy) throws Exception {
        fx(() -> {
            robot.mouseMove(start);
            return null;
        });
        Thread.sleep(120);
        fx(() -> {
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        try {
            Thread.sleep(100);
            fx(() -> {
                robot.mouseMove(start.add(dx, dy));
                return null;
            });
            Thread.sleep(150);
        } finally {
            fx(() -> {
                robot.mouseRelease(MouseButton.PRIMARY);
                return null;
            });
        }
        Thread.sleep(180);
    }

    private void navigate(boolean pan) throws Exception {
        fx(() -> {
            var bounds = workbench.viewport().localToScreen(workbench.viewport().getBoundsInLocal());
            robot.mouseMove(bounds.getCenterX(), bounds.getCenterY());
            if (pan) robot.keyPress(KeyCode.SHIFT);
            robot.mousePress(MouseButton.MIDDLE);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            robot.mouseMove(robot.getMouseX() + 60, robot.getMouseY() + 35);
            return null;
        });
        Thread.sleep(150);
        fx(() -> {
            robot.mouseRelease(MouseButton.MIDDLE);
            if (pan) robot.keyRelease(KeyCode.SHIFT);
            return null;
        });
        Thread.sleep(150);
    }

    @Test
    void signedExtrusionDragAndEscapeHaveExactUndoSemantics() throws Exception {
        openPrepared("rectangle-12.vxb");
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
        fx(() -> {
            var point = workbench.viewport().handleScreenPosition();
            assertNotNull(point);
            robot.mouseMove(point);
            robot.mousePress(MouseButton.PRIMARY);
            robot.mouseMove(point.getX(), point.getY() - 60);
            return null;
        });
        Thread.sleep(200);
        key(KeyCode.ESCAPE);
        fx(() -> {
            robot.mouseRelease(MouseButton.PRIMARY);
            return null;
        });
        ready();
        assertEquals(before, document());
        assertFalse(fx(() -> workbench.controller().hasPreview()));
        click("history-0");
        click("extrude");
        ready();
        fx(() -> {
            var point = workbench.viewport().handleScreenPosition();
            robot.mouseMove(point);
            robot.mousePress(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(100);
        fx(() -> {
            var point = workbench.viewport().handleScreenPosition();
            robot.mouseMove(point.getX(), point.getY() - 70);
            return null;
        });
        Thread.sleep(200);
        fx(() -> {
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
        openPrepared("rectangle-12.vxb"); // A parallel offset plane is editable; the origin plane is fixed.
        var original = document();
        text("dimension-offsetZ", "NaN");
        key(KeyCode.ENTER);
        assertEquals(original, document());
        assertTrue(fx(() -> ((Label) node("#tool-error")).getText().contains("finite")));
        var sketch = (Feature.Sketch) original.features().getFirst();
        var broken = new CadDocument(
                "broken", List.of(sketch, new Feature.Extrude("bad", new Feature.ProfileRef("missing", "none"), 8)), 2);
        Path file = directory.resolve("broken.vxb");
        DocumentWriter.save(broken, file, false);
        // Open through the actual file action after saving the current document to avoid an
        // unrelated prompt.
        save(directory.resolve("scratch.vxb"));
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertFalse(fx(() -> workbench.controller().build().valid()));
        assertTrue(fx(() -> workbench.controller().build().bodies().isEmpty()));
        click("history-1");
        assertFalse(fx(() -> ((javafx.scene.layout.VBox) node("#feature-diagnostics"))
                .getChildren()
                .isEmpty()));
        choice("repair-sketch", 0);
        click("repair");
        ready();
        assertEquals(19200, volume(), 0.01);
        assertEquals("bad", document().features().getLast().id());
        assertTrue(fx(() -> ((javafx.scene.layout.VBox) node("#feature-diagnostics"))
                .getChildren()
                .isEmpty()));
    }

    @Test
    void searchableSketchTypesSupportKeyboardSelectionAndCancellation() throws Exception {
        ready();
        key(KeyCode.K);
        click("choose-plane-xy");
        sketchReady();
        key(KeyCode.M);
        assertEquals("", fx(() -> ((TextField) node("#sketch-type-search")).getText()));
        text("sketch-type-search", "CiRcLe");
        assertEquals(
                5,
                fx(() -> ((ListView<?>) node("#sketch-type-results")).getItems().size()));
        text("sketch-type-search", "no such tool");
        assertTrue(fx(() -> node("#sketch-type-confirm").isDisabled()));
        key(KeyCode.ESCAPE);
        assertTrue(sketchGeometry().entities().isEmpty());
        click("sketch-create-type");
        text("sketch-type-search", "2-point rectangle");
        key(KeyCode.ENTER);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(60, 40);
        assertEquals(4, sketchGeometry().entities().size());
        click("constraint-cancel");
        ready();
    }

    @Test
    void keyboardModelingNavigationAndSketchDraftsRespectFocusAndModalBoundaries() throws Exception {
        ready();
        click("operations-menu");
        fx(() -> {
            var menu = (MenuButton) node("#operations-menu");
            for (var id : List.of("extrude", "split-body")) {
                var item = menu.getItems().stream()
                        .filter(value -> id.equals(value.getId()))
                        .findFirst()
                        .orElseThrow();
                assertEquals(KeyboardShortcuts.primary(id), item.getAccelerator(), "Visible operation shortcut: " + id);
                assertNotNull(node("#" + id));
            }
            return null;
        });
        key(KeyCode.ESCAPE);
        click("menu-fit");
        key(KeyCode.E);
        assertNull(fx(() -> workbench.lookup("#extrude-distance")), "Disabled extrusion stays unavailable");
        shortcut(KeyCode.E, false);
        assertTrue(fx(workbench::isDisabled), "An empty design can still use document exporters");
        assertNotNull(fx(() -> node("#export-format")));
        key(KeyCode.ESCAPE);
        assertFalse(fx(workbench::isDisabled), "Cancelling export restores the workbench");
        assertNull(fx(() -> workbench.lookup("#rectangle")));
        assertNull(fx(() -> workbench.lookup("#circle")));
        var empty = fx(() -> workbench.controller().snapshot());
        key(KeyCode.R);
        key(KeyCode.C);
        assertEquals(empty, fx(() -> workbench.controller().snapshot()));
        assertFalse(fx(workbench::isDisabled), "R/C cannot open a standalone design tool");
        key(KeyCode.K);
        click("choose-plane-xy");
        sketchReady();
        assertShortcutCaption("sketch-tool-rectangle", "Rectangle", "R");
        assertShortcutCaption("sketch-tool-circle", "Circle", "C");
        assertShortcutCaption("sketch-tool-select", "Select (V)", "V");
        assertShortcutCaption("sketch-tool-polygon", "Polygon", "g");
        saveShortcutLabels("en-light");
        sketchType(SketchTools.Tool.RECTANGLE_TWO_POINT);
        sketchNumericPoint(0, 0);
        sketchNumericPoint(60, 40);
        click("constraint-finish");
        ready();
        assertEquals(1, document().features().size());
        var rectangle = document();
        text("dimension-offsetZ", "=30+30");
        shortcut(KeyCode.Z, false);
        assertEquals(rectangle, document(), "Typing undo must not remove a CAD feature");
        assertNotEquals("=30+30", fx(() -> ((TextField) node("#dimension-offsetZ")).getText()));
        shortcut(KeyCode.Y, false);
        assertEquals("=30+30", fx(() -> ((TextField) node("#dimension-offsetZ")).getText()));
        text("dimension-offsetZ", "circle");
        assertNull(fx(() -> workbench.lookup("#sketch-diameter")), "Letters stay in the field");
        text("dimension-offsetZ", "0");
        click("menu-fit");
        key(KeyCode.E);
        assertNotNull(fx(() -> node("#extrude-distance")));
        key(KeyCode.ESCAPE);
        assertEquals(rectangle, document(), "Cancelled extrusion creates no history entry");
        click("menu-fit");
        key(KeyCode.E);
        text("extrude-distance", "-5");
        key(KeyCode.ENTER);
        ready();
        assertEquals(2, document().features().size());
        assertEquals(12000, volume(), .01);
        click("menu-fit");
        shortcut(KeyCode.Z, false);
        ready();
        assertEquals(1, document().features().size());
        shortcut(KeyCode.Z, true);
        ready();
        assertEquals(2, document().features().size());
        var part = document();
        for (var entry : Map.of(
                        KeyCode.DIGIT0,
                        "Isometric",
                        KeyCode.DIGIT1,
                        "Top",
                        KeyCode.DIGIT2,
                        "Front",
                        KeyCode.DIGIT3,
                        "Right",
                        KeyCode.DIGIT4,
                        "Back",
                        KeyCode.DIGIT5,
                        "Left",
                        KeyCode.DIGIT6,
                        "Bottom")
                .entrySet()) {
            key(entry.getKey());
            assertEquals(
                    entry.getValue(),
                    fx(() -> workbench.viewport().orientationProperty().get().name()));
        }
        key(KeyCode.F);
        assertEquals(part, document(), "Navigation must not edit the document");
        save(directory.resolve("keyboard-workspace.vxb"));
        shortcut(KeyCode.DIGIT2, false);
        assertEquals(
                "workspace-print",
                fx(() -> ((TabPane) node("#workspaces"))
                        .getSelectionModel()
                        .getSelectedItem()
                        .getId()));
        key(KeyCode.K);
        assertNull(fx(() -> stage.getScene().lookup("#sketch-editor")), "Design tools are inactive in Print");
        shortcut(KeyCode.DIGIT1, false);
        shortcut(KeyCode.P, true);
        assertNotNull(fx(() -> node("#parameters-cancel")));
        shortcut(KeyCode.N, false);
        assertEquals(part, document());
        click("parameters-cancel");
        shortcut(KeyCode.E, false);
        assertNotNull(fx(() -> node("#export-format")));
        key(KeyCode.ESCAPE);
        click("menu-fit");
        key(KeyCode.K);
        click("choose-plane-xy");
        sketchReady();
        assertNull(fx(() -> stage.getScene().lookup("#constraint-plane")));
        key(KeyCode.R);
        sketchClick(0, 0, false);
        sketchClick(20, 10, false);
        sketchReady();
        assertEquals(4, sketchGeometry().entities().size());
        shortcut(KeyCode.Z, false);
        sketchReady();
        assertTrue(sketchGeometry().entities().isEmpty());
        shortcut(KeyCode.Y, false);
        sketchReady();
        assertEquals(4, sketchGeometry().entities().size());
        assertEquals(part, document(), "Sketch undo/redo stays in the draft");
        int constraints = sketchGeometry().constraints().size();
        assertTrue(constraints > 0);
        click("sketch-constraints");
        key(KeyCode.HOME);
        key(KeyCode.DELETE);
        sketchReady();
        assertEquals(
                constraints - 1, sketchGeometry().constraints().size(), "Delete works on the focused constraint list");
        shortcut(KeyCode.Z, false);
        sketchReady();
        assertEquals(constraints, sketchGeometry().constraints().size());
        openShortcutHelp();
        assertNotNull(fx(() -> node("#keyboard-shortcuts-list")));
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "keyboard-shortcuts-light.png"));
            return null;
        });
        shortcut(KeyCode.ENTER, false);
        assertEquals(part, document(), "Help overlay blocks finish-sketch");
        closeShortcutHelp();
        shortcut(KeyCode.ENTER, false);
        ready();
        assertEquals(3, document().features().size(), "Finish sketch commits exactly one operation");
        click("menu-fit");
        shortcut(KeyCode.Z, false);
        ready();
        assertEquals(part, document());
        click("history-cursor");
        key(KeyCode.HOME);
        ready();
        assertEquals(0, document().cursor());
        key(KeyCode.K);
        assertNull(fx(() -> stage.getScene().lookup("#sketch-editor")), "Rollback blocks new features");
        shortcut(KeyCode.E, false);
        assertNotNull(fx(() -> node("#export-format")), "Rollback still allows document exporters");
        key(KeyCode.ESCAPE);
        assertDesignRejectsMeshExport("Return timeline cursor to the end");
        click("history-cursor");
        key(KeyCode.END);
        ready();
        assertEquals(part, document());
        choice("appearance", 2);
        openShortcutHelp();
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "keyboard-shortcuts-dark.png"));
            return null;
        });
        closeShortcutHelp();
        click("constrained-sketch");
        click("choose-plane-xy");
        sketchReady();
        assertShortcutCaption("sketch-tool-rectangle", "Rectangle", "R");
        assertShortcutCaption("sketch-tool-select", "Select (V)", "V");
        saveShortcutLabels("en-dark");
        click("constraint-cancel");
    }

    private void assertShortcutCaption(String id, String expected, String letter) throws Exception {
        fx(() -> {
            var button = (ButtonBase) node("#" + id);
            button.getScene().getRoot().applyCss();
            button.getScene().getRoot().layout();
            var caption = (javafx.scene.layout.HBox) button.lookup(".shortcut-caption");
            assertNotNull(caption, id);
            var fragments = caption.getChildren().stream()
                    .map(javafx.scene.text.Text.class::cast)
                    .toList();
            assertEquals(
                    expected,
                    fragments.stream()
                            .map(javafx.scene.text.Text::getText)
                            .collect(java.util.stream.Collectors.joining()),
                    id);
            var marked = fragments.stream()
                    .filter(javafx.scene.text.Text::isUnderline)
                    .toList();
            assertEquals(1, marked.size(), id);
            assertEquals(letter, marked.getFirst().getText(), id);
            assertEquals(button.getTextFill(), marked.getFirst().getFill(), id);
            assertEquals(button.getFont(), marked.getFirst().getFont(), id);
            assertFalse(button.isMnemonicParsing(), "Do not register unrelated Alt mnemonics");
            var bounds = button.localToScene(button.getLayoutBounds());
            var textBounds = caption.localToScene(caption.getLayoutBounds());
            assertTrue(
                    textBounds.getMinX() >= bounds.getMinX() - 1 && textBounds.getMaxX() <= bounds.getMaxX() + 1,
                    "Full caption fits: " + id);
            assertTrue(
                    textBounds.getMinY() >= bounds.getMinY() - 1 && textBounds.getMaxY() <= bounds.getMaxY() + 1,
                    "Caption height fits: " + id);
            return null;
        });
    }

    private void saveShortcutLabels(String languageAndPalette) throws Exception {
        fx(() -> {
            Screenshot.save(
                    stage.getScene(),
                    Path.of(
                            System.getProperty("voxelbrew.ui.artifacts"),
                            "shortcut-labels-" + languageAndPalette + ".png"));
            return null;
        });
    }

    @Test
    void fileShortcutsAndUnsavedSaveDiscardCancelProtectDocuments() throws Exception {
        openPrepared("rectangle-12.vxb");
        text("dimension-offsetZ", "13");
        key(KeyCode.ENTER);
        ready();
        var original = document();
        assertTrue(fx(() -> stage.getTitle().startsWith("●")));
        click("new");
        click("unsaved-cancel");
        assertEquals(original, document());
        Path file = directory.resolve("Save As ~ design.vxb");
        shortcut(KeyCode.S, true);
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertFalse(fx(() -> workbench.controller().dirty()));
        text("dimension-offsetZ", "1");
        key(KeyCode.ENTER);
        ready();
        assertTrue(fx(() -> workbench.controller().dirty()));
        shortcut(KeyCode.N, false);
        click("unsaved-save");
        ready();
        assertTrue(document().features().isEmpty());
        assertEquals(
                1,
                ((Feature.Sketch) DocumentReader.load(file).features().getFirst())
                        .plane()
                        .offsetZ());
        shortcut(KeyCode.O, false);
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        click("history-0");
        text("dimension-offsetZ", "2");
        key(KeyCode.ENTER);
        ready();
        click("new");
        click("unsaved-discard");
        ready();
        assertTrue(document().features().isEmpty());
        assertEquals(
                1,
                ((Feature.Sketch) DocumentReader.load(file).features().getFirst())
                        .plane()
                        .offsetZ());
    }

    @Test
    @SuppressWarnings("unchecked")
    void printingSplitPlacementPetgAndActualPreviewMatchCli() throws Exception {
        ready();
        click("workspace-design");
        key(KeyCode.F1);
        Thread.sleep(500);
        assertTrue(fx(() -> ((Label) node("#context-help-content")).getText().contains("Create a sketch")));
        key(KeyCode.ESCAPE);
        click("workspace-print");
        assertNotNull(fx(() -> node("#file-path")));
        key(KeyCode.ESCAPE);
        assertTrue(fx(() -> ((TabPane) node("#workspaces"))
                .getSelectionModel()
                .getSelectedItem()
                .getId()
                .equals("workspace-design")));
        openPrepared("rectangle-0.vxb");
        click("extrude");
        ready();
        text("extrude-distance", "8");
        key(KeyCode.ENTER);
        ready();
        circle();
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
        ready();
        assertFalse(fx(() -> workbench.controller().dirty()));
        assertTrue(fx(() -> node("#print-instance").isDisabled()));
        assertTrue(fx(() -> node("#print-support-instance").isDisabled()));
        assertTrue(fx(() -> node("#print-support-id").isDisabled()));
        assertEquals("Body 1", fx(() -> {
            var choice = (ComboBox<String>) node("#print-body");
            return choice.getConverter().toString(choice.getValue());
        }));
        key(KeyCode.F1);
        assertTrue(fx(() -> ((Label) node("#context-help-content")).getText().contains("Add a CAD body")));
        key(KeyCode.ESCAPE);
        click("print-view-3d");
        assertTrue(fx(() -> ((RadioButton) node("#print-view-3d")).isSelected()));
        var plateCaption = centerOf("print-plate-size");
        fx(() -> {
            robot.mouseMove(plateCaption);
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(80);
        fx(() -> {
            robot.mouseClick(MouseButton.PRIMARY);
            return null;
        });
        Thread.sleep(250);
        text("print-bed-width", "250");
        text("print-bed-depth", "220");
        key(KeyCode.ENTER);
        ready();
        assertEquals(
                250,
                PrintProfile.resolve(document().printSetup())
                        .printer()
                        .bed()
                        .get(2)
                        .x());
        assertEquals(
                220,
                PrintProfile.resolve(document().printSetup())
                        .printer()
                        .bed()
                        .get(2)
                        .y());
        click("print-view-2d");
        click("print-add");
        ready();
        click("print-view-3d");
        fx(() -> {
            assertEquals(
                    "Body 1",
                    ((ComboBox<?>) node("#print-body")).getButtonCell().getText());
            Screenshot.save(
                    stage.getScene(),
                    Path.of(System.getProperty("voxelbrew.ui.artifacts"), "printing-3d-placement.png"));
            return null;
        });
        click("print-view-2d");
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
        Path file = directory.resolve("GUI print setup.vxb");
        save(file);
        click("open");
        text("file-path", file.toString());
        click("file-confirm");
        ready();
        assertEquals(DocumentReader.load(file), document());
        click("print-tab-job");
        assertEquals(
                file.resolveSibling("GUI print setup-1-Plate 1.gcode").toString(),
                fx(() -> ((FormulaField) node("#print-output")).getText()));
        assertEquals(
                250,
                PrintProfile.resolve(document().printSetup())
                        .printer()
                        .bed()
                        .get(2)
                        .x());
        Path job = directory.resolve("GUI printer job.gcode");
        text("print-output", job.toString());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (fx(() -> node("#print-slice").isDisabled()) && System.nanoTime() < deadline) Thread.sleep(25);
        click("print-slice");
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().running()) && System.nanoTime() < deadline)
            Thread.sleep(25);
        assertEquals(
                PrintController.State.SUCCEEDED,
                fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()),
                fx(() -> ((Label) node("#print-status")).getText()));
        var actual = GcodeReader.read(job);
        assertEquals(40, actual.modelLayers().size());
        assertEquals("PETG", actual.report().get("material"));
        click("print-layer");
        key(KeyCode.END);
        click("print-legend");
        click("print-role-travel");
        click("print-role-perimeter");
        Path evidence = Path.of(System.getProperty("voxelbrew.ui.artifacts"));
        fx(() -> {
            Screenshot.save(stage.getScene(), evidence.resolve("printing-dark.png"));
            return null;
        });
        click("workspace-design");
        choice("appearance", 1);
        click("workspace-print");
        fx(() -> {
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
        Files.copy(file, evidence.resolve("GUI print setup.vxb"), StandardCopyOption.REPLACE_EXISTING);
        Files.copy(job, evidence.resolve("GUI printer job.gcode"), StandardCopyOption.REPLACE_EXISTING);
        click("print-tab-process");
        text("print-layer-height", "0.3");
        click("print-apply-process");
        ready();
        assertEquals(
                PrintController.State.OUTDATED,
                fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().state()));
        assertNull(fx(() -> ((PrintingPane) node("#printing-workspace")).jobs().result()));
    }

    @Test
    void futureFileErrorsAreVisibleAndKeepTheCurrentDesign() throws Exception {
        // Version compatibility and byte preservation are covered headlessly in DocumentControllerTest.
        // Keep only the real file dialog -> status label -> current design wiring here.
        Path file = directory.resolve("current design.vxb");
        DocumentWriter.save(
                new CadDocument(
                        "current",
                        List.of(new Feature.Sketch(
                                "plate", "profile", Feature.Plane.origin("XY"), new Feature.Rectangle(0, 0, 60, 40))),
                        1),
                file,
                false);
        open(file);
        var before = document();
        var saved = Files.readAllBytes(file);
        var message = DocumentProto.Document.parseFrom(Arrays.copyOfRange(saved, 44, saved.length));
        var futureFile = directory.resolve("future design.vxb");
        Files.write(futureFile, versionedDocument(message, "65534.65534.65534"));
        click("open");
        text("file-path", futureFile.toString());
        click("file-confirm");
        ready();
        String error = fx(() -> ((Label) node("#status")).getText());
        assertTrue(error.contains("VoxelBrew 65534.65534.65534"), error);
        assertTrue(error.contains("supported version " + Version.version()), error);
        assertTrue(error.contains("Update VoxelBrew"), error);
        assertEquals(before, document());
        assertEquals(file.toAbsolutePath(), fx(() -> workbench.controller().path()));
        assertFalse(fx(() -> workbench.controller().dirty()));
    }

    private static byte[] versionedDocument(DocumentProto.Document document, String version) throws Exception {
        byte[] payload =
                document.toBuilder().setApplicationVersion(version).build().toByteArray();
        return ByteBuffer.allocate(44 + payload.length)
                .put(new byte[] {'J', 'C', 'A', 'D', 13, 37, 13, 37})
                .putInt(payload.length)
                .put(MessageDigest.getInstance("SHA-256").digest(payload))
                .put(payload)
                .array();
    }

    private String cli(String... arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Dvoxelbrew.native.path=" + System.getProperty("voxelbrew.native.path"));
        command.add("-Dvoxelbrew.home=" + directory.resolve("cli-settings"));
        command.add("-cp");
        command.add(Path.of(System.getProperty("voxelbrew.cli.distribution"), "lib") + java.io.File.separator + "*");
        command.add("dev.voxelbrew.cli.VoxelBrew");
        command.addAll(List.of(arguments));
        Path log = Files.createTempFile(directory, "cli-", ".txt");
        var process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(log.toFile())
                .start();
        assertTrue(process.waitFor(30, TimeUnit.SECONDS));
        String output = Files.readString(log);
        assertEquals(0, process.exitValue(), output);
        return output;
    }
}
