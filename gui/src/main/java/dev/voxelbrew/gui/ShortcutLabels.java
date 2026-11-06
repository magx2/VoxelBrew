package dev.voxelbrew.gui;

import javafx.geometry.Pos;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ContentDisplay;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;

/**
 * Always-visible letter hints; original text, accessibility help and keyboard dispatch stay intact.
 */
final class ShortcutLabels {
    private ShortcutLabels() {}

    static void install(ButtonBase button) {
        var key = KeyboardShortcuts.letter(button.getId());
        // Icon controls retain their own graphics and existing shortcut tooltip.
        if (key.isEmpty()
                || button.getGraphic() != null
                || button.getProperties().putIfAbsent(ShortcutLabels.class, true) != null) return;
        var caption = new HBox();
        caption.getStyleClass().add("shortcut-caption");
        caption.setAlignment(Pos.BASELINE_LEFT);
        caption.setMouseTransparent(true);
        caption.setMinWidth(Region.USE_PREF_SIZE);
        button.setMnemonicParsing(false);
        button.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        button.setGraphic(caption);
        button.setMinWidth(Region.USE_PREF_SIZE);
        update(button, caption, key.get());
        button.textProperty()
                .addListener((observable, before, after) -> update(button, caption, key.get()));
    }

    private static void update(ButtonBase button, HBox caption, String key) {
        String text = button.getText();
        caption.getChildren().clear();
        if (text == null || text.isEmpty()) return;
        int index = -1;
        for (int i = 0; i < text.length(); i++) {
            if (Character.toUpperCase(text.charAt(i)) == key.charAt(0)) {
                index = i;
                break;
            }
        }
        if (index >= 0) {
            caption.getChildren()
                    .addAll(
                            fragment(button, text.substring(0, index), false),
                            fragment(button, text.substring(index, index + 1), true),
                            fragment(button, text.substring(index + 1), false));
        } else {
            caption.getChildren()
                    .addAll(
                            fragment(button, text + " (", false),
                            fragment(button, key, true),
                            fragment(button, ")", false));
        }
    }

    private static Text fragment(ButtonBase button, String text, boolean underline) {
        var fragment = new Text(text);
        fragment.fontProperty().bind(button.fontProperty());
        fragment.fillProperty().bind(button.textFillProperty());
        fragment.setUnderline(underline);
        if (underline) fragment.getStyleClass().add("shortcut-key");
        return fragment;
    }
}
