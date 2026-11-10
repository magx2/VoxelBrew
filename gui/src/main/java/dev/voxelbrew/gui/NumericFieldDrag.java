package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.Expressions;
import java.math.BigDecimal;
import java.math.RoundingMode;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

/** Edits a numeric literal in place; the field's normal acceptance still applies. */
final class NumericFieldDrag {
    private static final BigDecimal STEP = new BigDecimal("0.1");
    private final FormulaField field;
    private String original;
    private BigDecimal startValue;
    private double startX;

    NumericFieldDrag(FormulaField field) {
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
                var value = constrain(startValue.add(BigDecimal.valueOf(pixels).multiply(STEP)));
                if (field.bounds().accepts(value.doubleValue())) {
                    field.setText(
                            value.compareTo(startValue) == 0
                                    ? original
                                    : value.stripTrailingZeros().toPlainString());
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

    private BigDecimal constrain(BigDecimal value) {
        var bounds = field.bounds();
        if (Double.isFinite(bounds.min())) {
            var minimum = BigDecimal.valueOf(bounds.min());
            if (!bounds.minInclusive()) minimum = stepInside(minimum, RoundingMode.FLOOR, BigDecimal.ONE);
            value = value.max(minimum);
        }
        if (Double.isFinite(bounds.max())) {
            var maximum = BigDecimal.valueOf(bounds.max());
            if (!bounds.maxInclusive()) maximum = stepInside(maximum, RoundingMode.CEILING, BigDecimal.ONE.negate());
            value = value.min(maximum);
        }
        return value;
    }

    /** Strict limits stop at the nearest drag step inside the range, without inventing tiny epsilon values. */
    private BigDecimal stepInside(BigDecimal limit, RoundingMode rounding, BigDecimal direction) {
        var steps = limit.subtract(startValue).divide(STEP, 0, rounding).add(direction);
        return startValue.add(steps.multiply(STEP));
    }

    private boolean editable() {
        return field.isEditable()
                && !field.isDisabled()
                && !field.textProperty().isBound();
    }
}
