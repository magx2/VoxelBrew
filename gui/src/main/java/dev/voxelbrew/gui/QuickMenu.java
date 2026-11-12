package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import java.util.*;
import java.util.function.*;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ObservableBooleanValue;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import javafx.stage.Screen;

/** Window-owned command popup. Actions share the controls' validation and availability. */
final class QuickMenu implements AutoCloseable {
    record Action(
            String id,
            String label,
            String icon,
            ObservableBooleanValue disabled,
            ObservableBooleanValue selected,
            Runnable invoke) {
        static Action button(ButtonBase source, String icon) {
            ObservableBooleanValue selected = source instanceof ToggleButton toggle
                    ? toggle.selectedProperty()
                    : source instanceof CheckBox check ? check.selectedProperty() : null;
            return new Action(
                    source.getId(), source.getText(), icon, source.disabledProperty(), selected, source::fire);
        }

        static Action menu(MenuItem source, String icon) {
            return new Action(source.getId(), source.getText(), icon, source.disableProperty(), null, source::fire);
        }
    }

    record Content(List<Action> radial, List<Action> additional) {}

    private final Node surface;
    private final Parent theme;
    private final Translations text;
    private final Supplier<Content> content;
    private final Supplier<List<Action>> workspaces;
    private final BiConsumer<Double, Double> pan;
    private final Predicate<Node> accepts;
    private final MiddleGesture gesture = new MiddleGesture();
    private final Popup popup = new Popup();
    private final List<ButtonBase> buttons = new ArrayList<>();
    private final List<MenuItem> menuItems = new ArrayList<>();
    private final EventHandler<MouseEvent> mouse = this::mouse;
    private javafx.stage.Window owner;
    private final javafx.beans.value.ChangeListener<Boolean> focusChanged = (o, a, focused) -> {
        if (!focused)
            javafx.application.Platform.runLater(() -> {
                if (owner != null && !owner.isFocused() && !popup.isFocused()) hide();
            });
    };
    private final javafx.beans.value.ChangeListener<javafx.stage.Window> windowChanged = (o, a, b) -> observeWindow(b);
    private final javafx.beans.value.ChangeListener<javafx.scene.Scene> sceneChanged = (o, a, b) -> {
        hide();
        if (a != null) a.windowProperty().removeListener(windowChanged);
        if (b != null) b.windowProperty().addListener(windowChanged);
        observeWindow(b == null ? null : b.getWindow());
    };
    private boolean active;

    QuickMenu(
            Node surface,
            Parent theme,
            Translations text,
            Supplier<Content> content,
            Supplier<List<Action>> workspaces,
            BiConsumer<Double, Double> pan,
            Predicate<Node> accepts) {
        this.surface = surface;
        this.theme = theme;
        this.text = text;
        this.content = content;
        this.workspaces = workspaces;
        this.pan = pan;
        this.accepts = accepts;
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setConsumeAutoHidingEvents(true);
        popup.setOnHidden(e -> {
            buttons.forEach(button -> {
                button.disableProperty().unbind();
                button.textProperty().unbind();
                button.setOnAction(null);
            });
            buttons.clear();
            menuItems.forEach(item -> {
                item.disableProperty().unbind();
                item.textProperty().unbind();
                item.setOnAction(null);
            });
            menuItems.clear();
            popup.getContent().clear();
        });
        surface.addEventFilter(MouseEvent.ANY, mouse);
        surface.sceneProperty().addListener(sceneChanged);
        if (surface.getScene() != null) {
            surface.getScene().windowProperty().addListener(windowChanged);
            observeWindow(surface.getScene().getWindow());
        }
    }

    private void observeWindow(javafx.stage.Window next) {
        if (owner != null) owner.focusedProperty().removeListener(focusChanged);
        owner = next;
        if (owner != null) owner.focusedProperty().addListener(focusChanged);
    }

