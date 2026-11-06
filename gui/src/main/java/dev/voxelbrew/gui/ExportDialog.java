package dev.voxelbrew.gui;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.api.ExportOption;
import dev.voxelbrew.exporter.api.ExporterDescriptor;
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

    static void show(
            Window owner,
            DocumentController controller,
            ExporterRegistry registry,
            dev.voxelbrew.util.i18n.Translations translations) {
        java.util.function.Supplier<ParameterSet> parameters =
                () -> controller.snapshot().document().parameters();
        var dialog = new Dialog<Void>();
        dialog.setTitle(translations.text("gui.export_model"));
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
        UiText.choices(
                body,
                value ->
                        value.equals("All bodies") ? translations.text("export.allBodies") : value);
        body.getItems().add("All bodies");
        controller.build().bodies().forEach(b -> body.getItems().add(b.id()));
        body.setValue(body.getItems().size() == 2 ? body.getItems().get(1) : "All bodies");
        var space = new ComboBox<ExportSelection.Space>();
        space.setId("export-space");
        UiText.enums(space, translations, "export.space.");
        space.getItems().setAll(ExportSelection.Space.values());
        space.setValue(ExportSelection.Space.DESIGN);
        var contents = new ComboBox<ExportSelection.Contents>();
        contents.setId("export-contents");
        UiText.enums(contents, translations, "export.contents.");
        contents.getItems().setAll(ExportSelection.Contents.values());
        contents.setValue(ExportSelection.Contents.MODEL);
        var instance = new ComboBox<String>();
        instance.setId("export-instance");
        UiText.choices(
                instance,
                value ->
                        value.equals("All instances")
                                ? translations.text("export.allInstances")
                                : value);
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
                        contents.setValue(ExportSelection.Contents.MODEL);
                    }
                });
        var path = FormulaField.text("", parameters).localized(translations);
        path.setId("export-path");
        path.setPrefColumnCount(40);
        var browse = new Button(translations.text("gui.browse"));
        browse.setId("file-browse");
        UiHelp.install(browse, translations);
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
        var chord =
                FormulaField.number("0.02", parameters, ParameterUnit.MM).localized(translations);
        chord.setId("export-chord");
        var angle = FormulaField.number("5", parameters, ParameterUnit.DEG).localized(translations);
        angle.setId("export-angle");
        var overwrite = new CheckBox(translations.text("gui.replace_existing_file"));
        overwrite.setId("export-overwrite");
        UiHelp.install(overwrite, translations);
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
                                if (editor instanceof FormulaField field)
                                    field.localized(translations);
                                editor.setId("export-option-" + option.key());
                                if (editor instanceof CheckBox check)
                                    UiHelp.describe(
                                            check,
                                            translations.text(
                                                    "help.export-option", option.label()));
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
                        new Label(translations.text("gui.format")),
                        format,
                        new Label(translations.text("gui.body")),
                        body,
                        new Label(translations.text("gui.coordinate_space_millimeters")),
                        space,
                        new Label(translations.text("gui.build_instance")),
                        instance,
                        new Label(translations.text("gui.contents_model_only_by_default")),
                        contents,
                        new Label(translations.text("gui.destination")),
                        Dialogs.pathRow(path, browse),
                        new Label(translations.text("gui.chordal_tolerance_mm")),
                        chord,
                        new Label(translations.text("gui.angular_tolerance_degrees")),
                        angle,
                        options,
                        overwrite,
                        error);
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(500);
        scroll.setPrefViewportHeight(Math.min(550, owner.getHeight() - 180));
        dialog.getDialogPane().setContent(scroll);
        var export = new ButtonType(translations.text("gui.export"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(export, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(export);
        button.setId("export-confirm");
        button.addEventFilter(
                ActionEvent.ACTION,
                e -> {
                    try {
                        var descriptor =
                                Objects.requireNonNull(
                                        format.getValue(),
                                        translations.text("gui.choose_an_exporter"));
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
                            throw new IllegalArgumentException(
                                    translations.text("gui.choose_a_destination"));
                        if (space.getValue() == ExportSelection.Space.DESIGN
                                && !descriptor.multipleInstances()
                                && body.getValue().equals("All bodies")
                                && controller.build().bodies().size() > 1)
                            throw new IllegalArgumentException(
                                    translations.text("gui.choose_one_body_for_this_exporter"));
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
        Dialogs.show(dialog, owner, translations);
    }
}
