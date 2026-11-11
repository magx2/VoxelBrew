package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.MenuBar;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.shape.SVGPath;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.WindowEvent;

/** Persistent client-side window frame; modal editors replace only its content. */
final class WindowFrame extends BorderPane {
    static final int LEFT = 1, RIGHT = 2, TOP = 4, BOTTOM = 8;
    private final Stage stage;
    private final Translations translations;
    private final Button maximize;
    private final javafx.beans.value.ChangeListener<Boolean> windowState =
            (observable, before, after) -> updateMaximize();
    private final javafx.beans.value.ChangeListener<Boolean> windowFocus = (observable, before, focused) ->
            pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("inactive"), !focused);
    private Rectangle2D restoreBounds, pressedBounds;
    private double pressX, pressY, dragOffsetX, dragOffsetY;
    private int resizeEdges;
    private boolean moving, expanded;

    WindowFrame(Stage stage, HBox header, Parent content, Translations translations) {
        this.stage = stage;
        this.translations = translations;
        if (stage.getStyle() != StageStyle.UNDECORATED) stage.initStyle(StageStyle.UNDECORATED);
        setId("window-frame");
        getStyleClass().add("window-frame");
        getStylesheets().setAll(content.getStylesheets());
        setCenter(content);
        setTop(header);
        header.setId("window-header");
        var minimize = control("window-minimize", "M2 8 H12", () -> stage.setIconified(true));
        maximize = control("window-maximize", "M2 2 H12 V12 H2 Z", this::toggleMaximize);
        var close = control("window-close", "M2 2 L12 12 M12 2 L2 12", this::requestClose);
        var controls = new HBox(minimize, maximize, close);
        controls.setId("window-controls");
        controls.getStyleClass().add("window-controls");
        controls.setMinWidth(Region.USE_PREF_SIZE);
        header.getChildren().add(controls);
        stage.maximizedProperty().addListener(windowState);
        stage.fullScreenProperty().addListener(windowState);
        stage.resizableProperty().addListener(windowState);
        stage.focusedProperty().addListener(windowFocus);
        stage.setMinWidth(Math.min(800, Screen.getPrimary().getVisualBounds().getWidth() - 60));
        stage.setMinHeight(Math.min(540, Screen.getPrimary().getVisualBounds().getHeight() - 80));
        header.addEventHandler(MouseEvent.MOUSE_PRESSED, this::beginMove);
        header.addEventHandler(MouseEvent.MOUSE_DRAGGED, this::move);
        header.addEventHandler(MouseEvent.MOUSE_RELEASED, e -> moving = false);
        header.addEventFilter(MouseEvent.MOUSE_CLICKED, e -> {
            if (e.getButton() == MouseButton.PRIMARY
                    && e.getClickCount() == 2
                    && e.isStillSincePress()
                    && dragTarget(e.getTarget())) {
                toggleMaximize();
                e.consume();
            }
        });
        addEventFilter(MouseEvent.MOUSE_MOVED, e -> setCursor(resizeCursor(edges(e))));
        addEventFilter(MouseEvent.MOUSE_EXITED, e -> {
            if (resizeEdges == 0) setCursor(Cursor.DEFAULT);
        });
        addEventFilter(MouseEvent.MOUSE_PRESSED, e -> {
            if (e.getButton() != MouseButton.PRIMARY) return;
            resizeEdges = edges(e);
            if (resizeEdges == 0) return;
            pressedBounds = bounds();
            pressX = e.getScreenX();
            pressY = e.getScreenY();
            e.consume();
        });
        addEventFilter(MouseEvent.MOUSE_DRAGGED, e -> {
            if (resizeEdges == 0 || !e.isPrimaryButtonDown()) return;
            apply(resize(
                    pressedBounds,
                    resizeEdges,
                    e.getScreenX() - pressX,
                    e.getScreenY() - pressY,
                    stage.getMinWidth(),
                    stage.getMinHeight(),
                    stage.getMaxWidth(),
                    stage.getMaxHeight()));
            e.consume();
        });
        addEventFilter(MouseEvent.MOUSE_RELEASED, e -> {
            if (resizeEdges == 0) return;
            resizeEdges = 0;
            setCursor(Cursor.DEFAULT);
            e.consume();
        });
        addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isAltDown() && e.getCode() == KeyCode.F4) {
                e.consume();
                requestClose();
            }
        });
        updateMaximize();
    }

    private Button control(String id, String path, Runnable action) {
        var icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("window-control-icon");
        icon.setMouseTransparent(true);
        var button = new Button("", icon);
        button.setId(id);
        button.getStyleClass().add("window-control");
        button.setAccessibleText(translations.text("help." + id));
        UiHelp.install(button, translations);
        button.setOnAction(e -> action.run());
        return button;
    }

    private void requestClose() {
        stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST));
    }

    private boolean maximized() {
        return expanded || stage.isMaximized();
    }

    private void toggleMaximize() {
        if (!stage.isResizable() || stage.isFullScreen()) return;
        if (maximized()) restore();
        else {
            restoreBounds = bounds();
            expanded = true;
            // Client-side maximize also works on WSL/Xvfb without a window manager.
            // Visual bounds keep the taskbar/dock accessible on the current monitor.
            var screen =
                    Screen.getScreensForRectangle(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight())
                            .stream()
                            .max(java.util.Comparator.comparingDouble(s -> intersection(bounds(), s.getBounds())))
                            .orElse(Screen.getPrimary());
            apply(screen.getVisualBounds());
        }
        updateMaximize();
    }

    private static double intersection(Rectangle2D a, Rectangle2D b) {
        return Math.max(0, Math.min(a.getMaxX(), b.getMaxX()) - Math.max(a.getMinX(), b.getMinX()))
                * Math.max(0, Math.min(a.getMaxY(), b.getMaxY()) - Math.max(a.getMinY(), b.getMinY()));
    }

    private void restore() {
        expanded = false;
        stage.setMaximized(false);
        if (restoreBounds != null) apply(restoreBounds);
        updateMaximize();
    }

    private void updateMaximize() {
        boolean restored = !maximized();
        ((SVGPath) maximize.getGraphic()).setContent(restored ? "M2 2 H12 V12 H2 Z" : "M4 1 H13 V10 M1 4 H10 V13 H1 Z");
        String help = translations.text(restored ? "help.window-maximize" : "help.window-restore");
        maximize.setAccessibleText(help);
        UiHelp.describe(maximize, help);
        maximize.setDisable(!stage.isResizable() || stage.isFullScreen());
        pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("maximized"), !restored);
    }

    private boolean dragTarget(Object target) {
        if (!(target instanceof Node node)) return false;
        for (; node != null && node != getTop(); node = node.getParent())
            if (node instanceof ButtonBase || node instanceof MenuBar) return false;
        return node == getTop();
    }

    private void beginMove(MouseEvent event) {
        if (event.getButton() != MouseButton.PRIMARY || stage.isFullScreen() || !dragTarget(event.getTarget())) return;
        moving = true;
        pressedBounds = bounds();
        pressX = event.getScreenX();
        pressY = event.getScreenY();
        dragOffsetX = pressX - stage.getX();
        dragOffsetY = pressY - stage.getY();
    }

    private void move(MouseEvent event) {
        if (!moving || !event.isPrimaryButtonDown() || event.isStillSincePress()) return;
        if (maximized()) {
            double fraction = Math.clamp(dragOffsetX / pressedBounds.getWidth(), 0, 1);
            restore();
            dragOffsetX = stage.getWidth() * fraction;
            dragOffsetY = Math.min(dragOffsetY, getTop().getBoundsInParent().getHeight() / 2);
        }
        stage.setX(event.getScreenX() - dragOffsetX);
        stage.setY(event.getScreenY() - dragOffsetY);
        event.consume();
    }

    private Rectangle2D bounds() {
        return new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
    }

    private void apply(Rectangle2D bounds) {
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
    }

    private int edges(MouseEvent event) {
        if (maximized() || stage.isFullScreen() || !stage.isResizable()) return 0;
        double x = event.getSceneX(), y = event.getSceneY();
        return (x < 6 ? LEFT : x > getWidth() - 6 ? RIGHT : 0) | (y < 6 ? TOP : y > getHeight() - 6 ? BOTTOM : 0);
    }

    private static Cursor resizeCursor(int edges) {
        return switch (edges) {
            case LEFT -> Cursor.W_RESIZE;
            case RIGHT -> Cursor.E_RESIZE;
            case TOP -> Cursor.N_RESIZE;
            case BOTTOM -> Cursor.S_RESIZE;
            case TOP | LEFT -> Cursor.NW_RESIZE;
            case TOP | RIGHT -> Cursor.NE_RESIZE;
            case BOTTOM | LEFT -> Cursor.SW_RESIZE;
            case BOTTOM | RIGHT -> Cursor.SE_RESIZE;
            default -> Cursor.DEFAULT;
        };
    }

    static Rectangle2D resize(
            Rectangle2D start,
            int edges,
            double dx,
            double dy,
            double minWidth,
            double minHeight,
            double maxWidth,
            double maxHeight) {
        double width = Math.clamp(
                start.getWidth() + ((edges & LEFT) != 0 ? -dx : (edges & RIGHT) != 0 ? dx : 0), minWidth, maxWidth);
        double height = Math.clamp(
                start.getHeight() + ((edges & TOP) != 0 ? -dy : (edges & BOTTOM) != 0 ? dy : 0), minHeight, maxHeight);
        return new Rectangle2D(
                (edges & LEFT) != 0 ? start.getMaxX() - width : start.getMinX(),
                (edges & TOP) != 0 ? start.getMaxY() - height : start.getMinY(),
                width,
                height);
    }

    static Parent content(Scene scene) {
        return scene.getRoot() instanceof WindowFrame frame ? (Parent) frame.getCenter() : scene.getRoot();
    }

    void close() {
        stage.maximizedProperty().removeListener(windowState);
        stage.fullScreenProperty().removeListener(windowState);
        stage.resizableProperty().removeListener(windowState);
        stage.focusedProperty().removeListener(windowFocus);
    }

    static void content(Scene scene, Parent content) {
        if (scene.getRoot() instanceof WindowFrame frame) frame.setCenter(content);
        else scene.setRoot(content);
    }
}
