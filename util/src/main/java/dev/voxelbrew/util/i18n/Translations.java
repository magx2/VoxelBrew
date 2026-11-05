package dev.voxelbrew.util.i18n;

import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Immutable, thread-safe translation catalog. Missing languages fall back to English. */
public final class Translations {
    private final Locale locale;
    private final ResourceBundle messages;

    public Translations(Locale locale) {
        this.locale = Objects.requireNonNull(locale);
        messages =
                ResourceBundle.getBundle(
                        "dev.voxelbrew.util.i18n.messages",
                        locale,
                        ResourceBundle.Control.getNoFallbackControl(
                                ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    public Locale locale() {
        return locale;
    }

    public ResourceBundle bundle() {
        return messages;
    }

    public String text(String key, Object... arguments) {
        String pattern = messages.getString(key);
        return arguments.length == 0
                ? pattern
                : new MessageFormat(pattern, locale).format(arguments);
    }

    public static Translations configured(UserConfiguration configuration) {
        try {
            String language = configuration.get("language", "en");
            Locale selected =
                    language.equalsIgnoreCase("system")
                            ? Locale.getDefault()
                            : new Locale.Builder().setLanguageTag(language).build();
            return new Translations(selected);
        } catch (IOException | IllformedLocaleException failure) {
            Logger.getLogger(Translations.class.getName())
                    .log(Level.WARNING, "Cannot read language preference; using English", failure);
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