    static List<Action> controls(Parent root, String... ids) {
        return controls(id -> (ButtonBase) root.lookup("#" + id), ids);
    }

    static List<Action> controls(Function<String, ? extends ButtonBase> commands, String... ids) {
        var result = new ArrayList<Action>();
        for (String id : ids) {
            var button = commands.apply(id);
            if (button != null) result.add(Action.button(button, icon(id)));
            else throw new IllegalStateException("Missing quick-menu command: " + id);
        }
        return result;
    }

    static List<Action> sketchWorkspaces(Translations text) {
        var disabled = new javafx.beans.property.SimpleBooleanProperty(true);
        return List.of(
                new Action(
                        "workspace-design",
                        text.text("tab.Designer"),
                        "▣",
                        disabled,
                        new javafx.beans.property.SimpleBooleanProperty(true),
                        () -> {}),
                new Action("workspace-print", text.text("tab.Slicer"), "▣", disabled, null, () -> {}),
                new Action("workspace-printer", text.text("tab.Printer"), "▣", disabled, null, () -> {}));
    }

    private static String icon(String id) {
        if (id.contains("undo")) return "↶";
        if (id.contains("redo")) return "↷";
        if (id.contains("cancel") || id.contains("remove")) return "×";
        if (id.contains("finish") || id.contains("commit")) return "✓";
        if (id.contains("fit")) return "⛶";
        if (id.contains("circle")) return "○";
        if (id.contains("rectangle")) return "▭";
        if (id.contains("slice")) return "▤";
        if (id.contains("sketch")) return "✎";
        if (id.contains("pause")) return "Ⅱ";
        if (id.contains("print") || id.contains("resume")) return "▷";
        return "◇";
    }

    private void mouse(MouseEvent event) {
        if (event.getEventType() == MouseEvent.MOUSE_PRESSED && event.getButton() == MouseButton.MIDDLE) {
            if (event.isPrimaryButtonDown()
                    || event.isSecondaryButtonDown()
                    || !(event.getTarget() instanceof Node target)
                    || !accepts.test(target)) return;
            hide();
            active = true;
            gesture.press(event.getScreenX(), event.getScreenY());
            surface.requestFocus();
            event.consume();
        } else if (active && event.getEventType() == MouseEvent.MOUSE_DRAGGED) {
            var delta = gesture.drag(event.getScreenX(), event.getScreenY());
            if (delta != null) {
                surface.setCursor(Cursor.CLOSED_HAND);
                pan.accept(delta[0], delta[1]);
            }
            event.consume();
        } else if (active
                && event.getEventType() == MouseEvent.MOUSE_RELEASED
                && event.getButton() == MouseButton.MIDDLE) {
            boolean click = gesture.release(event.getScreenX(), event.getScreenY());
            active = false;
            surface.setCursor(Cursor.DEFAULT);
            if (click) show(event.getScreenX(), event.getScreenY());
            event.consume();
        }
    }

    private Button actionButton(Action action) {
        var button = new Button();
        button.setId("quick-" + action.id());
        button.setGraphic(new Label(action.icon()));
        button.setAccessibleText(action.label());
        button.textProperty()
                .bind(
                        action.selected() == null
                                ? new javafx.beans.property.SimpleStringProperty(action.label())
                                : Bindings.when(action.selected())
                                        .then("✓ " + action.label())
                                        .otherwise(action.label()));
        button.disableProperty().bind(action.disabled());
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(e -> {
            if (action.disabled().get()) return;
            hide();
            action.invoke().run();
        });
        buttons.add(button);
        return button;
    }

