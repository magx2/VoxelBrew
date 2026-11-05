package dev.voxelbrew.gui;

import dev.voxelbrew.engine.update.*;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Explicit updater. All network, archive and helper work stays off the JavaFX thread. */
final class UpdateDialog {
    @FunctionalInterface
    interface Factory {
        UpdateService create() throws IOException;
    }

    private final Factory factory;
    private final Consumer<Runnable> beforeInstall;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().daemon().name("voxelbrew-update").factory());
    private final UpdateCancellation cancellation = new UpdateCancellation();
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField version = new TextField();
    private final CheckBox force = new CheckBox("Reinstall if already current");
    private final Label status =
            new Label("Check for a published release. GitHub CLI (gh) authentication is required.");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button check = new Button("Check"), download = new Button("Download");
    private final Button install, cancel;
    private final ButtonType installType =
            new ButtonType("Install and quit", ButtonBar.ButtonData.OK_DONE);
    private UpdateService service;
    private UpdateService.Check selected;
    private UpdateService.Prepared prepared;
    private boolean busy, closed, scheduled;

    private UpdateDialog(Factory factory, Consumer<Runnable> beforeInstall) {
        this.factory = factory;
        this.beforeInstall = beforeInstall;
        dialog.setTitle("VoxelBrew updates");
        dialog.setHeaderText("Application updates");
        dialog.getDialogPane().getButtonTypes().setAll(installType, ButtonType.CANCEL);
        install = (Button) dialog.getDialogPane().lookupButton(installType);
        cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        install.setId("update-install");
        cancel.setId("update-cancel");
        check.setId("update-check");
        download.setId("update-download");
        version.setId("update-version");
        force.setId("update-force");
        status.setId("update-status");
        version.setPromptText("Latest stable release (or A.B.C)");
        status.setWrapText(true);
        progress.setMaxSize(26, 26);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        var content =
                new VBox(
                        12,
                        new Label("Installed: " + BuildVersion.current()),
                        new Label("Release version"),
                        version,
                        force,
                        check,
                        status,
                        progress,
                        download);
        dialog.getDialogPane().setContent(content);
        download.setDisable(true);
        install.setDisable(true);
        version.textProperty().addListener((observable, old, value) -> invalidateSelection());
        force.selectedProperty().addListener((observable, old, value) -> invalidateSelection());
        check.setOnAction(e -> check());
        download.setOnAction(e -> download());
        install.addEventFilter(
                ActionEvent.ACTION,
                e -> {
                    if (scheduled) return;
                    e.consume();
                    if (!busy && prepared != null)
                        beforeInstall.accept(
                                () -> {
                                    if (!closed && !busy) handOff();
                                });
                });
    }

    static boolean show(Window owner, Factory factory, Consumer<Runnable> beforeInstall) {
        var updates = new UpdateDialog(factory, beforeInstall);
        try {
            Dialogs.show(updates.dialog, owner);
            return updates.scheduled;
        } finally {
            updates.closed = true;
            updates.cancellation.cancel();
            var prepared = updates.prepared;
            if (prepared != null) updates.worker.execute(() -> cleanup(prepared));
            updates.worker.shutdown();
        }
    }

    private void invalidateSelection() {
        selected = null;
        download.setDisable(true);
    }

    private void busy(boolean value) {
        busy = value;
        progress.setVisible(value);
        check.setDisable(value || prepared != null);
        version.setDisable(value || prepared != null);
        force.setDisable(value || prepared != null);
        download.setDisable(
                value
                        || selected == null
                        || !selected.available()
                        || !selected.installable()
                        || prepared != null);
        install.setDisable(value || prepared == null);
    }

    private void check() {
        UpdateService.Request request;
        try {
            request =
                    new UpdateService.Request(
                            version.getText().isBlank()
                                    ? null
                                    : ReleaseVersion.release(version.getText().strip()),
                            force.isSelected());
        } catch (IllegalArgumentException invalid) {
            status.setText(invalid.getMessage());
            invalidateSelection();
            return;
        }
        selected = null;
        busy(true);
        status.setText("Checking GitHub releases…");
        worker.execute(
                () -> {
                    try {
                        var created = factory.create();
                        var result = created.check(request, cancellation);
                        Platform.runLater(
                                () -> {
                                    if (closed) return;
                                    service = created;
                                    selected = result;
                                    status.setText(result.message());
                                    busy(false);
                                });
                    } catch (Exception failure) {
                        failed(failure);
                    }
                });
    }

    private void download() {
        var selection = selected;
        var updater = service;
        busy(true);
        worker.execute(
                () -> {
                    try {
                        var ready =
                                updater.prepare(
                                        selection,
                                        cancellation,
                                        message ->
                                                Platform.runLater(
                                                        () -> {
                                                            if (!closed) status.setText(message);
                                                        }));
                        Platform.runLater(
                                () -> {
                                    if (closed) {
                                        Thread.startVirtualThread(() -> cleanup(ready));
                                        return;
                                    }
                                    prepared = ready;
                                    status.setText(
                                            "Verified and ready. Install and quit will close this application and retain the previous installation as a backup. Close other instances too.");
                                    busy(false);
                                });
                    } catch (Exception failure) {
                        failed(failure);
                    }
                });
    }

    private void handOff() {
        busy(true);
        cancel.setDisable(true);
        status.setText("Starting the update helper…");
        worker.execute(
                () -> {
                    try {
                        prepared.handOff();
                        Platform.runLater(
                                () -> {
                                    scheduled = true;
                                    install.setDisable(false);
                                    install.fire();
                                });
                    } catch (Exception failure) {
                        var failed = prepared;
                        prepared = null;
                        cleanup(failed);
                        failed(failure);
                    }
                });
    }

    private void failed(Exception failure) {
        Platform.runLater(
                () -> {
                    if (closed) return;
                    status.setText(
                            failure instanceof CancellationException
                                    ? "Update cancelled"
                                    : "Update failed: " + failure.getMessage());
                    cancel.setDisable(false);
                    busy(false);
                });
    }

    private static void cleanup(UpdateService.Prepared prepared) {
        try {
            prepared.close();
        } catch (IOException failure) {
            System.err.println("Update staging cleanup failed: " + failure.getMessage());
        }
    }
}
