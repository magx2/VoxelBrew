package dev.voxelbrew.gui;

import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.stage.WindowEvent;

/** Window-local modal controls keep keyboard focus within the document on WSLg. */
final class Dialogs {
    private Dialogs() {}

    /** Give the action its full translated label width; the editable path takes the remainder. */
    static HBox pathRow(TextField path, Button browse) {
        path.setMinWidth(0);
        path.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(path, Priority.ALWAYS);
        browse.setMinWidth(Region.USE_PREF_SIZE);
        var row = new HBox(8, path, browse);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    @SuppressWarnings("unchecked") // Dialog's default result type is ButtonType, matching Alert.
    static <T> Optional<T> show(
            Dialog<T> dialog, Window owner, dev.voxelbrew.util.i18n.Translations translations) {
        var scene = owner.getScene();
        var content = WindowFrame.content(scene);
        var previousFocus = scene.getFocusOwner();
        boolean disabled = content.isDisable();
        var previousClose = owner.getOnCloseRequest();
        var pane = dialog.getDialogPane();
        var converter = dialog.getResultConverter();
        var buttons = new LinkedHashMap<ButtonType, ButtonBase>();
        for (var type : pane.getButtonTypes()) {
            buttons.put(type, (ButtonBase) pane.lookupButton(type));
            String key =
                    type == ButtonType.OK
                            ? "dialog.ok"
                            : type == ButtonType.CANCEL
                                    ? "action.cancel"
                                    : type == ButtonType.APPLY
                                            ? "dialog.apply"
                                            : type == ButtonType.CLOSE
                                                    ? "dialog.close"
                                                    : type == ButtonType.YES
                                                            ? "dialog.yes"
                                                            : type == ButtonType.NO
                                                                    ? "dialog.no"
                                                                    : null;
            if (key != null) buttons.get(type).setText(translations.text(key));
            buttons.get(type).setMinWidth(Region.USE_PREF_SIZE);
            UiHelp.dialogButton(buttons.get(type), type, translations);
        }
        Node body = pane.getContent();
        pane.setContent(null);
        pane.getButtonTypes().clear();
        if (body == null) {
            var label = new Label(pane.getContentText());
            label.setWrapText(true);
            body = label;
        }
        var title =
                new Label(pane.getHeaderText() == null ? dialog.getTitle() : pane.getHeaderText());
        title.getStyleClass().add("selection-title");
        var actions = new HBox(10);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.getChildren().addAll(buttons.values());
        var panel = new VBox(16, title, body, actions);
        panel.getStyleClass().add("dialog-overlay");
        panel.getStylesheets().setAll(content.getStylesheets());
        panel.getStyleClass().addAll(content.getStyleClass());
        panel.setPrefWidth(Math.min(650, scene.getWidth() - 64));
        panel.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        var shade = new Region();
        shade.setStyle("-fx-background-color: rgba(0,0,0,0.38);");
        var host = new StackPane();
        WindowFrame.content(scene, host);
        host.getChildren().addAll(content, shade, panel);
        content.setDisable(true);

        Object loop = new Object();
        boolean[] completed = {false};
        Consumer<ButtonType> finish =
                button -> {
                    if (completed[0]) return;
                    T result =
                            button == null
                                    ? null
                                    : converter == null ? (T) button : converter.call(button);
                    completed[0] = true;
                    Platform.exitNestedEventLoop(loop, result);
                };
        ButtonBase cancel = null, initial = null;
        for (var entry : buttons.entrySet()) {
            var type = entry.getKey();
            var button = entry.getValue();
            // Existing action filters still validate inputs before this handler runs.
            button.setOnAction(e -> finish.accept(type));
            if (type.getButtonData().isDefaultButton()) initial = button;
            if (type.getButtonData().isCancelButton()
                    || type.getButtonData() == ButtonBar.ButtonData.NO) cancel = button;
        }
        ButtonBase cancelButton = cancel;
        EventHandler<KeyEvent> escape =
                e -> {
                    if (e.getCode() == KeyCode.ESCAPE
                            && cancelButton != null
                            && WindowFrame.content(scene) == host) {
                        e.consume();
                        cancelButton.fire();
                    }
                };
        // MenuBarSkin retains a scene-level Escape filter even when its workbench is
        // disabled behind this overlay. Only the topmost modal owns this window filter.
        owner.addEventFilter(KeyEvent.KEY_PRESSED, escape);
        owner.setOnCloseRequest(
                e -> {
                    e.consume();
                    if (cancelButton != null) cancelButton.fire();
                });
        EventHandler<WindowEvent> hidden = e -> finish.accept(null);
        owner.addEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
        Node initialFocus = initial != null ? initial : cancel != null ? cancel : panel;
        initialFocus.requestFocus();
        // A newly attached overlay may not yet be focusable until its first scene pulse.
        Platform.runLater(
                () -> {
                    if (!completed[0] && !panel.isDisabled()) initialFocus.requestFocus();
                });
        try {
            return Optional.ofNullable((T) Platform.enterNestedEventLoop(loop));
        } finally {
            owner.removeEventHandler(WindowEvent.WINDOW_HIDDEN, hidden);
            owner.setOnCloseRequest(previousClose);
            owner.removeEventFilter(KeyEvent.KEY_PRESSED, escape);
            host.getChildren().clear();
            WindowFrame.content(scene, content);
            content.setDisable(disabled);
            if (previousFocus != null) previousFocus.requestFocus();
        }
    }
}
