package dev.voxelbrew.gui;

import dev.voxelbrew.util.i18n.Translations;
import java.util.function.Function;
import javafx.scene.control.ComboBox;
import javafx.util.StringConverter;

/**
 * Display adapters translate labels without changing model identifiers, enum values or commands.
 */
final class UiText {
    private UiText() {}

    static <T> void choices(ComboBox<T> control, Function<T, String> display) {
        control.setConverter(
                new StringConverter<>() {
                    @Override
                    public String toString(T value) {
                        return value == null ? "" : display.apply(value);
                    }

                    @Override
                    public T fromString(String value) {
                        throw new UnsupportedOperationException("Read-only choices");
                    }
                });
    }

    static <E extends Enum<E>> void enums(
            ComboBox<E> control, Translations translations, String prefix) {
        choices(control, value -> translations.text(prefix + value.name()));
    }

    static String view(Translations translations, String name) {
        return java.util.Arrays.stream(name.split(" "))
                .map(part -> translations.textOrDefault("view." + part, part))
                .collect(java.util.stream.Collectors.joining(" "));
    }
}
