package dev.voxelbrew.gui;

import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/** Persists display language independently of CAD documents; each window keeps its own catalog. */
final class LanguageDialog {
    private LanguageDialog() {}

    static void show(
            Window owner,
            Translations translations,
            UserConfiguration configuration,
            ExecutorService worker) {
        var dialog = new Dialog<Void>();
        dialog.setTitle(translations.text("language.title"));
        var choice = new ComboBox<String>();
        choice.setId("language-choice");
        choice.getItems().setAll("system", "en", "pl");
        UiText.choices(choice, value -> translations.text("language." + value));
        var message = new Label(translations.text("language.restart"));
        message.setWrapText(true);
        message.setId("language-status");
        try {
            String saved = configuration.get("language", "system").strip();
            choice.setValue(
                    saved.isEmpty() || saved.equalsIgnoreCase("system")
                            ? "system"
                            : Locale.forLanguageTag(saved).getLanguage().equals("pl")
                                    ? "pl"
                                    : "en");
        } catch (IOException failure) {
            choice.setValue("en");
            message.setText(translations.text("language.readFailed", failure.getMessage()));
        }
        var save = new Button(translations.text("action.save"));
        save.setId("language-save");
        UiHelp.install(save, translations);
        save.setOnAction(
                event -> {
                    String language = choice.getValue();
                    save.setDisable(true);
                    choice.setDisable(true);
                    worker.execute(
                            () -> {
                                String result;
                                try {
                                    configuration.set("language", language);
                                    result = translations.text("language.saved");
                                } catch (IOException failure) {
                                    result =
                                            translations.text(
                                                    "language.saveFailed", failure.getMessage());
                                }
                                String status = result;
                                Platform.runLater(
                                        () -> {
                                            message.setText(status);
                                            save.setDisable(false);
                                            choice.setDisable(false);
                                        });
                            });
                });
        dialog.getDialogPane().setContent(new VBox(10, choice, message, save));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setId("language-close");
        Dialogs.show(dialog, owner, translations);
    }
}
