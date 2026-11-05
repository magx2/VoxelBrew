package dev.voxelbrew.gui;

import dev.voxelbrew.engine.export.*;
import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import dev.voxelbrew.exporter.ExporterRegistry;
import java.nio.file.Path;
import java.util.*;
import javafx.event.ActionEvent;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.StringConverter;

final class ExportDialog {
    private ExportDialog() {}

    static void show(Window owner, DocumentController controller, ExporterRegistry registry) {
        java.util.function.Supplier<ParameterSet> parameters =
                () -> controller.snapshot().document().parameters();
        var dialog = new Dialog<Void>();
        dialog.setTitle("Export model");
        dialog.setResizable(true);
        var format = new ComboBox<ExporterDescriptor>();
        format.setId("export-format");
        format.getItems().setAll(registry.descriptors());
        format.setConverter(
                new StringConverter<>() {
                    public String toString(ExporterDescriptor d) {
                        return d == null ? "" : d.name();
                    }

                    public ExporterDescriptor fromString(String s) {
                        throw new UnsupportedOperationException();
                    }
                });
        var body = new ComboBox<String>();
        body.setId("export-body");
        body.getItems().add("All bodies");
        controller.build().bodies().forEach(b -> body.getItems().add(b.id()));
        body.setValue(body.getItems().size() == 2 ? body.getItems().get(1) : "All bodies");
        var space = new ComboBox<ExportSelection.Space>();
        space.setId("export-space");
        space.getItems().setAll(ExportSelection.Space.values());
        space.setValue(ExportSelection.Space.DESIGN);
        var contents = new ComboBox<ManufacturingModel.Selection>();
        contents.setId("export-contents");
        contents.getItems().setAll(ManufacturingModel.Selection.values());
        contents.setValue(ManufacturingModel.Selection.MODEL);
        var instance = new ComboBox<String>();
        instance.setId("export-instance");
        instance.getItems().add("All instances");
        controller
                .snapshot()
                .document()
                .printSetup()
                .instances()
                .forEach(i -> instance.getItems().add(i.id()));
        instance.setValue("All instances");
        instance.setDisable(true);
        contents.setDisable(true);
        space.setOnAction(
                e -> {
                    boolean design = space.getValue() == ExportSelection.Space.DESIGN;
                    instance.setDisable(design);
                    contents.setDisable(design);
                    body.setDisable(!design);
                    if (design) {
                        instance.setValue("All instances");
                        contents.setValue(ManufacturingModel.Selection.MODEL);
                    }
                });
        var path = FormulaField.text("", parameters);
        path.setId("export-path");
        path.setPrefColumnCount(40);
        var browse = new Button("Browse…");
        browse.setOnAction(
                e -> {
                    var chooser = new FileChooser();
                    var d = format.getValue();
                    if (d != null)
                        chooser.getExtensionFilters()
                                .add(
                                        new FileChooser.ExtensionFilter(
                                                d.name(), "*." + d.extensions().getFirst()));
                    var selected = chooser.showSaveDialog(owner);
                    if (selected != null) path.setText(selected.toString());
                });
        var chord = FormulaField.number("0.02", parameters, ParameterUnit.MM);
        chord.setId("export-chord");
        var angle = FormulaField.number("5", parameters, ParameterUnit.DEG);
        angle.setId("export-angle");
        var overwrite = new CheckBox("Replace existing file");
        overwrite.setId("export-overwrite");
        var options = new VBox(8);
        Map<String, Node> editors = new LinkedHashMap<>();
        format.valueProperty()
                .addListener(
                        (o, old, d) -> {
                            options.getChildren().clear();
                            editors.clear();
                            if (d == null) return;
                            if (path.getText().isBlank())
                                path.setText(
                                        Path.of(
                                                        System.getProperty("user.home"),
                                                        "part." + d.extensions().getFirst())
                                                .toString());
                            for (var option : d.options()) {
                                Node editor;
                                if (option.type() == ExportOption.Type.BOOLEAN) {
                                    var check = new CheckBox();
                                    check.setSelected(Boolean.parseBoolean(option.defaultValue()));
                                    editor = check;
                                } else if (option.type() == ExportOption.Type.CHOICE) {
                                    var choices = new ComboBox<String>();
                                    choices.getItems().setAll(option.choices());
                                    choices.setValue(option.defaultValue());
                                    editor = choices;
                                } else
                                    editor =
                                            option.type() == ExportOption.Type.STRING
                                                    ? FormulaField.text(
                                                            option.defaultValue(), parameters)
                                                    : FormulaField.number(
                                                            option.defaultValue(),
                                                            parameters,
                                                            ParameterUnit.NUMBER);
                                editor.setId("export-option-" + option.key());
                                editors.put(option.key(), editor);
                                options.getChildren().addAll(new Label(option.label()), editor);
                            }
                        });
        format.getSelectionModel().selectFirst();
        var error = new Label();
        error.setId("export-error");
        error.getStyleClass().add("error");
        error.setWrapText(true);
        var content =
                new VBox(
                        8,
                        new Label("Format"),
                        format,
                        new Label("Body"),
                        body,
                        new Label("Coordinate space (millimeters)"),
                        space,
                        new Label("Build instance"),
                        instance,
                        new Label("Contents (model-only by default)"),
                        contents,
                        new Label("Destination"),
                        new HBox(8, path, browse),
                        new Label("Chordal tolerance (mm)"),
                        chord,
                        new Label("Angular tolerance (degrees)"),
                        angle,
                        options,
                        overwrite,
                        error);
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(500);
        scroll.setPrefViewportHeight(Math.min(550, owner.getHeight() - 180));
        dialog.getDialogPane().setContent(scroll);
        var export = new ButtonType("Export", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(export);
        button.setId("export-confirm");
        button.addEventFilter(
                ActionEvent.ACTION,
                e -> {
                    try {
                        var descriptor =
                                Objects.requireNonNull(format.getValue(), "Choose an exporter");
                        var values = new LinkedHashMap<String, String>();
                        for (var option : descriptor.options()) {
                            Node editor = editors.get(option.key());
                            String value =
                                    editor instanceof FormulaField field
                                            ? field.evaluatedText()
                                            : editor instanceof CheckBox check
                                                    ? Boolean.toString(check.isSelected())
                                                    : Objects.toString(
                                                            ((ComboBox<?>) editor).getValue(), "");
                            option.validate(value);
                            values.put(option.key(), value);
                        }
                        if (path.evaluatedText().isBlank())
                            throw new IllegalArgumentException("Choose a destination");
                        if (space.getValue() == ExportSelection.Space.DESIGN
                                && !descriptor.multipleInstances()
                                && body.getValue().equals("All bodies")
                                && controller.build().bodies().size() > 1)
                            throw new IllegalArgumentException("Choose one body for this exporter");
                        controller
                                .export(
                                        registry,
                                        descriptor.id(),
                                        new ExportSelection(
                                                space.getValue(),
                                                contents.getValue(),
                                                space.getValue() == ExportSelection.Space.DESIGN
                                                                && !body.getValue()
                                                                        .equals("All bodies")
                                                        ? body.getValue()
                                                        : null,
                                                space.getValue()
                                                                        == ExportSelection.Space
                                                                                .BUILD_PLATE
                                                                && !instance.getValue()
                                                                        .equals("All instances")
                                                        ? instance.getValue()
                                                        : null),
                                        new MeshOptions(chord.value(), angle.value()),
                                        values,
                                        Path.of(path.evaluatedText()),
                                        overwrite.isSelected())
                                .exceptionally(failure -> null);
                    } catch (RuntimeException failure) {
                        error.setText(Objects.toString(failure.getMessage(), failure.toString()));
                        e.consume();
                    }
                });
        Dialogs.show(dialog, owner);
    }
}
