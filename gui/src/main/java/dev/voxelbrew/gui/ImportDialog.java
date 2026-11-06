package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.importer.ImporterRegistry;
import dev.voxelbrew.importer.api.*;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.*;
import java.util.*;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Descriptor-driven inputs; provider code runs later on the document controller's file worker. */
final class ImportDialog {
    record Request(String format, Path source, Map<String, String> options) {
        Request {
            options = Map.copyOf(options);
        }
    }

    private ImportDialog() {}

    static Optional<Request> choose(
            Window owner,
            ImporterRegistry registry,
            ParameterSet parameters,
            Translations translations) {
        var dialog = new Dialog<Request>();
        dialog.setTitle(translations.text("import.title"));
        dialog.setResizable(true);
        var format = new ComboBox<ImporterDescriptor>();
        format.setId("import-format");
        format.getItems()
                .setAll(
                        registry.descriptors().stream()
                                .filter(d -> d.resultKind() == ImportResult.Kind.DOCUMENT)
                                .toList());
        UiText.choices(format, ImporterDescriptor::name);
        var path = FormulaField.text("", () -> parameters).localized(translations);
        path.setId("import-path");
        path.setPrefColumnCount(40);
        var browse = new Button(translations.text("gui.browse"));
        browse.setId("file-browse");
        UiHelp.install(browse, translations);
        browse.setOnAction(
                e -> {
                    var chooser = new FileChooser();
                    var descriptor = format.getValue();
                    if (descriptor != null)
                        chooser.getExtensionFilters()
                                .add(
                                        new FileChooser.ExtensionFilter(
                                                descriptor.name(),
                                                descriptor.extensions().stream()
                                                        .map(ext -> "*." + ext)
                                                        .toList()));
                    var selected = chooser.showOpenDialog(owner);
                    if (selected != null) path.setText(selected.toString());
                });
        var options = new VBox(8);
        Map<String, Control> editors = new LinkedHashMap<>();
        format.valueProperty()
                .addListener(
                        (o, before, descriptor) -> {
                            options.getChildren().clear();
                            editors.clear();
                            if (descriptor == null) return;
                            for (var option : descriptor.options()) {
                                Control editor;
                                if (option.type() == ImportOption.Type.BOOLEAN) {
                                    var check = new CheckBox();
                                    check.setSelected(Boolean.parseBoolean(option.defaultValue()));
                                    editor = check;
                                } else if (option.type() == ImportOption.Type.CHOICE) {
                                    var choices = new ComboBox<String>();
                                    choices.getItems().setAll(option.choices());
                                    choices.setValue(option.defaultValue());
                                    editor = choices;
                                } else {
                                    editor =
                                            (option.type() == ImportOption.Type.STRING
                                                            ? FormulaField.text(
                                                                    option.defaultValue(),
                                                                    () -> parameters)
                                                            : FormulaField.number(
                                                                    option.defaultValue(),
                                                                    () -> parameters,
                                                                    ParameterUnit.NUMBER))
                                                    .localized(translations);
                                }
                                editor.setId("import-option-" + option.key());
                                UiHelp.describe(
                                        editor,
                                        translations.text("help.import-option", option.label()));
                                editors.put(option.key(), editor);
                                options.getChildren().addAll(new Label(option.label()), editor);
                            }
                        });
        format.getSelectionModel().selectFirst();
        var note = new Label(translations.text("import.note"));
        note.setWrapText(true);
        var diagnostic = new Label(String.join("\n", registry.diagnostics()));
        diagnostic.setWrapText(true);
        diagnostic.setId("import-diagnostics");
        var error = new Label();
        error.setId("import-error");
        error.getStyleClass().add("error");
        error.setWrapText(true);
        var content =
                new VBox(
                        8,
                        note,
                        new Label(translations.text("gui.format")),
                        format,
                        new Label(translations.text("import.source")),
                        Dialogs.pathRow(path, browse),
                        options,
                        diagnostic,
                        error);
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(500);
        scroll.setPrefViewportHeight(Math.min(400, owner.getHeight() - 180));
        dialog.getDialogPane().setContent(scroll);
        var confirm =
                new ButtonType(translations.text("action.import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("import-confirm");
        Request[] request = new Request[1];
        button.addEventFilter(
                ActionEvent.ACTION,
                e -> {
                    try {
                        var descriptor =
                                Objects.requireNonNull(
                                        format.getValue(), translations.text("import.choose"));
                        if (path.evaluatedText().isBlank())
                            throw new IllegalArgumentException(
                                    translations.text("gui.choose_a_file_path"));
                        var source = Path.of(path.evaluatedText()).toAbsolutePath();
                        if (!Files.isRegularFile(source))
                            throw new IllegalArgumentException(translations.text("import.missing"));
                        var values = new LinkedHashMap<String, String>();
                        for (var option : descriptor.options()) {
                            var editor = editors.get(option.key());
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
                        request[0] = new Request(descriptor.id(), source, values);
                    } catch (RuntimeException failure) {
                        error.setText(Objects.toString(failure.getMessage(), failure.toString()));
                        e.consume();
                    }
                });
        dialog.setResultConverter(b -> b == confirm ? request[0] : null);
        return Dialogs.show(dialog, owner, translations);
    }
}
