package dev.voxelbrew.engine.model;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Immutable millimeter document. The cursor counts evaluated features. */
public record CadDocument(
        String id,
        List<Feature> features,
        int cursor,
        dev.voxelbrew.engine.printing.PrintSetup printSetup) {
    public CadDocument(String id, List<Feature> features, int cursor) {
        this(id, features, cursor, dev.voxelbrew.engine.printing.PrintSetup.empty());
    }

    public CadDocument {
        java.util.Objects.requireNonNull(printSetup);
        identifier(id, "document ID");
        features = List.copyOf(features);
        var ids = new HashSet<String>();
        for (Feature feature : features) {
            identifier(feature.id(), "feature ID");
            if (!ids.add(feature.id())) {
                throw new ModelException(feature.id(), "duplicate feature ID");
            }
        }
        if (cursor < 0 || cursor > features.size()) {
            throw new IllegalArgumentException("cursor must be between 0 and " + features.size());
        }
    }

    public static CadDocument empty() {
        return new CadDocument(UUID.randomUUID().toString(), List.of(), 0);
    }

    public static void identifier(String id, String field) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,127}")) {
            throw new IllegalArgumentException(
                    field + " must contain 1–128 letters, digits, dots, underscores or hyphens");
        }
    }
}