    private void show(double x, double y) {
        if (surface.getScene() == null || surface.isDisabled()) return;
        var commands = content.get();
        var root = new VBox(8);
        root.setId("quick-menu");
        root.getStyleClass().addAll("root", "quick-menu", theme.getStyleClass().contains("dark") ? "dark" : "light");
        root.getStylesheets()
                .add(Objects.requireNonNull(QuickMenu.class.getResource("workbench.css"))
                        .toExternalForm());
        root.setAlignment(Pos.TOP_CENTER);
        root.setPadding(new Insets(8));
        var ring = new GridPane();
        ring.setHgap(8);
        ring.setVgap(8);
        int[][] slots = {{1, 0}, {2, 0}, {2, 1}, {2, 2}, {1, 2}, {0, 2}, {0, 1}, {0, 0}};
        for (int index = 0; index < Math.min(8, commands.radial().size()); index++) {
            var button = actionButton(commands.radial().get(index));
            button.setPrefWidth(150);
            button.setMinHeight(38);
            button.setWrapText(true);
            ring.add(button, slots[index][0], slots[index][1]);
        }
        root.getChildren().add(ring);
        var list = new VBox(3);
        list.getStyleClass().add("quick-menu-list");
        list.setMaxWidth(300);
        var workspaceButton = new MenuButton(text.text("quick.workspace"));
        workspaceButton.setId("quick-workspaces");
        workspaceButton.setMaxWidth(Double.MAX_VALUE);
        var workspaceActions = workspaces.get();
        for (var action : workspaceActions) {
            var item = new MenuItem(action.label());
            item.setId("quick-" + action.id());
            item.disableProperty().bind(action.disabled());
            if (action.selected() != null)
                item.textProperty()
                        .bind(Bindings.when(action.selected())
                                .then("✓ " + action.label())
                                .otherwise(action.label()));
            item.setOnAction(e -> {
                if (action.disabled().get()) return;
                hide();
                action.invoke().run();
            });
            menuItems.add(item);
            workspaceButton.getItems().add(item);
        }
        popup.setOnHiding(e -> workspaceButton.hide());
        list.getChildren().addAll(workspaceButton, new Separator());
        if (workspaceActions.stream().allMatch(action -> action.disabled().get())) {
            var hint = new Label(text.text("quick.finishSketchFirst"));
            hint.setWrapText(true);
            list.getChildren().add(hint);
        }
        for (var action : commands.additional()) list.getChildren().add(actionButton(action));
        var scroll = new ScrollPane(list);
        scroll.setFitToWidth(true);
        scroll.setMaxWidth(310);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("quick-menu-scroll");
        root.getChildren().add(scroll);
        var bounds = Screen.getScreensForRectangle(x, y, 1, 1).stream()
                .findFirst()
                .orElse(Screen.getPrimary())
                .getVisualBounds();
        popup.getContent().setAll(root);
        root.applyCss();
        root.autosize();
        scroll.setPrefViewportHeight(
                Math.min(list.prefHeight(300), Math.max(80, bounds.getHeight() - ring.prefHeight(-1) - 48)));
        root.autosize();
        double width = root.prefWidth(-1), height = root.prefHeight(width);
        double left = Math.max(bounds.getMinX(), Math.min(x - width / 2, bounds.getMaxX() - width));
        double top = Math.max(bounds.getMinY(), Math.min(y - ring.prefHeight(-1) / 2, bounds.getMaxY() - height));
        popup.show(surface.getScene().getWindow(), left, top);
        root.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                hide();
                event.consume();
            } else if (event.getCode() == KeyCode.ENTER) {
                var focused = root.getScene().getFocusOwner();
                if (focused instanceof MenuButton menu) menu.show();
                else if (focused instanceof ButtonBase button && !button.isDisabled()) button.fire();
                event.consume();
            }
        });
        workspaceButton.requestFocus();
    }

    void hide() {
        popup.hide();
        gesture.cancel();
        active = false;
        surface.setCursor(Cursor.DEFAULT);
    }

    @Override
    public void close() {
        hide();
        surface.removeEventFilter(MouseEvent.ANY, mouse);
        surface.sceneProperty().removeListener(sceneChanged);
        if (surface.getScene() != null) surface.getScene().windowProperty().removeListener(windowChanged);
        observeWindow(null);
    }
}
