package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.Expressions;
import java.math.BigDecimal;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/** Edits a numeric literal in place; the field's normal acceptance still applies. */
final class NumericFieldDrag {
    private final TextField field;
    private String original;
    private BigDecimal startValue;
    private double startX;

    NumericFieldDrag(TextField field) {
        this.field = field;
        field.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            original = null;
            if (event.getButton() != MouseButton.MIDDLE || !editable() || !Expressions.isNumber(field.getText()))
                return;
            try {
                startValue = new BigDecimal(field.getText().strip());
                if (!Double.isFinite(startValue.doubleValue())) return;
            } catch (NumberFormatException invalid) {
                return;
            }
            original = field.getText();
            startX = event.getScreenX();
            field.requestFocus();
            event.consume();
        });
        field.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (original == null || !event.isMiddleButtonDown()) return;
            if (!editable()) {
                original = null;
                return;
            }
            // One unit per ten screen pixels, calculated from the press to avoid accumulated drift.
            double pixels = Math.rint(event.getScreenX() - startX);
            if (Double.isFinite(pixels)) {
                var value = startValue.add(BigDecimal.valueOf(pixels).movePointLeft(1));
                if (Double.isFinite(value.doubleValue())) {
                    field.setText(
                            pixels == 0 ? original : value.stripTrailingZeros().toPlainString());
                }
            }
            event.consume();
        });
        field.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (original == null || event.getButton() != MouseButton.MIDDLE) return;
            original = null;
            event.consume();
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (original == null || event.getCode() != KeyCode.ESCAPE) return;
            String restore = original;
            original = null;
            field.setText(restore);
            event.consume();
        });
        field.focusedProperty().addListener((o, before, focused) -> {
            if (!focused) original = null;
        });
        field.sceneProperty().addListener(o -> original = null);
    }

    private boolean editable() {
        return field.isEditable()
                && !field.isDisabled()
                && !field.textProperty().isBound();
    }
}
