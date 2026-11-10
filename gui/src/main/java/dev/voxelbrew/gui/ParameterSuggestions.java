package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.BuiltinParameters;
import dev.voxelbrew.engine.parameters.ParameterSet;
import dev.voxelbrew.util.i18n.Translations;
import java.util.List;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

/** Non-modal completion: typing remains in the field; accepting a name never applies its value. */
final class ParameterSuggestions {
    record Reference(int start, int end, String prefix) {}

    private final FormulaField field;
    private final Supplier<ParameterSet> parameters;
    private final Supplier<Translations> translations;
    private Popup popup;
    private ListView<String> names;
    private VBox content;
    private Label heading, hint, empty;
    private boolean queued, changing, dismissed;

    ParameterSuggestions(FormulaField field, Supplier<ParameterSet> parameters, Supplier<Translations> translations) {
        this.field = field;
        this.parameters = parameters;
        this.translations = translations;
        field.textProperty().addListener(o -> edited());
        field.caretPositionProperty().addListener(o -> edited());
        field.selectionProperty().addListener(o -> edited());
        field.focusedProperty().addListener((o, before, focused) -> {
            if (focused) edited();
            else hide();
        });
        field.disabledProperty().addListener(o -> {
            if (field.isDisabled()) hide();
        });
        field.sceneProperty().addListener(o -> hide());
    }

    private void edited() {
        if (changing) return;
        dismissed = false;
        if (queued) return;
        queued = true;
        Platform.runLater(() -> {
            queued = false;
            refresh();
        });
    }

    private void refresh() {
        if (dismissed
                || !field.isFocused()
                || field.isDisabled()
                || !field.isEditable()
                || field.getScene() == null
                || field.getSelection().getLength() != 0) {
            hide();
            return;
        }
        var reference = reference(field.getText(), field.getCaretPosition());
        var bounds = field.localToScreen(field.getBoundsInLocal());
        if (reference == null || bounds == null || !field.getScene().getWindow().isShowing()) {
            hide();
            return;
        }
        if (popup == null) createPopup();
        var matches = matches(parameters.get(), reference.prefix(), field.expressionType());
        String selected = names.getSelectionModel().getSelectedItem();
        if (!names.getItems().equals(matches)) names.getItems().setAll(matches);
        if (selected != null && matches.contains(selected))
            names.getSelectionModel().select(selected);
        else names.getSelectionModel().selectFirst();
        var t = translations.get();
        heading.setText(t.text("formula.parameters"));
        hint.setText(t.text("formula.completionKeys"));
        empty.setText(t.text("formula.noParameters"));
        names.setAccessibleText(t.text("formula.parameters"));
        names.setAccessibleHelp(t.text("formula.completionKeys"));
        var root = field.getScene().getRoot();
        boolean dark = root.getStyleClass().contains("dark") || root.lookup(".dark") != null;
        content.getStyleClass().removeAll("light", "dark");
        content.getStyleClass().add(dark ? "dark" : "light");
        content.setPrefWidth(Math.max(290, Math.min(420, bounds.getWidth())));
        names.setPrefHeight(Math.max(1, Math.min(7, matches.size())) * 28 + 4);
        if (!popup.isShowing()) {
            field.getTooltip().hide();
            popup.show(field, bounds.getMinX(), bounds.getMaxY() + 3);
        }
    }

    private void createPopup() {
        popup = new Popup();
        popup.setAutoHide(true);
        popup.setConsumeAutoHidingEvents(false);
        popup.setOnAutoHide(e -> dismissed = true);
        heading = new Label();
        heading.getStyleClass().add("eyebrow");
        hint = new Label();
        hint.getStyleClass().add("completion-hint");
        empty = new Label();
        names = new ListView<>();
        names.setId("formula-suggestions");
        names.setFocusTraversable(false);
        names.setFixedCellSize(28);
        names.setPlaceholder(empty);
        names.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean unused) {
                super.updateItem(item, unused);
                setText(unused ? null : item);
                setId(unused ? null : "formula-suggestion-" + item);
            }

            {
                setOnMousePressed(e -> {
                    if (e.getButton() == MouseButton.PRIMARY && !isEmpty()) {
                        e.consume();
                        accept(getItem());
                    }
                });
            }
        });
        content = new VBox(6, heading, names, hint);
        content.getStyleClass().add("parameter-completion");
        content.getStylesheets()
                .add(ParameterSuggestions.class.getResource("workbench.css").toExternalForm());
        popup.getContent().add(content);
        // PopupWindow forwards owner key events here before window-level modal Escape handlers.
        // Leave printable keys untouched so they continue to edit the original TextField.
        popup.getScene().addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.isControlDown() || e.isAltDown() || e.isMetaDown() || e.isShiftDown()) return;
            switch (e.getCode()) {
                case ESCAPE -> {
                    e.consume();
                    dismiss();
                }
                case DOWN, UP -> {
                    if (names.getItems().isEmpty()) return;
                    e.consume();
                    int direction = e.getCode() == javafx.scene.input.KeyCode.DOWN ? 1 : -1;
                    int index = Math.floorMod(
                            names.getSelectionModel().getSelectedIndex() + direction,
                            names.getItems().size());
                    names.getSelectionModel().select(index);
                    names.scrollTo(index);
                }
                case ENTER, TAB -> {
                    var name = names.getSelectionModel().getSelectedItem();
                    if (name == null) {
                        dismiss();
                        return;
                    }
                    e.consume();
                    accept(name);
                }
                default -> {}
            }
        });
    }

    private void accept(String name) {
        var reference = reference(field.getText(), field.getCaretPosition());
        if (reference == null
                || !matches(parameters.get(), reference.prefix(), field.expressionType())
                        .contains(name)) {
            dismiss();
            return;
        }
        changing = true;
        try {
            dismiss();
            field.replaceText(reference.start(), reference.end(), "$" + name);
            field.positionCaret(reference.start() + name.length() + 1);
            field.requestFocus();
        } finally {
            changing = false;
        }
    }

    private void dismiss() {
        dismissed = true;
        hide();
    }

    private void hide() {
        if (popup != null) popup.hide();
    }

    static List<String> matches(ParameterSet parameters, String prefix) {
        return matches(parameters, prefix, FormulaField.ExpressionType.STRING);
    }

    static List<String> matches(ParameterSet parameters, String prefix, FormulaField.ExpressionType type) {
        return parameters.allParameters().stream()
                .map(ParameterSet.Parameter::name)
                .filter(name -> type == FormulaField.ExpressionType.STRING || !BuiltinParameters.isString(name))
                .filter(name -> name.startsWith(prefix))
                .sorted()
                .toList();
    }

    static Reference reference(String text, int caret) {
        if (caret < 0 || caret > text.length()) return null;
        int start = caret;
        while (start > 0 && identifier(text.charAt(start - 1))) start--;
        if (start == 0 || text.charAt(start - 1) != '$') return null;
        if (start < caret && !asciiLetter(text.charAt(start))) return null;
        boolean quoted = false;
        for (int i = 0; i < start - 1; i++) {
            if (text.charAt(i) != '"') continue;
            if (quoted && i + 1 < start - 1 && text.charAt(i + 1) == '"') i++;
            else quoted = !quoted;
        }
        if (quoted) return null;
        int end = caret;
        while (end < text.length() && identifier(text.charAt(end))) end++;
        return new Reference(start - 1, end, text.substring(start, caret));
    }

    private static boolean asciiLetter(char c) {
        return c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z';
    }

    private static boolean identifier(char c) {
        return asciiLetter(c) || c >= '0' && c <= '9' || c == '_' || c == '.';
    }
}
