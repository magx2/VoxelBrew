package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import java.awt.Desktop;
import java.net.URI;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Window;
import lombok.extern.slf4j.Slf4j;

/** Support links shared by the Settings and About dialogs. */
@Slf4j
final class SupportDialog {
    private SupportDialog() {}

    static void show(Window owner, Translations translations) {
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle(translations.text("support.title"));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setId("support-close");
        var message = new Label(translations.text("support.welcome"));
        message.setWrapText(true);
        dialog.getDialogPane().setContent(new VBox(16, message, banners()));
        Dialogs.show(dialog, owner, translations);
    }

    static VBox banners() {
        var banners = new VBox(
                10,
                banner("☕  Support on buycoffee.to", "support-buycoffee", "#00a862", "https://buycoffee.to/magx2"),
                banner("☕  Support on Ko-fi", "support-kofi", "#29abe0", "https://ko-fi.com/magx2"));
        banners.setAlignment(Pos.CENTER);
        return banners;
    }

    private static Button banner(String text, String id, String color, String address) {
        var button = new Button(text);
        button.setId(id);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setMinHeight(46);
        button.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white;"
                + " -fx-font-size: 15px; -fx-font-weight: bold; -fx-background-radius: 8;");
        button.setOnAction(event -> {
            try {
                Desktop.getDesktop().browse(URI.create(address));
            } catch (Exception failure) {
                log.warn("Could not open support link {}", address, failure);
                var alert = new Alert(Alert.AlertType.ERROR, address, ButtonType.CLOSE);
                alert.setHeaderText("Could not open the link. Copy this address into your browser:");
                alert.showAndWait();
            }
        });
        return button;
    }
}
