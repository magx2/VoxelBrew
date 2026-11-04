package dev.voxelbrew.gui;

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

    static Optional<Path> choose(Window owner, boolean save, Path current) {
        var dialog = new Dialog<Path>();
        dialog.setTitle(save ? "Save editable design" : "Open editable design");
        var path =
                new TextField(
                        current == null
                                ? Path.of(System.getProperty("user.home"), "Untitled.jcad")
                                        .toString()
                                : current.toString());
        path.setId("file-path");
        path.setPrefColumnCount(48);
        var browse = new Button("Browse…");
        browse.setOnAction(
                e -> {
                    var chooser = new FileChooser();
                    chooser.setTitle(dialog.getTitle());
                    chooser.getExtensionFilters()
                            .add(
                                    new FileChooser.ExtensionFilter(
                                            "VoxelBrew design (*.jcad)", "*.jcad"));
                    var file = save ? chooser.showSaveDialog(owner) : chooser.showOpenDialog(owner);
                    if (file != null) path.setText(file.toString());
                });
        var error = new Label();
        error.getStyleClass().add("error");
        var box =
                new VBox(
                        10,
                        new Label("Editable design location"),
                        new HBox(8, path, browse),
                        error);
        dialog.getDialogPane().setContent(box);
        var confirm = new ButtonType(save ? "Save" : "Open", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(confirm, ButtonType.CANCEL);
        var button = dialog.getDialogPane().lookupButton(confirm);
        button.setId("file-confirm");
        button.addEventFilter(
                ActionEvent.ACTION,
                e -> {
                    try {
                        if (path.getText().isBlank())
                            throw new IllegalArgumentException("Choose a file path");
                        Path.of(path.getText());
                    } catch (IllegalArgumentException failure) {
                        error.setText(failure.getMessage());
                        e.consume();
                    }
                });
        dialog.setResultConverter(
                b -> b == confirm ? Path.of(path.getText()).toAbsolutePath() : null);
        return Dialogs.show(dialog, owner);
    }
}
