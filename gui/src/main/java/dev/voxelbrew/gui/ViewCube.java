package dev.voxelbrew.gui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javafx.geometry.Point3D;
import javafx.scene.AccessibleRole;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.control.Button;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.transform.Affine;

/** Beveled, orthographically projected navigation cube with 26 independently pickable regions. */
final class ViewCube extends Pane {
    private static final double SIZE = 176, CX = 88, CY = 91, OUTER = 36, INNER = 28;
    private final Group surface = new Group(), axes = new Group(), labels = new Group();
    private final List<Patch> patches = new ArrayList<>();
    private final Consumer<ViewOrientation> navigate;
    private final dev.voxelbrew.util.i18n.Translations translations;
    private ViewOrientation orientation = ViewOrientation.HOME;
    private boolean dark;
    private double lastX, lastY;

    private record Patch(int x, int y, int z, List<Point3D> vertices, Polygon shape, Text label) {
        Point3D normal() {
            return new Point3D(x, y, z).normalize();
        }

        boolean face() {
            return label != null;
        }
    }

    ViewCube(
            Consumer<ViewOrientation> navigate,
            BiConsumer<Double, Double> orbit,
            Runnable home,
            Runnable fit,
            dev.voxelbrew.util.i18n.Translations translations) {
        this.navigate = navigate;
        this.translations = translations;
        setId("view-cube");
        setMinSize(SIZE, SIZE);
        setPrefSize(SIZE, SIZE);
        setMaxSize(SIZE, SIZE);
        setPickOnBounds(false);
        surface.setPickOnBounds(false);
        axes.setMouseTransparent(true);
        labels.setMouseTransparent(true);
        getChildren().addAll(axes, surface, labels);
        for (int z = -1; z <= 1; z++)
            for (int y = -1; y <= 1; y++)
                for (int x = -1; x <= 1; x++) if (x != 0 || y != 0 || z != 0) addPatch(x, y, z);

        surface.setOnMousePressed(
                e -> {
                    if (e.getButton() != MouseButton.PRIMARY) return;
                    lastX = e.getScreenX();
                    lastY = e.getScreenY();
                    e.consume();
                });
        surface.setOnMouseDragged(
                e -> {
                    if (!e.isPrimaryButtonDown()) return;
                    orbit.accept(e.getScreenX() - lastX, e.getScreenY() - lastY);
                    lastX = e.getScreenX();
                    lastY = e.getScreenY();
                    e.consume();
                });

        var homeIcon = new SVGPath();
        homeIcon.setContent("M1 9 L10 1 L19 9 M4 7 L4 19 L8 19 L8 12 L12 12 L12 19 L16 19 L16 7");
        homeIcon.getStyleClass().add("view-cube-home-icon");
        var homeButton = new Button("", homeIcon);
        homeButton.setId("view-cube-home");
        homeButton.getStyleClass().add("view-cube-control");
        homeButton.setAccessibleText(translations.text("gui.home_view_and_fit_model"));
        homeButton.setTooltip(new Tooltip(translations.text("gui.home_view_fit_model")));
        homeButton.setOnAction(e -> home.run());
        homeButton.relocate(14, 4);
        var menu = new MenuButton();
        menu.setId("view-cube-menu");
        menu.getStyleClass().add("view-cube-control");
        menu.setAccessibleText(translations.text("gui.standard_views"));
        menu.setTooltip(new Tooltip(translations.text("gui.standard_views")));
        for (String name :
                new String[] {"Isometric", "Top", "Front", "Right", "Back", "Left", "Bottom"}) {
            var item = new MenuItem(UiText.view(translations, name));
            item.setOnAction(e -> navigate.accept(ViewOrientation.named(name)));
            menu.getItems().add(item);
        }
        var fitItem = new MenuItem(translations.text("gui.fit_model"));
        fitItem.setOnAction(e -> fit.run());
        menu.getItems().add(fitItem);
        menu.relocate(142, 143);
        getChildren().addAll(homeButton, menu);
        render();
    }

    void setOrientation(ViewOrientation value) {
        orientation = value;
        render();
    }

    void setDark(boolean value) {
        dark = value;
        render();
    }

