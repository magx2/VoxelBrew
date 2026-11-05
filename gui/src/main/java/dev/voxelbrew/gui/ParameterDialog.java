package dev.voxelbrew.gui;

import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.engine.parameters.*;
import java.util.*;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.*;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.*;
import javafx.stage.Window;

/**
 * One cancellable parameter/configuration transaction, usable from the workbench or sketch draft.
 */
final class ParameterDialog {
    private ParameterSet draft;
    private final List<Feature> features;
    private final TableView<ParameterSet.Parameter> parameters = new TableView<>();
    private final TableView<String> configurations = new TableView<>();
    private final TableView<FeatureDimensions.Dimension> dimensions = new TableView<>();
    private final TextField name = field("parameter-name"),
            expression = field("parameter-expression"),
            comment = field("parameter-comment"),
            configName = field("configuration-name"),
            override = field("configuration-expression"),
            binding = field("parameter-binding-expression");
    private final ComboBox<ParameterUnit> unit = new ComboBox<>();
    private final ComboBox<ParameterSet.Parameter> overrideParameter = new ComboBox<>();
    private final Label error = new Label(), active = new Label();
    private boolean refreshing;
    private String selectedParameter;

    static Optional<ParameterSet> show(Window owner, List<Feature> features, ParameterSet initial) {
        var editor = new ParameterDialog(features, initial);
        var dialog = new Dialog<ParameterSet>();
        dialog.setTitle("Parameters and configurations");
        dialog.getDialogPane().setContent(editor.content());
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.APPLY, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.APPLY).setId("parameters-apply");
        dialog.getDialogPane().lookupButton(ButtonType.CANCEL).setId("parameters-cancel");
        dialog.getDialogPane()
                .lookupButton(ButtonType.APPLY)
                .addEventFilter(
                        javafx.event.ActionEvent.ACTION,
                        e -> {
                            try {
                                editor.draft.validate();
                            } catch (RuntimeException failure) {
                                editor.error.setText(failure.getMessage());
                                e.consume();
                            }
                        });
        dialog.setResultConverter(b -> b == ButtonType.APPLY ? editor.draft : null);
        return Dialogs.show(dialog, owner);
    }

    private ParameterDialog(List<Feature> features, ParameterSet initial) {
        this.features = List.copyOf(features);
        draft = initial;
    }

    private static TextField field(String id) {
        var field = new TextField();
        field.setId(id);
        return field;
    }

    private static <T> TableColumn<T, String> column(
            String title, double width, Function<T, String> value) {
        var c = new TableColumn<T, String>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(v -> new ReadOnlyStringWrapper(value.apply(v.getValue())));
        return c;
    }

    private Button button(String label, String id, Runnable action) {
        var b = new Button(label);
        b.setId(id);
        b.setOnAction(e -> attempt(action));
        return b;
    }

    private void attempt(Runnable action) {
        try {
            action.run();
            error.setText("");
        } catch (RuntimeException failure) {
            error.setText(failure.getMessage());
        }
    }

    private void update(ParameterSet next) {
        FeatureDimensions.validateBindings(features, next);
        draft = next;
        refresh();
    }

    private Pane content() {
        parameters.setId("parameter-table");
        parameters.setPrefHeight(175);
        parameters.getColumns().add(column("Name", 110, ParameterSet.Parameter::name));
        parameters.getColumns().add(column("Unit", 65, p -> p.unit().toString()));
        parameters
                .getColumns()
                .add(column("Expression", 180, p -> draft.expression(p, draft.active())));
        parameters
                .getColumns()
                .add(
                        column(
                                "Value",
                                90,
                                p -> {
                                    try {
                                        return String.format(
                                                Locale.ROOT,
                                                "%.6g",
                                                draft.values().get(p.name()).value()
                                                        / p.unit().scale());
                                    } catch (RuntimeException failure) {
                                        return "Error";
                                    }
                                }));
        parameters.getColumns().add(column("Comment", 130, ParameterSet.Parameter::comment));
        parameters.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        parameters
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, p) -> {
                            if (refreshing || p == null) return;
                            selectedParameter = p.id();
                            name.setText(p.name());
                            unit.setValue(p.unit());
                            expression.setText(draft.expression(p, draft.active()));
                            comment.setText(p.comment());
                        });
        unit.setId("parameter-unit");
        unit.getItems().setAll(ParameterUnit.values());
        unit.setValue(ParameterUnit.MM);
        var fields = new GridPane();
        fields.setHgap(8);
        fields.setVgap(5);
        fields.addRow(0, new Label("Name"), name, new Label("Unit"), unit);
        fields.addRow(1, new Label("Expression"), expression, new Label("Comment"), comment);
        var parameterButtons =
                new FlowPane(
                        6,
                        6,
                        button(
                                "Add / update",
                                "parameter-save",
                                () -> {
                                    ParameterSet next = draft;
                                    if (selectedParameter != null
                                            && !next.parameter(selectedParameter)
                                                    .name()
                                                    .equals(name.getText().strip()))
                                        next =
                                                next.rename(
                                                        selectedParameter, name.getText().strip());
                                    update(
                                            next.put(
                                                    name.getText().strip(),
                                                    unit.getValue(),
                                                    expression.getText().strip(),
                                                    comment.getText()));
                                }),
                        button(
                                "New",
                                "parameter-new",
                                () -> {
                                    selectedParameter = null;
                                    parameters.getSelectionModel().clearSelection();
                                    name.clear();
                                    expression.clear();
                                    comment.clear();
                                    name.requestFocus();
                                }),
                        button(
                                "Remove",
                                "parameter-remove",
                                () -> {
                                    update(draft.remove(name.getText().strip()));
                                    selectedParameter = null;
                                }));
        var parameterPane =
                new VBox(
                        7,
                        new Label(
                                "Expressions use names, arithmetic and units. Edits affect the active configuration."),
                        parameters,
                        fields,
                        parameterButtons);

        configurations.setId("configuration-table");
        configurations.setPrefHeight(175);
        configurations.setEditable(true);
        configurations
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, id) -> {
                            if (refreshing || id == null) return;
                            configName.setText(configLabel(id));
                            updateOverride();
                        });
        overrideParameter.setId("configuration-parameter");
        overrideParameter.setPrefWidth(145);
        overrideParameter
                .valueProperty()
                .addListener(
                        (o, a, p) -> {
                            if (!refreshing) updateOverride();
                        });
        var configButtons =
                new FlowPane(
                        6,
                        6,
                        new Label("Name"),
                        configName,
                        button(
                                "Add",
                                "configuration-add",
                                () ->
                                        update(
                                                draft.addConfiguration(
                                                        configName.getText().strip(), false))),
                        button(
                                "Duplicate active",
                                "configuration-copy",
                                () ->
                                        update(
                                                draft.addConfiguration(
                                                        configName.getText().strip(), true))),
                        button(
                                "Rename",
                                "configuration-rename",
                                () ->
                                        update(
                                                draft.renameConfiguration(
                                                        selectedConfig(),
                                                        configName.getText().strip()))),
                        button(
                                "Remove",
                                "configuration-remove",
                                () -> update(draft.removeConfiguration(selectedConfig()))),
                        button(
                                "Activate selected",
                                "configuration-activate",
                                () -> update(draft.select(selectedConfig()))));
        var overrides =
                new FlowPane(
                        6,
                        6,
                        overrideParameter,
                        override,
                        button(
                                "Set override",
                                "configuration-set",
                                () ->
                                        update(
                                                draft.override(
                                                        requiredParameter().id(),
                                                        selectedConfig(),
                                                        override.getText()))),
                        button(
                                "Inherit Default",
                                "configuration-inherit",
                                () ->
                                        update(
                                                draft.override(
                                                        requiredParameter().id(),
                                                        selectedConfig(),
                                                        ""))));
        var configurationPane =
                new VBox(
                        7,
                        new Label(
                                "Rows are variants. Double-click a cell to edit its expression; blank inherits Default."),
                        configurations,
                        configButtons,
                        overrides);

        dimensions.setId("parameter-dimensions");
        dimensions.setPrefHeight(205);
        dimensions.getColumns().add(column("Feature / dimension", 280, d -> d.target().toString()));
        dimensions.getColumns().add(column("Unit", 60, d -> d.unit().toString()));
        dimensions.getColumns().add(column("Expression", 220, d -> draft.binding(d.target())));
        dimensions
                .getItems()
                .setAll(
                        features.stream()
                                .flatMap(f -> FeatureDimensions.list(f).stream())
                                .toList());
        dimensions
                .getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, d) -> {
                            if (d != null) binding.setText(draft.binding(d.target()));
                        });
        var dimensionsPane =
                new VBox(
                        7,
                        new Label(
                                "Bind driving sketch dimensions, extrusion distances and offset planes to parameters."),
                        dimensions,
                        new Label("Expression (blank restores the stored numeric value)"),
                        binding,
                        button(
                                "Bind dimension",
                                "parameter-bind",
                                () -> {
                                    var chosen = dimensions.getSelectionModel().getSelectedItem();
                                    if (chosen == null)
                                        throw new IllegalArgumentException("Select a dimension");
                                    var doc =
                                            new dev.voxelbrew.engine.model.CadDocument(
                                                    "parameter-editor",
                                                    features,
                                                    features.size(),
                                                    dev.voxelbrew.engine.printing.PrintSetup
                                                            .empty(),
                                                    draft);
                                    update(
                                            FeatureDimensions.bind(
                                                    doc, chosen.target(), binding.getText()));
                                }));
        var tabs =
                new TabPane(
                        new Tab("Parameters", parameterPane),
                        new Tab("Configurations", configurationPane),
                        new Tab("Dimensions", dimensionsPane));
        tabs.setId("parameters-tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.setPrefHeight(370);
        error.setWrapText(true);
        error.getStyleClass().add("error");
        error.setId("parameters-error");
        active.setId("parameters-active");
        var result = new VBox(8, active, tabs, error);
        refresh();
        return result;
    }

    private String selectedConfig() {
        String id = configurations.getSelectionModel().getSelectedItem();
        if (id == null) throw new IllegalArgumentException("Select a configuration row");
        return id;
    }

    private String configLabel(String id) {
        return id.isEmpty()
                ? "Default"
                : draft.configurations().stream()
                        .filter(c -> c.id().equals(id))
                        .findFirst()
                        .orElseThrow()
                        .name();
    }

    private ParameterSet.Parameter requiredParameter() {
        var p = overrideParameter.getValue();
        if (p == null) throw new IllegalArgumentException("Choose a parameter");
        return p;
    }

    private void updateOverride() {
        var p = overrideParameter.getValue();
        var id = configurations.getSelectionModel().getSelectedItem();
        if (p != null && id != null) override.setText(draft.expression(p, id));
    }

    private void refresh() {
        refreshing = true;
        try {
            var config = configurations.getSelectionModel().getSelectedItem();
            var chosen = overrideParameter.getValue();
            parameters.getItems().setAll(draft.parameters());
            if (selectedParameter != null)
                parameters
                        .getSelectionModel()
                        .select(
                                draft.parameters().stream()
                                        .filter(p -> p.id().equals(selectedParameter))
                                        .findFirst()
                                        .orElse(null));
            overrideParameter.getItems().setAll(draft.parameters());
            overrideParameter.setValue(
                    chosen == null
                            ? draft.parameters().stream().findFirst().orElse(null)
                            : draft.parameters().stream()
                                    .filter(p -> p.id().equals(chosen.id()))
                                    .findFirst()
                                    .orElse(null));
            configurations.getColumns().clear();
            configurations
                    .getColumns()
                    .add(
                            column(
                                    "Configuration",
                                    160,
                                    id ->
                                            (draft.active().equals(id) ? "● " : "")
                                                    + configLabel(id)));
            for (var p : draft.parameters()) {
                var c =
                        ParameterDialog.<String>column(
                                p.name() + " (" + p.unit() + ")",
                                130,
                                id -> draft.expression(p, id));
                c.setCellFactory(TextFieldTableCell.forTableColumn());
                c.setOnEditCommit(
                        event ->
                                attempt(
                                        () ->
                                                update(
                                                        draft.override(
                                                                p.id(),
                                                                event.getRowValue(),
                                                                event.getNewValue()))));
                configurations.getColumns().add(c);
            }
            configurations.getItems().setAll("");
            draft.configurations().forEach(c -> configurations.getItems().add(c.id()));
            configurations
                    .getSelectionModel()
                    .select(
                            config != null && configurations.getItems().contains(config)
                                    ? config
                                    : draft.active());
            active.setText(
                    "Active configuration: "
                            + draft.activeName()
                            + " · Apply commits all changes; Cancel discards them.");
            dimensions.refresh();
        } finally {
            refreshing = false;
        }
        updateOverride();
    }
}
