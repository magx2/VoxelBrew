package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import java.util.List;
import java.util.stream.Collectors;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/** One catalog for keyboard dispatch, menu accelerators and translated shortcut help. */
final class KeyboardShortcuts {
    enum Scope {
        DOCUMENT,
        DESIGN,
        SKETCH
    }

    record Binding(Scope scope, String id, boolean inText, List<KeyCombination> keys) {
        boolean matches(KeyEvent event, boolean editingText) {
            return (!editingText || inText) && keys.stream().anyMatch(key -> key.match(event));
        }

        String display() {
            return keys.stream().map(KeyCombination::getDisplayText).collect(Collectors.joining(" / "));
        }
    }

    private static Binding binding(Scope scope, String id, boolean inText, String... keys) {
        return new Binding(
                scope,
                id,
                inText,
                java.util.Arrays.stream(keys)
                        .map(KeyCombination::keyCombination)
                        .toList());
    }

    static final List<Binding> BINDINGS = List.of(
            binding(Scope.DOCUMENT, "new", true, "Shortcut+N"),
            binding(Scope.DOCUMENT, "open", true, "Shortcut+O"),
            binding(Scope.DOCUMENT, "import", true, "Shortcut+I"),
            binding(Scope.DOCUMENT, "save", true, "Shortcut+S"),
            binding(Scope.DOCUMENT, "save-as", true, "Shortcut+Shift+S"),
            binding(Scope.DOCUMENT, "export", true, "Shortcut+E"),
            binding(Scope.DOCUMENT, "undo", false, "Shortcut+Z"),
            binding(Scope.DOCUMENT, "redo", false, "Shortcut+Shift+Z", "Shortcut+Y"),
            binding(Scope.DOCUMENT, "parameters", true, "Shortcut+Shift+P"),
            binding(Scope.DOCUMENT, "workspace-design", true, "Shortcut+1"),
            binding(Scope.DOCUMENT, "workspace-print", true, "Shortcut+2"),
            binding(Scope.DOCUMENT, "keyboard-shortcuts", true, "F1"),
            binding(Scope.DESIGN, "constrained-sketch", false, "K"),
            binding(Scope.DESIGN, "extrude", false, "E"),
            binding(Scope.DESIGN, "cut", false, "H"),
            binding(Scope.DESIGN, "split-body", false, "X"),
            binding(Scope.DESIGN, "fit", false, "F"),
            binding(Scope.DESIGN, "view-isometric", false, "0"),
            binding(Scope.DESIGN, "view-top", false, "1"),
            binding(Scope.DESIGN, "view-front", false, "2"),
            binding(Scope.DESIGN, "view-right", false, "3"),
            binding(Scope.DESIGN, "view-back", false, "4"),
            binding(Scope.DESIGN, "view-left", false, "5"),
            binding(Scope.DESIGN, "view-bottom", false, "6"),
            binding(Scope.SKETCH, "sketch-tool-select", false, "V"),
            binding(Scope.SKETCH, "sketch-tool-point", false, "P"),
            binding(Scope.SKETCH, "sketch-tool-line", false, "L"),
            binding(Scope.SKETCH, "sketch-tool-rectangle", false, "R"),
            binding(Scope.SKETCH, "sketch-tool-circle", false, "C"),
            binding(Scope.SKETCH, "sketch-tool-arc", false, "A"),
            binding(Scope.SKETCH, "sketch-tool-spline", false, "S"),
            binding(Scope.SKETCH, "sketch-tool-polygon", false, "G"),
            binding(Scope.SKETCH, "sketch-fit", false, "F"),
            binding(Scope.SKETCH, "sketch-undo", false, "Shortcut+Z"),
            binding(Scope.SKETCH, "sketch-redo", false, "Shortcut+Shift+Z", "Shortcut+Y"),
            binding(Scope.SKETCH, "sketch-parameters", true, "Shortcut+Shift+P"),
            binding(Scope.SKETCH, "constraint-finish", true, "Shortcut+Enter"),
            binding(Scope.SKETCH, "sketch-remove-constraint", false, "Delete"),
            binding(Scope.SKETCH, "keyboard-shortcuts", true, "F1"));

    private KeyboardShortcuts() {}

    /** Bare letter keys can be shown in captions without implying a new Alt mnemonic. */
    static java.util.Optional<String> letter(String id) {
        return BINDINGS.stream()
                .filter(binding -> binding.id().equals(id))
                .flatMap(binding -> binding.keys().stream())
                .filter(KeyCodeCombination.class::isInstance)
                .map(KeyCodeCombination.class::cast)
                .filter(key -> key.getCode().isLetterKey()
                        && key.getShift() == KeyCombination.ModifierValue.UP
                        && key.getControl() == KeyCombination.ModifierValue.UP
                        && key.getAlt() == KeyCombination.ModifierValue.UP
                        && key.getMeta() == KeyCombination.ModifierValue.UP
                        && key.getShortcut() == KeyCombination.ModifierValue.UP)
                .map(key -> key.getCode().getName())
                .findFirst();
    }

