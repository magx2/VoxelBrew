package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.units.Measurements;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.css.PseudoClass;
import javafx.scene.control.*;
import javafx.scene.control.skin.TextFieldSkin;

/** A normal editable TextField with explicit formula affordance and non-destructive previews. */
final class FormulaField extends TextField {
    private static final PseudoClass FORMULA = PseudoClass.getPseudoClass("formula");
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("formula-invalid");
    private final Supplier<ParameterSet> parameters;
    private final Supplier<ParameterUnit> unit;
    private final Tooltip preview = UiHelp.tooltip("");
    private dev.voxelbrew.util.i18n.Translations translations =
            new dev.voxelbrew.util.i18n.Translations(java.util.Locale.ENGLISH);

    private FormulaField(
            String text, Supplier<ParameterSet> parameters, Supplier<ParameterUnit> unit) {
        super(text);
        this.parameters = Objects.requireNonNull(parameters);
        this.unit = unit;
        getStyleClass().add("formula-field");
        setTooltip(preview);
        localizeHelp();
        textProperty().addListener((o, a, b) -> refreshFormula());
        focusedProperty().addListener((o, a, b) -> refreshFormula());
        sceneProperty()
                .addListener(
                        (o, a, b) -> {
                            if (b != null) refreshFormula();
                        });
        preview.setOnShowing(e -> refreshFormula());
    }

    static FormulaField number(String text, Supplier<ParameterSet> parameters, ParameterUnit unit) {
        return number(text, parameters, () -> unit);
    }

    static FormulaField number(
            String text, Supplier<ParameterSet> parameters, Supplier<ParameterUnit> unit) {
        return new FormulaField(text, parameters, Objects.requireNonNull(unit));
    }

    static FormulaField text(String text, Supplier<ParameterSet> parameters) {
        return new FormulaField(text, parameters, null);
    }

    FormulaField localized(dev.voxelbrew.util.i18n.Translations translations) {
        this.translations = Objects.requireNonNull(translations);
        localizeHelp();
        refreshFormula();
        return this;
    }

    private void localizeHelp() {
        setPromptText(
                translations.text(unit == null ? "formula.textPrompt" : "formula.numberPrompt"));
        setAccessibleHelp(translations.text("formula.help"));
    }

    static ParameterUnit unit(String label) {
        try {
            return ParameterUnit.parse(label);
        } catch (IllegalArgumentException unsupported) {
            return ParameterUnit.NUMBER;
        }
    }

    double value() {
        if (unit == null) throw new IllegalStateException("This is a text field");
        return parameters.get().value(getText(), Objects.requireNonNull(unit.get()));
    }

    /** Plugin payloads use their declared unit rather than the CAD model's canonical unit. */
    double declaredValue() {
        if (unit == null) throw new IllegalStateException("This is a text field");
        var selected = Objects.requireNonNull(unit.get());
        return selected.displayValue(Measurements.quantity(value(), selected.canonicalUnit()));
    }

    int integer() {
        double result = value();
        if (result != Math.rint(result) || result < Integer.MIN_VALUE || result > Integer.MAX_VALUE)
            throw new IllegalArgumentException(
                    translations.text("gui.expected_a_whole_number_in_the_supported_range"));
        return (int) result;
    }

    /** Evaluated input for one-time settings; getText() always retains the user's formula. */
    String evaluatedText() {
        return unit == null
                ? parameters.get().text(getText())
                : Expressions.format(Measurements.quantity(value(), Measurements.ONE));
    }

    void refreshFormula() {
        boolean formula =
                Expressions.isFormula(getText())
                        || (unit != null
                                && !getText().isBlank()
                                && !Expressions.isNumber(getText()));
        pseudoClassStateChanged(FORMULA, formula);
        try {
            String result = getText().isBlank() ? "" : evaluatedText();
            String suffix =
                    unit == null || unit.get() == ParameterUnit.NUMBER
                            ? ""
                            : " " + unit.get().canonicalUnit();
            preview.setText(
                    "ƒx  "
                            + (formula
                                    ? translations.text("formula.result", result, suffix)
                                    : translations.text("gui.supports_formulas_and_parameters")));
            pseudoClassStateChanged(INVALID, false);
        } catch (RuntimeException failure) {
            preview.setText("ƒx  " + failure.getMessage());
            pseudoClassStateChanged(INVALID, true);
        }
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new TextFieldSkin(this) {
            private final Label mark = new Label("ƒx");

            {
                mark.getStyleClass().add("formula-mark");
                mark.setManaged(false);
                mark.setMouseTransparent(true);
                getChildren().add(mark);
            }

            @Override
            protected void layoutChildren(double x, double y, double width, double height) {
                super.layoutChildren(x, y, width, height);
                mark.resizeRelocate(7, y, 18, height);
            }
        };
    }
}
