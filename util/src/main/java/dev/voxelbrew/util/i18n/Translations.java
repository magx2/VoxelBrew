package dev.voxelbrew.util.i18n;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;

/** Immutable, thread-safe translation catalog. Missing languages fall back to English. */
@Slf4j
public final class Translations {
    @Getter
    @Accessors(fluent = true)
    private final Locale locale;

    private final ResourceBundle messages;

    public Translations(@NonNull Locale locale) {

        this.locale = locale.getLanguage().equals("pl") ? Locale.forLanguageTag("pl") : Locale.ENGLISH;
        var control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);
        var english = ResourceBundle.getBundle("dev.voxelbrew.util.i18n.messages", Locale.ENGLISH, control);
        var selected = ResourceBundle.getBundle("dev.voxelbrew.util.i18n.messages", this.locale, control);
        messages = new ResourceBundle() {
            @Override
            public Locale getLocale() {
                return Translations.this.locale;
            }

            @Override
            protected Object handleGetObject(String key) {
                return selected.containsKey(key) ? selected.getObject(key) : english.getObject(key);
            }

            @Override
            public Enumeration<String> getKeys() {
                var keys = new HashSet<>(english.keySet());
                keys.addAll(selected.keySet());
                return Collections.enumeration(keys);
            }
        };
    }

    public ResourceBundle bundle() {
        return messages;
    }

    public String text(String key, Object... arguments) {
        String pattern = messages.getString(key);
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }

    /** Provider descriptions without a bundled translation retain their original text. */
    public String textOrDefault(String key, String fallback) {
        return messages.containsKey(key) ? text(key) : fallback;
    }

    public static Translations configured(UserConfiguration configuration) {
        try {
            String language = configuration.get("language", "system").strip();
            boolean system = language.isEmpty() || language.equalsIgnoreCase("system");
            Locale selected = system
                    ? Locale.getDefault(Locale.Category.DISPLAY)
                    : new Locale.Builder().setLanguageTag(language).build();
            var translations = new Translations(selected);
            String source = system ? "system display locale" : "saved language preference";
            if (selected.getLanguage().equals(translations.locale().getLanguage()))
                log.info(
                        "Selected application language {} from {} ({})",
                        translations.locale().toLanguageTag(),
                        source,
                        selected.toLanguageTag());
            else
                log.info(
                        "Selected application language en: English fallback for unset or unsupported {} ({})",
                        source,
                        selected.toLanguageTag());
            return translations;
        } catch (IOException | IllformedLocaleException failure) {
            log.warn("Cannot read language preference; using English", failure);
            log.info(
                    "Selected application language en: English fallback for unreadable or invalid language preference");
            return new Translations(Locale.ENGLISH);
        }
    }

    private static final class Current {
        static final Translations INSTANCE = configured(UserConfiguration.user());
    }

    /** Reads the language preference once per application process; restart after changing it. */
    public static Translations current() {
        return Current.INSTANCE;
    }
}
