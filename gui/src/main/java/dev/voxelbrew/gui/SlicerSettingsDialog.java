package dev.voxelbrew.gui;

import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.util.i18n.Translations;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;

final class SlicerSettingsDialog {
    private final SlicerCatalog catalog;
    private final SlicerSettings settings;
    private final SlicerAvailability availability;
    private final Translations translations;
    private final ExecutorService worker;

    @javax.inject.Inject
    SlicerSettingsDialog(
            SlicerCatalog catalog,
            SlicerSettings settings,
            SlicerAvailability availability,
            Translations translations,
            @javax.inject.Named("appearance") ExecutorService worker) {
        this.catalog = catalog;
        this.settings = settings;
        this.availability = availability;
        this.translations = translations;
        this.worker = worker;
    }

    void show(Window owner) {
        var dialog = new Dialog<Void>();
        dialog.setTitle(translations.text("slicer.settings.title"));
        var tabs = new TabPane();
        tabs.setId("slicer-settings-tabs");
        var message = new Label(translations.text("slicer.settings.hint"));
        message.setId("slicer-settings-status");
        message.setWrapText(true);
        var fields = new LinkedHashMap<String, Map<String, TextField>>();
        var statusLabels = new LinkedHashMap<String, SlicerStatusView>();
        boolean readable = true;
        for (var descriptor : catalog.descriptors()) {
            var content = new VBox(8);
            content.setPadding(new Insets(12));
            var providerStatus = new SlicerStatusView(availability, translations, "slicer-status-" + descriptor.id());
            providerStatus.showBackend(descriptor.id());

            statusLabels.put(descriptor.id(), providerStatus);
            var options = new LinkedHashMap<String, TextField>();
            try {
                for (var option : new TreeMap<>(settings.options(descriptor.id())).entrySet()) {
                    var field = new TextField(option.getValue());
                    field.setId("slicer-option-" + descriptor.id() + "-" + option.getKey());
                    String label = translations.textOrDefault("slicer.option." + option.getKey(), option.getKey());
                    field.setAccessibleText(label);
                    content.getChildren().add(new Label(label));
                    if (java.util.Set.of("executable", "definition", "settings", "profile")
                            .contains(option.getKey())) {
                        var browse = new Button(translations.text("gui.browse"));
                        browse.setId(field.getId() + "-browse");
                        UiHelp.describe(browse, translations.text("slicer.settings.browse"));
                        browse.setOnAction(event -> {
                            var chooser = new FileChooser();
                            chooser.setTitle(label);
                            var file = chooser.showOpenDialog(owner);
                            if (file != null) field.setText(file.getAbsolutePath());
                        });
                        content.getChildren().add(Dialogs.pathRow(field, browse));
                    } else content.getChildren().add(field);
                    options.put(option.getKey(), field);
                }
            } catch (IOException | RuntimeException failure) {
                readable = false;
                message.setText(translations.text("slicer.settings.failed", failure.getMessage()));
            }
            if (options.isEmpty()) content.getChildren().add(new Label(translations.text("slicer.settings.noOptions")));
            fields.put(descriptor.id(), options);
            var scroll = new ScrollPane(content);
            scroll.setFitToWidth(true);
            scroll.setPrefViewportHeight(230);
            var tab = new Tab(descriptor.name(), new VBox(10, providerStatus, scroll));
            tab.setId("slicer-settings-" + descriptor.id());
            tab.setClosable(false);
            tabs.getTabs().add(tab);
        }
        var saveType = new ButtonType(translations.text("action.save"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CANCEL, saveType);
        var save = (Button) dialog.getDialogPane().lookupButton(saveType);
        save.setDefaultButton(true);
        save.getStyleClass().add("slicer-primary-action");
        save.setId("slicer-settings-save");
        UiHelp.install(save, translations);
        save.setDisable(!readable || fields.isEmpty());

        var close = (Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL);
        close.prefHeightProperty().bind(save.heightProperty());
        close.setId("slicer-settings-close");
        save.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            event.consume();
            var values = new LinkedHashMap<String, Map<String, String>>();
            fields.forEach((id, options) -> {
                var entries = new LinkedHashMap<String, String>();
                options.forEach((key, field) -> entries.put(key, field.getText().strip()));
                values.put(id, Map.copyOf(entries));
            });
            save.setDisable(true);
            tabs.setDisable(true);
            close.setDisable(true);
            worker.execute(() -> {
                String result;
                try {
                    settings.save(values);
                    result = translations.text("slicer.settings.saved");
                } catch (IOException | RuntimeException failure) {
                    result = translations.text("slicer.settings.failed", failure.getMessage());
                }
                String status = result;
                Platform.runLater(() -> {
                    availability.refresh();
                    message.setText(status);
                    save.setDisable(false);
                    tabs.setDisable(false);
                    close.setDisable(false);
                });
            });
        });
        var diagnostics = new Label(String.join("\n", catalog.diagnostics()));
        diagnostics.setWrapText(true);
        if (fields.isEmpty()) message.setText(translations.text("slicer.settings.empty"));
        dialog.getDialogPane().setContent(new VBox(10, tabs, message, diagnostics));
        javafx.collections.MapChangeListener<String, SlicerAvailability.Status> listener =
                change -> statusLabels.forEach((id, label) -> label.showBackend(id));
        availability.statuses.addListener(listener);
        dialog.setOnHidden(event -> availability.statuses.removeListener(listener));
        availability.refresh();
        Dialogs.show(dialog, owner, translations);
    }
}
