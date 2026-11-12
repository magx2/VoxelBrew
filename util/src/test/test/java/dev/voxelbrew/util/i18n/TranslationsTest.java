package dev.voxelbrew.util.i18n;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.*;
import java.util.*;
import java.util.logging.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

class TranslationsTest {
    @TempDir
    Path directory;

    @Test
    void loadsUserCatalogAndFallsBackToEnglishForMissingKeys() throws Exception {
        var catalogs = Files.createDirectory(directory.resolve("i18n"));
        Files.writeString(
                catalogs.resolve("messages_de-DE.properties"),
                "language.name=Deutsch\nlanguage.i18n=de-DE\naction.save=Speichern\n");
        var german = new Translations(Locale.forLanguageTag("de-DE"), catalogs);
        assertEquals("Speichern", german.text("action.save"));
        assertEquals("Application language", german.text("language.title"));
        assertEquals("Deutsch", german.languages().get("de-DE"));
        assertEquals("English", german.languages().get("en-EN"));
        assertEquals("Polski", german.languages().get("pl-PL"));
    }

    private Locale previousDefault, previousDisplay, previousFormat;

    @BeforeEach
    void rememberLocales() {
        previousDefault = Locale.getDefault();
        previousDisplay = Locale.getDefault(Locale.Category.DISPLAY);
        previousFormat = Locale.getDefault(Locale.Category.FORMAT);
    }

    @AfterEach
    void restoreLocales() {
        Locale.setDefault(previousDefault);
        Locale.setDefault(Locale.Category.DISPLAY, previousDisplay);
        Locale.setDefault(Locale.Category.FORMAT, previousFormat);
    }

    @Test
    void firstLaunchUsesDisplayLanguageWithoutCreatingOrChangingPreferences() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        Locale.setDefault(Locale.ENGLISH);
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
        assertEquals("Zapisz", Translations.configured(config).text("action.save"));
        assertFalse(Files.exists(config.path()));
        assertEquals(Locale.ENGLISH, Locale.getDefault(Locale.Category.FORMAT));

        config.set("external.setting", "preserve me");
        var before = config.read();
        assertEquals("Zapisz", Translations.configured(config).text("action.save"));
        assertEquals(before, config.read());
        config.set("language", "  ");
        assertEquals("Zapisz", Translations.configured(config).text("action.save"));

