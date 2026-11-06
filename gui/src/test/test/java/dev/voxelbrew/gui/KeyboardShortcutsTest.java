package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.i18n.Translations;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import org.junit.jupiter.api.Test;

class KeyboardShortcutsTest {
    @Test
    void modelingKeysRequireExactModifiersAndDoNotHijackCopyOrAltGr() {
        assertEquals("constrained-sketch", match(KeyCode.K, false, false, false));
        assertNull(match(KeyCode.R, false, false, false));
        assertNull(match(KeyCode.C, false, false, false));
        assertNull(match(KeyCode.C, false, true, false));
        assertNull(match(KeyCode.C, false, true, true));
        assertNull(match(KeyCode.C, true, false, false));
        assertEquals("view-top", match(KeyCode.DIGIT1, false, false, false));
        assertNull(match(KeyCode.DIGIT1, false, true, false));
    }

    @Test
    void rectangleAndCircleShortcutsExistOnlyInsideTheSketchEditor() {
        for (var entry :
                java.util.Map.of(
                                KeyCode.R, "sketch-tool-rectangle", KeyCode.C, "sketch-tool-circle")
                        .entrySet())
            assertEquals(
                    entry.getValue(),
                    KeyboardShortcuts.match(
                            KeyboardShortcuts.Scope.SKETCH,
                            new KeyEvent(
                                    KeyEvent.KEY_PRESSED,
                                    "",
                                    "",
                                    entry.getKey(),
                                    false,
                                    false,
                                    false,
                                    false),
                            null));
    }

    @Test
    void catalogsDescribeEveryBindingAndHaveNoConflictsWithinActiveScopes() {
        for (var scope : KeyboardShortcuts.Scope.values()) {
            var combinations = new HashSet<>();
            for (var binding : KeyboardShortcuts.BINDINGS) {
                if (binding.scope() != scope
                        && !(scope == KeyboardShortcuts.Scope.DESIGN
                                && binding.scope() == KeyboardShortcuts.Scope.DOCUMENT)) continue;
                for (var key : binding.keys()) assertTrue(combinations.add(key), binding.id());
                for (var language : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
                    var translations = new Translations(language);
                    assertFalse(UiHelp.description(translations, binding.id()).isBlank());
                    assertFalse(translations.text("shortcuts.section." + scope.name()).isBlank());
                }
            }
        }
    }

    private static String match(KeyCode code, boolean shift, boolean control, boolean alt) {
        return KeyboardShortcuts.match(
                KeyboardShortcuts.Scope.DESIGN,
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, control, alt, false),
                null);
    }
}
