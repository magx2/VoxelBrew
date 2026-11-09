package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.importer.api.*;
import dev.voxelbrew.importer.api.ImporterCatalog;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.*;
import java.util.*;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.*;

/** Descriptor-driven inputs; provider code runs later on the document controller's file worker. */
final class ImportDialog {
    private ImportDialog() {}

    static Optional<ImporterDescriptor> choose(Window owner, ImporterCatalog registry, Translations translations) {
        var dialog = new Dialog<ImporterDescriptor>();
        dialog.setTitle(translations.text("import.title"));
        var format = new ComboBox<ImporterDescriptor>();
        format.setId("import-format");
        format.getItems()
                .setAll(registry.descriptors().stream()
                        .filter(d -> d.resultKind() == ImportResult.Kind.DOCUMENT)
                        .toList());
        UiText.choices(format, ImporterDescriptor::name);
        format.getSelectionModel().selectFirst();
        var diagnostics = new Label(String.join("\n", registry.diagnostics()));
        diagnostics.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(8, new Label(translations.text("gui.format")), format, diagnostics));
        var confirm = new ButtonType(translations.text("action.import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("import-confirm");
        button.disableProperty().bind(format.valueProperty().isNull());
        dialog.setResultConverter(selected -> selected == confirm ? format.getValue() : null);
        return Dialogs.show(dialog, owner, translations);
    }

    static Optional<FileImportDialog.Source> options(
            Window owner, FileImportDialog request, ParameterSet parameters, Translations translations) {
        var dialog = new Dialog<FileImportDialog.Source>();
        dialog.setTitle(request.title());
        dialog.setResizable(true);
        var path = FormulaField.text("", () -> parameters).localized(translations);
        path.setId("import-path");
        path.setPrefColumnCount(40);
        var browse = new Button(translations.text("gui.browse"));
        browse.setId("file-browse");
        UiHelp.install(browse, translations);
        browse.setOnAction(e -> {
            var chooser = new FileChooser();
            chooser.getExtensionFilters()
                    .add(new FileChooser.ExtensionFilter(
                            request.title(),
                            request.extensions().stream().map(ext -> "*." + ext).toList()));
            var selected = chooser.showOpenDialog(owner);
            if (selected != null) path.setText(selected.toString());
        });
        var options = new VBox(8);
        Map<String, Control> editors = new LinkedHashMap<>();
        for (var option : request.options()) {
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
                editor = (option.type() == ImportOption.Type.STRING
                                ? FormulaField.text(option.defaultValue(), () -> parameters)
                                : FormulaField.number(option.defaultValue(), () -> parameters, ParameterUnit.NUMBER))
                        .localized(translations);
            }
            editor.setId("import-option-" + option.key());
            UiHelp.describe(editor, translations.text("help.import-option", option.label()));
            editors.put(option.key(), editor);
            options.getChildren().addAll(new Label(option.label()), editor);
        }
        var note = new Label(translations.text("import.note"));
        note.setWrapText(true);
        var error = new Label();
        error.setId("import-error");
        error.getStyleClass().add("error");
        error.setWrapText(true);
        var content = new VBox(
                8, note, new Label(translations.text("import.source")), Dialogs.pathRow(path, browse), options, error);
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        scroll.setPrefViewportWidth(500);
        scroll.setPrefViewportHeight(Math.min(400, owner.getHeight() - 180));
        dialog.getDialogPane().setContent(scroll);
        var confirm = new ButtonType(translations.text("action.import"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("import-confirm");
        FileImportDialog.Source[] selected = new FileImportDialog.Source[1];
        button.addEventFilter(ActionEvent.ACTION, e -> {
            try {
                if (path.evaluatedText().isBlank())
                    throw new IllegalArgumentException(translations.text("gui.choose_a_file_path"));
                var source = Path.of(path.evaluatedText()).toAbsolutePath();
                if (!Files.isRegularFile(source))
                    throw new IllegalArgumentException(translations.text("import.missing"));
                var values = new LinkedHashMap<String, String>();
                for (var option : request.options()) {
                    var editor = editors.get(option.key());
                    String value = editor instanceof FormulaField field
                            ? field.evaluatedText()
                            : editor instanceof CheckBox check
                                    ? Boolean.toString(check.isSelected())
                                    : Objects.toString(((ComboBox<?>) editor).getValue(), "");
                    option.validate(value);
                    values.put(option.key(), value);
                }
                selected[0] = request.settings(source, values);
            } catch (RuntimeException failure) {
                error.setText(Objects.toString(failure.getMessage(), failure.toString()));
                e.consume();
            }
        });
        dialog.setResultConverter(b -> b == confirm ? selected[0] : null);
        return Dialogs.show(dialog, owner, translations);
    }
}
