package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.units.Measurements;
import java.util.Objects;
import java.util.function.Supplier;
import javafx.css.PseudoClass;
import javafx.scene.control.*;
import javafx.scene.control.skin.TextFieldSkin;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** A normal editable TextField with explicit formula affordance and non-destructive previews. */
final class FormulaField extends TextField {
    @RequiredArgsConstructor
    enum ExpressionType {
        NUMBER("ƒx", "number-expression"),
        STRING("ƒs", "string-expression");

        private final String mark;
        private final String styleClass;
    }

    private final ExpressionType expressionType;
    private static final PseudoClass FORMULA = PseudoClass.getPseudoClass("formula");
    private static final PseudoClass INVALID = PseudoClass.getPseudoClass("formula-invalid");
    private final Supplier<ParameterSet> parameters;
    private final Supplier<ParameterUnit> unit;
    private final Tooltip preview = UiHelp.tooltip("");
    private final Tooltip syntax = UiHelp.tooltip("");
    private dev.voxelbrew.util.i18n.Translations translations =
            new dev.voxelbrew.util.i18n.Translations(java.util.Locale.ENGLISH);

    private FormulaField(String text, @NonNull Supplier<ParameterSet> parameters, Supplier<ParameterUnit> unit) {
        super(text);
        this.parameters = parameters;
        this.unit = unit;
        expressionType = unit == null ? ExpressionType.STRING : ExpressionType.NUMBER;
        getStyleClass().addAll("formula-field", expressionType.styleClass);
        setTooltip(preview);
        localizeHelp();
        textProperty().addListener((o, a, b) -> refreshFormula());
        focusedProperty().addListener((o, a, b) -> refreshFormula());
        sceneProperty().addListener((o, a, b) -> {
            if (b != null) refreshFormula();
        });
        preview.setOnShowing(e -> refreshFormula());
        new ParameterSuggestions(this, parameters, () -> translations);
    }

    static FormulaField number(String text, Supplier<ParameterSet> parameters, ParameterUnit unit) {
        return number(text, parameters, () -> unit);
    }

    static FormulaField number(String text, Supplier<ParameterSet> parameters, @NonNull Supplier<ParameterUnit> unit) {
        return new FormulaField(text, parameters, unit);
    }

    static FormulaField text(String text, Supplier<ParameterSet> parameters) {
        return new FormulaField(text, parameters, null);
    }

    ExpressionType expressionType() {
        return expressionType;
    }

    FormulaField localized(@NonNull dev.voxelbrew.util.i18n.Translations translations) {
        this.translations = translations;
        localizeHelp();
        refreshFormula();
        return this;
    }

    private void localizeHelp() {
        setPromptText(translations.text(unit == null ? "formula.textPrompt" : "formula.numberPrompt"));
        syntax.setText(translations.text(unit == null ? "formula.textSyntax" : "formula.numberSyntax"));
        setAccessibleHelp(syntax.getText());
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
            throw new IllegalArgumentException(translations.text("gui.expected_a_whole_number_in_the_supported_range"));
        return (int) result;
    }

    /** Evaluated input for one-time settings; getText() always retains the user's formula. */
    String evaluatedText() {
        return unit == null
                ? parameters.get().text(getText())
                : Expressions.format(Measurements.quantity(value(), Measurements.ONE));
    }

    void refreshFormula() {
        boolean formula = Expressions.isFormula(getText())
                || (unit != null && !getText().isBlank() && !Expressions.isNumber(getText()));
        pseudoClassStateChanged(FORMULA, formula);
        try {
            String result = getText().isBlank() ? "" : evaluatedText();
            String suffix = unit == null || unit.get() == ParameterUnit.NUMBER
                    ? ""
                    : " " + unit.get().canonicalUnit();
            preview.setText(expressionType.mark
                    + "  "
                    + (formula
                            ? translations.text("formula.result", result, suffix)
                            : translations.text("gui.supports_formulas_and_parameters")));
            pseudoClassStateChanged(INVALID, false);
        } catch (RuntimeException failure) {
            preview.setText(expressionType.mark + "  " + failure.getMessage());
            pseudoClassStateChanged(INVALID, true);
        }
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new TextFieldSkin(this) {
            private final Label mark = new Label(expressionType.mark);
            private final Button help = new Button("?");

            {
                mark.getStyleClass().add("formula-mark");
                mark.setManaged(false);
                mark.setMouseTransparent(true);
                help.getStyleClass().add("formula-help");
                help.setManaged(false);
                help.setTooltip(syntax);
                help.accessibleTextProperty().bind(syntax.textProperty());
                help.setOnAction(event -> {
                    preview.hide();
                    if (syntax.isShowing()) {
                        syntax.hide();
                    } else {
                        var bounds = help.localToScreen(help.getBoundsInLocal());
                        if (bounds != null) syntax.show(help, bounds.getMinX(), bounds.getMaxY());
                    }
                    event.consume();
                });
                getChildren().addAll(mark, help);
            }

            @Override
            protected void layoutChildren(double x, double y, double width, double height) {
                super.layoutChildren(x, y, width, height);
                mark.resizeRelocate(7, y, 18, height);
                help.resizeRelocate(getWidth() - 25, y + (height - 18) / 2, 18, 18);
            }
        };
    }
}
