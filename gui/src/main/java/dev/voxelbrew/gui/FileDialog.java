package dev.voxelbrew.gui;

import dev.voxelbrew.engine.parameters.ParameterSet;
import java.nio.file.Path;
import java.util.Optional;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Window;

/** Editable path plus native Browse keeps file operations accessible and keyboard friendly. */
final class FileDialog {
    private FileDialog() {}

    static Optional<Path> choose(
            Window owner,
            boolean save,
            Path current,
            ParameterSet parameters,
            dev.voxelbrew.util.i18n.Translations translations) {
        var dialog = new Dialog<Path>();
        dialog.setTitle(
                save
                        ? translations.text("gui.save_editable_design")
                        : translations.text("gui.open_editable_design"));
        var path =
                FormulaField.text(
                                current == null
                                        ? Path.of(System.getProperty("user.home"), "Untitled.jcad")
                                                .toString()
                                        : current.toString(),
                                () -> parameters)
                        .localized(translations);
        path.setId("file-path");
        path.setPrefColumnCount(48);
        var browse = new Button(translations.text("gui.browse"));
        browse.setId("file-browse");
        UiHelp.install(browse, translations);
        browse.setOnAction(
                e -> {
                    var chooser = new FileChooser();
                    chooser.setTitle(dialog.getTitle());
                    chooser.getExtensionFilters()
                            .add(
                                    new FileChooser.ExtensionFilter(
                                            translations.text("gui.voxelbrew_design_jcad"),
                                            "*.jcad"));
                    var file = save ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
                    if (file != null) path.setText(file.toString());
                });
        var error = new Label();
        error.getStyleClass().add("error");
        var box =
                new VBox(
                        10,
                        new Label(translations.text("gui.editable_design_location")),
                        new HBox(8, path, browse),
                        error);
        dialog.getDialogPane().setContent(box);
        var confirm =
                new ButtonType(
                        save ? translations.text("action.save") : translations.text("gui.open"),
                        ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("file-confirm");
        button.addEventFilter(
                ActionEvent.ACTION,
                e -> {
                    try {
                        if (path.evaluatedText().isBlank())
                            throw new IllegalArgumentException(
                                    translations.text("gui.choose_a_file_path"));
                        Path.of(path.evaluatedText());
                    } catch (IllegalArgumentException failure) {
                        error.setText(failure.getMessage());
                        e.consume();
                    }
                });
        dialog.setResultConverter(
                b -> b == confirm ? Path.of(path.evaluatedText()).toAbsolutePath() : null);
        return Dialogs.show(dialog, owner, translations);
    }
}
