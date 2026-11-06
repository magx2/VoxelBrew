package dev.voxelbrew.gui;

import dev.voxelbrew.util.Version;
import dev.voxelbrew.util.i18n.Translations;
import dev.voxelbrew.util.update.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import javax.inject.*;
import lombok.extern.slf4j.Slf4j;

/** An explicit, window-local release lookup. Closing it cancels work and rejects late results. */
@Slf4j
final class AboutDialog {
    private final UpdateServiceFactory factory;
    private final Translations translations;
    private final ExecutorService worker;
    private final UpdateCancellation cancellation = new UpdateCancellation();
    private final Dialog<ButtonType> dialog = new Dialog<>();
    private final Label status = new Label();
    private final Button check;
    private final Hyperlink updates, license, notices;
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button close;
    private boolean closed, openUpdates;

    @Inject
    AboutDialog(
            UpdateServiceFactory factory,
            Translations translations,
            @Named("updateWorkers") Supplier<ExecutorService> workers) {
        this.factory = factory;
        this.translations = translations;
        dialog.setTitle(translations.text("about.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        close = (Button) dialog.getDialogPane().lookupButton(ButtonType.CLOSE);
        close.setId("about-close");
        var product = new Label("VoxelBrew CAD");
        product.getStyleClass().add("selection-title");
        var version = label(translations.text("about.version", Version.version()), "about-version");
        status.setId("about-status");
        status.setWrapText(true);
        progress.setMaxSize(20, 20);
        progress.setMinSize(20, 20);
        progress.managedProperty().bind(progress.visibleProperty());
        check = new Button(translations.text("about.check"));
        check.setId("about-check");
        check.setMinWidth(Region.USE_PREF_SIZE);
        UiHelp.install(check, translations);
        check.setOnAction(event -> check());
        updates = link("about.updates", "about-updates");
        updates.setOnAction(
                event -> {
                    openUpdates = true;
                    close.fire();
                });
        license = link("about.license", "about-license");
        notices = link("about.notices", "about-notices");
        var releaseStatus = new HBox(10, progress, status);
        releaseStatus.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(status, Priority.ALWAYS);
        var content =
                new VBox(
                        12,
                        product,
                        version,
                        label(translations.text("about.description"), "about-description"),
                        new Separator(),
                        releaseStatus,
                        check,
                        updates,
                        new Separator(),
                        license,
                        notices);
        var scroll = new ScrollPane(content);
        content.getStyleClass().add("about-content");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(340);
        dialog.getDialogPane().setContent(scroll);
        worker = workers.get();
    }

    boolean show(Window owner) {
        log.info("Opening About for VoxelBrew {}", Version.version());
        license.setOnAction(event -> showLegal(owner, "LICENSE", license.getText()));
        notices.setOnAction(event -> showLegal(owner, "THIRD_PARTY_NOTICES.md", notices.getText()));
        try {
            check();
            Dialogs.show(dialog, owner, translations);
            return openUpdates;
        } finally {
            closed = true;
            cancellation.cancel();
            worker.shutdownNow();
            log.debug("Closed About and cancelled its release lookup");
        }
    }

    private void check() {
        check.setDisable(true);
        progress.setVisible(true);
        status.setText(translations.text("about.checking"));
        worker.execute(
                () -> {
                    try {
                        var result =
                                factory.create()
                                        .check(
                                                new UpdateService.Request(null, false),
                                                cancellation);
                        Platform.runLater(
                                () -> {
                                    if (closed) return;
                                    String message =
                                            result.available()
                                                    ? translations.text(
                                                            "about.available",
                                                            result.release().version())
                                                    : translations.text(
                                                            "about.current",
                                                            result.release().version());
                                    if (!result.installable())
                                        message += "\n" + translations.text("about.development");
                                    status.setText(message);
                                    finished();
                                });
                    } catch (Exception failure) {
                        if (failure instanceof CancellationException
                                || Thread.currentThread().isInterrupted())
                            log.debug("About release lookup cancelled");
                        else log.warn("About release lookup failed", failure);
                        Platform.runLater(
                                () -> {
                                    if (closed) return;
                                    status.setText(translations.text("about.failed"));
                                    finished();
                                });
                    }
                });
    }

    private void finished() {
        progress.setVisible(false);
        check.setDisable(false);
    }

    private void showLegal(Window owner, String resource, String title) {
        try (var input = AboutDialog.class.getResourceAsStream("legal/" + resource)) {
            if (input == null) throw new IOException("Missing legal resource: " + resource);
            var text = new TextArea(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            text.setId("about-legal-text");
            text.setEditable(false);
            text.setWrapText(true);
            text.setPrefHeight(300);
            text.setAccessibleText(title);
            var legal = new Dialog<ButtonType>();
            legal.setTitle(title);
            legal.getDialogPane().setContent(text);
            legal.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            legal.getDialogPane().lookupButton(ButtonType.CLOSE).setId("about-legal-close");
            Dialogs.show(legal, owner, translations);
        } catch (IOException failure) {
            log.error("Cannot read bundled legal information {}", resource, failure);
            status.setText(translations.text("about.legalFailed"));
        }
    }

    private Hyperlink link(String key, String id) {
        var link = new Hyperlink(translations.text(key));
        link.setId(id);
        link.setWrapText(true);
        UiHelp.install(link, translations);
        return link;
    }

    private static Label label(String text, String id) {
        var label = new Label(text);
        label.setId(id);
        label.setWrapText(true);
        return label;
    }
}