        Locale.setDefault(Locale.Category.DISPLAY, Locale.UK);
        Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("pl-PL"));
        assertEquals("Save", Translations.configured(config).text("action.save"));
    }

    @Test
    void missingUnsupportedAndInvalidLanguagesFallBackWhileSavedChoicesOverrideSystem() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        Locale.setDefault(Locale.forLanguageTag("pl-PL"));
        for (var unsupported : List.of(Locale.ROOT, Locale.GERMAN, Locale.JAPANESE)) {
            Locale.setDefault(Locale.Category.DISPLAY, unsupported);
            assertEquals(Locale.ENGLISH, Translations.configured(config).locale());
        }
        config.set("language", "pl-PL");
        assertEquals("Zapisz", Translations.configured(config).text("action.save"));
        Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
        for (var saved : List.of("en", "en-GB", "de-DE", "not_a_tag")) {
            config.set("language", saved);
            assertEquals(Locale.ENGLISH, Translations.configured(config).locale());
        }
        config.set("language", " SYSTEM ");
        assertEquals("Zapisz", Translations.configured(config).text("action.save"));
        Files.writeString(config.path(), "language=\\uXXXX");
        assertEquals(Locale.ENGLISH, Translations.configured(config).locale());
        assertEquals("language=\\uXXXX", Files.readString(config.path()));
    }

    @Test
    void startupLogsSelectionSourceAndEnglishFallbackAtInfoLevel() throws Exception {
        var records = new ArrayList<LogRecord>();
        var logger = Logger.getLogger(Translations.class.getName());
        var previousLevel = logger.getLevel();
        boolean previousParents = logger.getUseParentHandlers();
        var capture = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {}

            @Override
            public void close() {}
        };
        logger.setLevel(Level.INFO);
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        try {
            var config = new UserConfiguration(directory.resolve("config.properties"));
            Locale.setDefault(Locale.Category.DISPLAY, Locale.forLanguageTag("pl-PL"));
            Translations.configured(config);
            config.set("language", "en");
            Translations.configured(config);
            config.set("language", "system");
            Locale.setDefault(Locale.Category.DISPLAY, Locale.ROOT);
            Translations.configured(config);
            Locale.setDefault(Locale.Category.DISPLAY, Locale.GERMAN);
            Translations.configured(config);
            config.set("language", "not_a_tag");
            Translations.configured(config);
            var messages = records.stream()
                    .filter(r -> r.getLevel() == Level.INFO)
                    .map(LogRecord::getMessage)
                    .toList();
            assertEquals(5, messages.size());
            assertTrue(messages.get(0).contains("language pl from system display locale (pl-PL)"));
            assertTrue(messages.get(1).contains("language en from saved language preference (en)"));
            assertTrue(messages.get(2).contains("English fallback"));
            assertTrue(messages.get(2).contains("system display locale (und)"));
            assertTrue(messages.get(3).contains("system display locale (de)"));
            assertTrue(messages.get(4).contains("English fallback for unreadable or invalid"));
        } finally {
            logger.removeHandler(capture);
            logger.setLevel(previousLevel);
            logger.setUseParentHandlers(previousParents);
        }
    }

    @Test
    void catalogUsesUtf8AndFallsBackToEnglishWithoutHostLocaleLeakage() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            var polish = new Translations(Locale.forLanguageTag("pl-PL"));
            assertEquals(polish.locale(), polish.bundle().getLocale());
            assertEquals("Otwórz…", polish.text("action.open"));
            assertEquals("Zainstalowano: 1.2.3", polish.text("update.installed", "1.2.3"));
            assertEquals("Open…", new Translations(Locale.JAPANESE).text("action.open"));
            assertEquals(Locale.ENGLISH, new Translations(Locale.JAPANESE).locale());
            assertEquals("Widok: Góra", polish.text("view.accessible", "Góra"));
            assertEquals("Provider-owned caption", polish.textOrDefault("external.caption", "Provider-owned caption"));
            assertEquals("Otwórz…", polish.textOrDefault("action.open", "ignored"));
            assertThrows(MissingResourceException.class, () -> polish.text("missing.key"));
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void configurationSelectsLanguageAndEveryCatalogHasTheSameKeysAndValidFormats() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("language", "en");
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
        try (var base = Translations.class.getResourceAsStream("messages_en.properties");
                var local = Translations.class.getResourceAsStream("messages_pl.properties")) {
            keys.load(new java.io.InputStreamReader(
                    Objects.requireNonNull(base), java.nio.charset.StandardCharsets.UTF_8));
            translated.load(new java.io.InputStreamReader(
                    Objects.requireNonNull(local), java.nio.charset.StandardCharsets.UTF_8));
        }
        assertEquals(keys.keySet(), translated.keySet());
        for (String key : keys.stringPropertyNames()) {
            assertFalse(english.text(key, "test").isBlank());
            assertFalse(polish.text(key, "test").isBlank());
            assertEquals(placeholders(keys.getProperty(key)), placeholders(translated.getProperty(key)), key);
        }
    }

    private static Set<String> placeholders(String pattern) {
        var result = new TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("\\{(\\d+)(?:[,}])").matcher(pattern);
        while (matcher.find()) result.add(matcher.group(1));
        return result;
    }

    @Test
    void configuredCatalogsAreIndependentAndOnlyNewInstancesReadPreferenceChanges() throws Exception {
        var config = new UserConfiguration(directory.resolve("config.properties"));
        config.set("language", "en");
        var english = Translations.configured(config);
        config.set("language", "pl-PL");
        var polish = Translations.configured(config);
        assertEquals("Language…", english.text("language.label"));
        assertEquals("Język…", polish.text("language.label"));
        assertEquals("Result: 1.5 mm", english.text("formula.result", "1.5", " mm"));
        assertEquals("Wynik: 1.5 mm", polish.text("formula.result", "1.5", " mm"));
        Locale previous = Locale.getDefault();
        try {
            config.set("language", "system");
            Locale.setDefault(Locale.forLanguageTag("pl-PL"));
            assertEquals("Zapisz", Translations.configured(config).text("action.save"));
            Locale.setDefault(Locale.GERMAN);
            assertEquals("Save", Translations.configured(config).text("action.save"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