    static void consume(KeyEvent event, javafx.scene.Scene scene) {
        event.consume();
        if (event.isShortcutDown()
                || event.isAltDown()
                || !(event.getCode().isLetterKey() || event.getCode().isDigitKey())) return;
        // KEY_TYPED is a separate event. Opening an editor on KEY_PRESSED can otherwise
        // insert the shortcut letter into its newly focused field (for example K into plane Z).
        class TypedGuard implements javafx.event.EventHandler<KeyEvent> {
            private void remove() {
                scene.removeEventFilter(KeyEvent.KEY_TYPED, this);
                scene.removeEventFilter(KeyEvent.KEY_PRESSED, this);
            }

            @Override
            public void handle(KeyEvent next) {
                if (next.getEventType() == KeyEvent.KEY_TYPED) {
                    next.consume();
                }
                // A fresh press also clears an unused guard, so it cannot eat later typing.
                remove();
            }
        }
        var guard = new TypedGuard();
        scene.addEventFilter(KeyEvent.KEY_TYPED, guard);
        scene.addEventFilter(KeyEvent.KEY_PRESSED, guard);
    }

    static String match(Scope scope, KeyEvent event, Node focus) {
        // Leave popup navigation, type-ahead and editable combo boxes to their own controls.
        boolean editing = focus instanceof TextInputControl
                || (!event.isShortcutDown()
                        && (focus instanceof ComboBoxBase<?>
                                || focus instanceof Spinner<?>
                                || ((event.getCode().isLetterKey()
                                                || event.getCode().isDigitKey())
                                        && (focus instanceof ListView<?> || focus instanceof TreeView<?>))));
        return BINDINGS.stream()
                .filter(b -> b.scope() == scope && b.matches(event, editing))
                .map(Binding::id)
                .findFirst()
                .orElse(null);
    }

    static KeyCombination primary(String id) {
        return BINDINGS.stream()
                .filter(b -> b.id().equals(id))
                .findFirst()
                .orElseThrow()
                .keys()
                .getFirst();
    }

    /** Menu actions may exist without a keyboard binding. */
    static java.util.Optional<KeyCombination> menuAccelerator(String id) {
        String key = id.startsWith("menu-") ? id.substring(5) : id;
        return BINDINGS.stream()
                .filter(binding -> binding.id().equals(key))
                .map(binding -> binding.keys().getFirst())
                .findFirst();
    }

    static String describe(Translations translations, String id, String description) {
        String key = id.startsWith("menu-") ? id.substring(5) : id;
        return BINDINGS.stream()
                .filter(b -> b.id().equals(key))
                .findFirst()
                .map(b -> description + "\n" + translations.text("shortcuts.hint", b.display()))
                .orElse(description);
    }

    static boolean textUndo(KeyEvent event, Node focus) {
        if (!(focus instanceof TextInputControl input)) return false;
        if (primary("undo").match(event)) input.undo();
        else if (BINDINGS.stream().filter(b -> b.id().equals("redo")).anyMatch(b -> b.matches(event, false)))
            input.redo();
        else return false;
        event.consume();
        return true;
    }

    static void show(Window owner, Translations translations) {
        var grid = new GridPane();
        grid.setHgap(20);
        grid.setVgap(10);
        int row = 0;
        for (var scope : Scope.values()) {
            var heading = new Label(translations.text("shortcuts.section." + scope.name()));
            heading.getStyleClass().add("eyebrow");
            grid.add(heading, 0, row++, 2, 1);
            for (var binding : BINDINGS) {
                if (binding.scope() != scope) continue;
                var key = new Label(binding.display());
                key.setMinWidth(135);
                var description = new Label(
                        binding.id().startsWith("view-")
                                ? UiText.view(
                                        translations,
                                        binding.id().substring(5, 6).toUpperCase(java.util.Locale.ROOT)
                                                + binding.id().substring(6))
                                : UiHelp.baseDescription(translations, binding.id()));
                description.setWrapText(true);
                description.setMaxWidth(390);
                grid.addRow(row++, key, description);
            }
        }
        var note = new Label(translations.text("shortcuts.note"));
        note.setWrapText(true);
        note.setMaxWidth(540);
        grid.add(note, 0, row, 2, 1);
        var scroll = new ScrollPane(grid);
        scroll.setId("keyboard-shortcuts-list");
        scroll.setFitToWidth(true);
        scroll.setPrefViewportHeight(Math.min(380, owner.getHeight() - 220));
        var dialog = new Dialog<ButtonType>();
        dialog.setTitle(translations.text("shortcuts.title"));
        dialog.getDialogPane().setContent(scroll);
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.getDialogPane().lookupButton(ButtonType.CLOSE).setId("keyboard-shortcuts-close");
        Dialogs.show(dialog, owner, translations);
    }
}
