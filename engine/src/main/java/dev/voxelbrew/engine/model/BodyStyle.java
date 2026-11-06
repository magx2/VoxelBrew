package dev.voxelbrew.engine.model;

import java.util.Locale;
import java.util.Objects;

/** CAD body appearance and material label, independent of the slicer's process profile. */
public record BodyStyle(String material, String color) {
    public static final BodyStyle DEFAULT = new BodyStyle("", "#91A8B9");

    static void requireBodyId(String id) {
        // Evaluated IDs append an output name to a feature ID (which can itself be 128 chars).
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,255}"))
            throw new IllegalArgumentException(
                    "Body ID must contain 1–256 letters, digits, dots, underscores or hyphens");
    }

    public BodyStyle {
        material = Objects.requireNonNull(material, "material").strip();
        if (material.length() > 64 || material.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Material must have at most 64 characters and no control characters");
        }
        color = Objects.requireNonNull(color, "color").toUpperCase(Locale.ROOT);
        if (!color.matches("#[0-9A-F]{6}")) {
            throw new IllegalArgumentException("Color must be #RRGGBB, for example #FF0000");
        }
    }
}
