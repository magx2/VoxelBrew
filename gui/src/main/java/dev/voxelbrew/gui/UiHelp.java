package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import java.util.Locale;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

/** Descriptions are keyed by stable control identities, independently of their display captions. */
final class UiHelp {
    private UiHelp() {}

    static String description(Translations translations, String id) {
        return KeyboardShortcuts.describe(translations, id, baseDescription(translations, id));
    }

    static String baseDescription(Translations translations, String id) {
        String key = id;
        if (id.startsWith("choose-plane-")) return translations.text("help.choose-plane");
        if (id.startsWith("view-") && !id.startsWith("view-cube-")) return translations.text("help.standard-view");
        if (id.equals("menu-undo") || id.equals("menu-redo") || id.equals("menu-parameters") || id.equals("menu-fit"))
            key = id.substring(5);
        if (id.startsWith("history-") && !id.equals("history-cursor")) key = "history-feature";
        else if (id.startsWith("apply-")) key = "apply-dimension";
        else if (id.startsWith("print-role-")) key = "print-role";
        else if (id.startsWith("constraint-menu-"))
            return translations.text("constraint.hint." + id.substring(16).toUpperCase(Locale.ROOT));
        else if (id.startsWith("constraint-") && !id.equals("constraint-finish") && !id.equals("constraint-cancel"))
            return translations.text("constraint.hint." + id.substring(11).toUpperCase(Locale.ROOT));
        return translations.text("help." + key);
    }

    static void dialogButton(ButtonBase button, ButtonType type, Translations translations) {
        String key = button.getId() == null ? null : "help." + button.getId();
        String description = key == null ? null : translations.textOrDefault(key, null);
        if (description == null)
            description = translations.text(
                    switch (type.getButtonData()) {
                        case CANCEL_CLOSE -> "help.dialog-cancel";
                        case NO -> "help.dialog-no";
                        case YES, OK_DONE, APPLY -> "help.dialog-apply";
                        default -> "help.dialog-close";
                    });
        if (type == ButtonType.CLOSE) description = translations.text("help.dialog-close");
        describe(button, description);
    }

    static void install(Control control, Translations translations) {
        describe(control, description(translations, control.getId()));
        if (control instanceof ButtonBase button) ShortcutLabels.install(button);
    }

    static void describe(Control control, String description) {
        if (control.getTooltip() != null
                && description.equals(control.getTooltip().getText())) return;
        control.setTooltip(tooltip(description));
        control.setAccessibleHelp(description);
    }

    static Tooltip tooltip(String description) {
        var tooltip = new Tooltip(description);
        // Closing help must never swallow a click intended for the underlying action.
        tooltip.setConsumeAutoHidingEvents(false);
        tooltip.getScene()
                .getStylesheets()
                .add(UiHelp.class.getResource("workbench.css").toExternalForm());
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(360);
        tooltip.setShowDelay(Duration.millis(450));
        // A lingering popup can cover a neighboring button and receive its click itself.
        tooltip.setHideDelay(Duration.ZERO);
        tooltip.setShowDuration(Duration.seconds(30));
        return tooltip;
    }

    static void menuItem(MenuItem item, String description) {
        // MenuItem is not a Node. Its public styleable node exists after the popup is shown.
        item.getProperties().put(UiHelp.class, description);
    }

    static void menu(Menu menu, Translations translations) {
        menuItem(menu, description(translations, menu.getId()));
        menu.addEventHandler(
                Menu.ON_SHOWN, event -> Platform.runLater(() -> menu.getItems().forEach(UiHelp::attach)));
    }

    static void menu(MenuButton menu, Translations translations) {
        install(menu, translations);
        observeMenu(menu);
    }

    static void menu(ContextMenu menu) {
        menu.addEventHandler(
                javafx.stage.WindowEvent.WINDOW_SHOWN,
                event -> Platform.runLater(() -> menu.getItems().forEach(UiHelp::attach)));
    }

    private static void observeMenu(MenuButton menu) {
        menu.showingProperty().addListener((o, before, showing) -> {
            if (showing) Platform.runLater(() -> menu.getItems().forEach(UiHelp::attach));
        });
    }

    static void menuBar(MenuBar bar) {
        bar.skinProperty().addListener((o, before, after) -> Platform.runLater(() -> attachMenuHeaders(bar)));
    }

    private static void attachMenuHeaders(MenuBar bar) {
        for (var node : bar.lookupAll(".menu-button")) {
            if (!(node instanceof MenuButton button)) continue;
            for (var menu : bar.getMenus()) {
                if (java.util.Objects.equals(menu.getId(), button.getId()))
                    describe(button, (String) menu.getProperties().get(UiHelp.class));
            }
            observeMenu(button);
        }
    }

    private static void attach(MenuItem item) {
        Node node = item.getStyleableNode();
        Object description = item.getProperties().get(UiHelp.class);
        if (node != null && description instanceof String text) {
            var popup = item.getParentPopup();
            if (popup != null && popup.getOwnerWindow() != null) {
                var owner = popup.getOwnerWindow().getScene().getRoot();
                var root = popup.getScene().getRoot();
                String stylesheet = UiHelp.class.getResource("workbench.css").toExternalForm();
                if (!popup.getScene().getStylesheets().contains(stylesheet))
                    popup.getScene().getStylesheets().add(stylesheet);
                boolean dark = owner.getStyleClass().contains("dark") || owner.lookup(".dark") != null;
                root.getStyleClass().removeAll("light", "dark");
                root.getStyleClass().add(dark ? "dark" : "light");
            }
            node.setId(item.getId());
            // A new popup skin may be created on each opening; do not retain its nodes.
            Object previous = node.getProperties().put(UiHelp.class, text);
            if (!text.equals(previous)) installMenuHover(node, item, text);
            node.setAccessibleHelp(text);
        }
    }

    private static void installMenuHover(Node node, MenuItem item, String text) {
        // The menu skin consumes mouse moves before ordinary Tooltip handlers see them.
        // Capture hover without consuming it so native selection and keyboard navigation remain
        // intact.
        var tooltip = tooltip(text);
        tooltip.setHideOnEscape(false);
        tooltip.setConsumeAutoHidingEvents(false);
        var delay = new PauseTransition(tooltip.getShowDelay());
        var expiry = new PauseTransition(tooltip.getShowDuration());
        double[] pointer = new double[2];
        Runnable hide = () -> {
            delay.stop();
            expiry.stop();
            tooltip.hide();
        };
        delay.setOnFinished(event -> {
            if (node.getScene() != null && node.getScene().getWindow().isShowing()) {
                tooltip.show(node, pointer[0] + 12, pointer[1] + 20);
                expiry.playFromStart();
            }
        });
        expiry.setOnFinished(event -> tooltip.hide());
        node.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            pointer[0] = event.getScreenX();
            pointer[1] = event.getScreenY();
            if (!tooltip.isShowing()) delay.playFromStart();
        });
        node.addEventFilter(MouseEvent.MOUSE_EXITED, event -> hide.run());
        node.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> hide.run());
        item.getParentPopup().showingProperty().addListener((o, before, showing) -> {
            if (!showing) hide.run();
        });
    }
}