    private void addPatch(int x, int y, int z) {
        int[] signs = {x, y, z};
        var fixed = new ArrayList<Integer>();
        var free = new ArrayList<Integer>();
        for (int i = 0; i < 3; i++) (signs[i] == 0 ? free : fixed).add(i);
        var vertices = new ArrayList<Point3D>();
        if (fixed.size() == 1) {
            for (int[] corner : new int[][] {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}}) {
                double[] v = {0, 0, 0};
                v[fixed.getFirst()] = signs[fixed.getFirst()] * OUTER;
                for (int i = 0; i < 2; i++) v[free.get(i)] = corner[i] * INNER;
                vertices.add(new Point3D(v[0], v[1], v[2]));
            }
        } else if (fixed.size() == 2) {
            for (int[] corner : new int[][] {{0, -1}, {1, -1}, {1, 1}, {0, 1}}) {
                double[] v = {0, 0, 0};
                for (int i = 0; i < 2; i++)
                    v[fixed.get(i)] = signs[fixed.get(i)] * (i == corner[0] ? OUTER : INNER);
                v[free.getFirst()] = corner[1] * INNER;
                vertices.add(new Point3D(v[0], v[1], v[2]));
            }
        } else {
            for (int i = 0; i < 3; i++)
                vertices.add(
                        new Point3D(
                                x * (i == 0 ? OUTER : INNER),
                                y * (i == 1 ? OUTER : INNER),
                                z * (i == 2 ? OUTER : INNER)));
        }
        var names = new ArrayList<String>();
        if (z != 0) names.add(z > 0 ? "Top" : "Bottom");
        if (y != 0) names.add(y < 0 ? "Front" : "Back");
        if (x != 0) names.add(x > 0 ? "Right" : "Left");
        String name = String.join(" ", names);
        var polygon = new Polygon();
        polygon.setId("view-cube-" + name.toLowerCase(Locale.ROOT).replace(' ', '-'));
        polygon.setCursor(Cursor.HAND);
        polygon.setFocusTraversable(true);
        polygon.setAccessibleRole(AccessibleRole.BUTTON);
        polygon.setAccessibleText(
                translations.text("view.accessible", UiText.view(translations, name)));
        Tooltip.install(
                polygon,
                new Tooltip(translations.text("view.orbit", UiText.view(translations, name))));
        Text label =
                fixed.size() == 1
                        ? new Text(
                                UiText.view(translations, name).toUpperCase(translations.locale()))
                        : null;
        if (label != null) {
            label.setFont(Font.font("SansSerif", FontWeight.BOLD, 11));
            labels.getChildren().add(label);
        }
        var patch = new Patch(x, y, z, vertices, polygon, label);
        patches.add(patch);
        surface.getChildren().add(polygon);
        polygon.hoverProperty().addListener((o, before, after) -> paint(patch));
        polygon.focusedProperty().addListener((o, before, after) -> paint(patch));
        polygon.setOnMouseClicked(
                e -> {
                    if (e.getButton() == MouseButton.PRIMARY && e.isStillSincePress()) {
                        polygon.requestFocus();
                        navigate.accept(ViewOrientation.lookingFrom(x, y, z));
                    }
                    e.consume();
                });
        polygon.setOnKeyPressed(
                e -> {
                    if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) {
                        navigate.accept(ViewOrientation.lookingFrom(x, y, z));
                        e.consume();
                    }
                });
    }

    private void render() {
        axes.getChildren().clear();
        for (int axis = 0; axis < 3; axis++) {
            double[] end = {0, 0, 0};
            end[axis] = 27;
            var p = orientation.project(new Point3D(end[0], end[1], end[2]));
            var color = Color.web(new String[] {"#d85b58", "#35a779", "#548de0"}[axis]);
            var line = new Line(30, 144, 30 + p.getX(), 144 + p.getY());
            line.setStroke(color);
            line.setStrokeWidth(1.4);
            var text = new Text("XYZ".substring(axis, axis + 1));
            text.setFont(Font.font("SansSerif", FontWeight.BOLD, 12));
            text.setFill(color);
            text.setX(30 + p.getX() + 3);
            text.setY(144 + p.getY() - 3);
            axes.getChildren().addAll(line, text);
        }
        for (var patch : patches) {
            boolean visible = orientation.project(patch.normal()).getZ() < -1e-8;
            patch.shape().setVisible(visible);
            patch.shape().getPoints().clear();
            for (var vertex : patch.vertices()) {
                var p = orientation.project(vertex);
                patch.shape().getPoints().addAll(CX + p.getX(), CY + p.getY());
            }
            paint(patch);
            if (patch.face()) {
                var label = patch.label();
                label.setVisible(visible);
                label.setFill(Color.web(dark ? "#e6edf5" : "#334252"));
                Point3D u =
                        patch.x() != 0
                                ? new Point3D(0, patch.x(), 0)
                                : new Point3D(patch.y() > 0 ? -1 : 1, 0, 0);
                Point3D v = patch.z() != 0 ? new Point3D(0, -patch.z(), 0) : new Point3D(0, 0, -1);
                var a = orientation.project(u);
                var b = orientation.project(v);
                var c = orientation.project(patch.normal().multiply(OUTER));
                var bounds = label.getLayoutBounds();
                double lx = -bounds.getCenterX(), ly = -bounds.getCenterY();
                label.getTransforms()
                        .setAll(
                                new Affine(
                                        a.getX(),
                                        b.getX(),
                                        CX + c.getX() + a.getX() * lx + b.getX() * ly,
                                        a.getY(),
                                        b.getY(),
                                        CY + c.getY() + a.getY() * lx + b.getY() * ly));
            }
        }
        var order =
                patches.stream()
                        .sorted(
                                Comparator.comparingDouble(
                                                (Patch patch) ->
                                                        patch.vertices().stream()
                                                                .mapToDouble(
                                                                        v ->
                                                                                orientation
                                                                                        .project(v)
                                                                                        .getZ())
                                                                .average()
                                                                .orElseThrow())
                                        .reversed())
                        .toList();
        // Reorder existing nodes without removing a pressed node during an orbit gesture.
        for (int i = 0; i < order.size(); i++) order.get(i).shape().setViewOrder(-i);
    }

    private void paint(Patch patch) {
        double light =
                Math.max(
                        0,
                        orientation
                                .project(patch.normal())
                                .dotProduct(new Point3D(-0.3, -0.6, -1).normalize()));
        boolean highlighted = patch.shape().isHover() || patch.shape().isFocused();
        patch.shape()
                .setFill(
                        highlighted
                                ? Color.web(dark ? "#41698c" : "#a6cbea")
                                : Color.gray((dark ? 0.23 : 0.67) + light * (dark ? 0.18 : 0.28)));
        patch.shape().setStroke(Color.web(highlighted ? "#548de0" : dark ? "#a2aeba" : "#6d7c8b"));
        patch.shape().setStrokeWidth(highlighted ? 1.2 : 0.65);
    }
}
