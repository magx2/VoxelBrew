package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import dev.voxelbrew.util.update.*;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Explicit updater. All network, archive and helper work stays off the JavaFX thread. */
@lombok.extern.slf4j.Slf4j
final class UpdateDialog {
    @dagger.assisted.AssistedFactory
    interface Factory {
        UpdateDialog create(Consumer<Runnable> beforeInstall);
    }

    private final UpdateServiceFactory factory;
    private final Translations translations;
    private final Consumer<Runnable> beforeInstall;
    private final ExecutorService worker;
    private final UpdateCancellation cancellation = new UpdateCancellation();
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final TextField version = new TextField();
    private final CheckBox force;
    private final Label status;
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button check, download;
    private final Button install, cancel;
    private final ButtonType installType;
    private UpdateService service;
    private UpdateService.Check selected;
    private UpdateService.Prepared prepared;
    private boolean busy, closed, scheduled;

    @dagger.assisted.AssistedInject
    UpdateDialog(
            UpdateServiceFactory factory,
            Translations translations,
            @javax.inject.Named("updateWorkers")
                    java.util.function.Supplier<ExecutorService> workers,
            @dagger.assisted.Assisted Consumer<Runnable> beforeInstall) {
        this.factory = factory;
        this.translations = translations;
        this.beforeInstall = beforeInstall;
        this.worker = workers.get();
        this.force = new CheckBox(translations.text("update.reinstall"));
        this.status = new Label(translations.text("update.intro"));
        this.check = new Button(translations.text("update.check"));
        this.download = new Button(translations.text("update.download"));
        this.installType =
                new ButtonType(translations.text("update.install"), ButtonBar.ButtonData.OK_DONE);
        dialog.setTitle(translations.text("update.title"));
        dialog.setHeaderText(translations.text("update.heading"));
        dialog.getDialogPane().getButtonTypes().setAll(installType, ButtonType.CANCEL);
        install = (Button) dialog.getDialogPane().lookupButton(installType);
        cancel = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        install.setId("update-install");
        cancel.setId("update-cancel");
        cancel.setText(translations.text("action.cancel"));
        check.setId("update-check");
        download.setId("update-download");
        version.setId("update-version");
        force.setId("update-force");
        for (var control : java.util.List.of(force, check, download))
            UiHelp.install(control, translations);
        status.setId("update-status");
        version.setPromptText(translations.text("update.versionPrompt"));
        status.setWrapText(true);
        progress.setMaxSize(26, 26);
        progress.setVisible(false);
        progress.managedProperty().bind(progress.visibleProperty());
        var content =
                new VBox(
                        12,
                        new Label(translations.text("update.installed", BuildVersion.current())),
                        new Label(translations.text("update.version")),
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
        var updates = factory.create(beforeInstall);
        try {
            Dialogs.show(updates.dialog, owner, updates.translations);
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
        status.setText(translations.text("update.checking"));
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
                                            translations.text(
                                                    "gui.verified_and_ready_install_and_quit_will_close_this_application_and_retain_the_previous_install"));
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
        status.setText(translations.text("update.starting"));
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
                                    ? translations.text("gui.update_cancelled")
                                    : translations.text("update.failed", failure.getMessage()));
                    cancel.setDisable(false);
                    busy(false);
                });
    }

    private static void cleanup(UpdateService.Prepared prepared) {
        try {
            prepared.close();
        } catch (IOException failure) {
            log.warn("Update staging cleanup failed", failure);
        }
    }
}
