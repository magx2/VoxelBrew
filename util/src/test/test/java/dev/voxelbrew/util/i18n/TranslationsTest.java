package dev.voxelbrew.util.i18n;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TranslationsTest {
    @TempDir Path directory;

    @Test
    void catalogUsesUtf8AndFallsBackToEnglishWithoutHostLocaleLeakage() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            var polish = new Translations(Locale.forLanguageTag("pl-PL"));
            assertEquals("Otwórz…", polish.text("action.open"));
            assertEquals("Zainstalowano: 1.2.3", polish.text("update.installed", "1.2.3"));
            assertEquals("Open…", new Translations(Locale.JAPANESE).text("action.open"));
            assertThrows(MissingResourceException.class, () -> polish.text("missing.key"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void configurationSelectsLanguageAndEveryCatalogHasTheSameKeysAndValidFormats()
            throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        assertEquals("Save", Translations.configured(config).text("action.save"));
        config.set("language", "pl");
        assertEquals("Zapisz", Translations.configured(config).text("action.save"));
        config.set("language", "not_a_tag");
        assertEquals("Save", Translations.configured(config).text("action.save"));
        var english = new Translations(Locale.ENGLISH);
        var polish = new Translations(Locale.forLanguageTag("pl"));
        // Read the files directly so ResourceBundle's inherited keys cannot hide an omission.
        var keys = new Properties();
        var translated = new Properties();
        try (var base = Translations.class.getResourceAsStream("messages.properties");
                var local = Translations.class.getResourceAsStream("messages_pl.properties")) {
            keys.load(
                    new java.io.InputStreamReader(
                            Objects.requireNonNull(base), java.nio.charset.StandardCharsets.UTF_8));
            translated.load(
                    new java.io.InputStreamReader(
                            Objects.requireNonNull(local),
                            java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(keys.keySet(), translated.keySet());
        for (String key : keys.stringPropertyNames()) {
            assertFalse(english.text(key, "test").isBlank());
            assertFalse(polish.text(key, "test").isBlank());
        }
    }
}
