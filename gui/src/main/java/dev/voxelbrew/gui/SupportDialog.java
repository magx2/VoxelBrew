package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import java.awt.Desktop;
import java.net.URI;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
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

    static HBox banners() {
        var banners = new HBox(
                10,
                banner("buycoffee.png", "Support on buycoffee.to", "support-buycoffee", "https://buycoffee.to/magx2"),
                banner("kofi.png", "Support on Ko-fi", "support-kofi", "https://ko-fi.com/magx2"));
        banners.setAlignment(Pos.CENTER);
        return banners;
    }

    private static Button banner(String image, String description, String id, String address) {
        var graphic = new ImageView(
                new Image(SupportDialog.class.getResource("support/" + image).toExternalForm()));
        graphic.setFitWidth(180);
        graphic.setPreserveRatio(true);
        var button = new Button();
        button.setId(id);
        button.setGraphic(graphic);
        button.setAccessibleText(description);
        button.setStyle("-fx-background-color: transparent; -fx-background-insets: 0;"
                + " -fx-border-color: transparent; -fx-border-width: 0;"
                + " -fx-padding: 0; -fx-cursor: hand;");
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
