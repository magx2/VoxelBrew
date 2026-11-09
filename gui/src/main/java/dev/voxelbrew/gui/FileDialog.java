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
            dev.voxelbrew.util.i18n.Translations translations,
            dev.voxelbrew.util.config.UserConfiguration configuration) {
        return choose(owner, save, current, parameters, translations, configuration, "vxb", null);
    }

    static Optional<Path> choose(
            Window owner,
            boolean save,
            Path current,
            ParameterSet parameters,
            dev.voxelbrew.util.i18n.Translations translations,
            dev.voxelbrew.util.config.UserConfiguration configuration,
            String extension,
            String title) {
        var dialog = new Dialog<Path>();
        dialog.setTitle(
                save ? translations.text("gui.save_editable_design") : translations.text("gui.open_editable_design"));
        if (title != null && !extension.equals("vxb")) dialog.setTitle(title);
        Path initial = current;
        if (initial == null) {
            try {
                initial = save
                        ? configuration.defaultSaveFolder().resolve("Untitled." + extension)
                        : Path.of(System.getProperty("user.home"), "Untitled.vxb");
            } catch (java.io.IOException | IllegalArgumentException failure) {
                Dialogs.show(
                        new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK), owner, translations);
                return Optional.empty();
            }
        }
        var path = FormulaField.text(initial.toString(), () -> parameters).localized(translations);
        path.setId("file-path");
        path.setPrefColumnCount(48);
        var browse = new Button(translations.text("gui.browse"));
        browse.setId("file-browse");
        UiHelp.install(browse, translations);
        browse.setOnAction(e -> {
            var chooser = new FileChooser();
            chooser.setTitle(dialog.getTitle());
            chooser.getExtensionFilters()
                    .add(new FileChooser.ExtensionFilter("VoxelBrew (*." + extension + ")", "*." + extension));
            try {
                Path selected = Path.of(path.evaluatedText()).toAbsolutePath();
                Path parent = selected.getParent();
                if (save) java.nio.file.Files.createDirectories(parent);
                if (java.nio.file.Files.isDirectory(parent)) chooser.setInitialDirectory(parent.toFile());
                chooser.setInitialFileName(selected.getFileName().toString());
            } catch (java.io.IOException | IllegalArgumentException failure) {
                Dialogs.show(
                        new Alert(Alert.AlertType.ERROR, failure.getMessage(), ButtonType.OK), owner, translations);
                return;
            }
            var file = save ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
            if (file != null) path.setText(file.toString());
        });
        var error = new Label();
        error.getStyleClass().add("error");
        var box = new VBox(
                10, new Label(translations.text("gui.editable_design_location")), Dialogs.pathRow(path, browse), error);
        dialog.getDialogPane().setContent(box);
        var confirm = new ButtonType(
                save ? translations.text("action.save") : translations.text("gui.open"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("file-confirm");
        button.addEventFilter(ActionEvent.ACTION, e -> {
            try {
                if (path.evaluatedText().isBlank())
                    throw new IllegalArgumentException(translations.text("gui.choose_a_file_path"));
                Path selected = Path.of(path.evaluatedText()).toAbsolutePath();
                if (save) java.nio.file.Files.createDirectories(selected.getParent());
            } catch (java.io.IOException | IllegalArgumentException failure) {
                error.setText(failure.getMessage());
                e.consume();
            }
        });
        dialog.setResultConverter(
                b -> b == confirm ? Path.of(path.evaluatedText()).toAbsolutePath() : null);
        return Dialogs.show(dialog, owner, translations);
    }
}
