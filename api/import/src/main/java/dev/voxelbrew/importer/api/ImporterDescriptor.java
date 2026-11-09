package dev.voxelbrew.importer.api;

import java.util.HashSet;
import java.util.List;
import lombok.NonNull;

public record ImporterDescriptor(
        String id,
        @NonNull String name,
        int apiVersion,
        List<String> extensions,
        @NonNull ImportResult.Kind resultKind,
        List<ImportOption> options) {
    public ImporterDescriptor {
        if (id == null || !id.matches("[a-z0-9][a-z0-9.-]*")) throw new IllegalArgumentException("Invalid importer ID");

        extensions = List.copyOf(extensions);
        options = List.copyOf(options);
        if (extensions.isEmpty() || extensions.stream().anyMatch(e -> !e.matches("[a-zA-Z0-9]+")))
            throw new IllegalArgumentException("Invalid extensions");
        var keys = new HashSet<String>();
        for (var option : options)
            if (!keys.add(option.key())) throw new IllegalArgumentException("Duplicate option " + option.key());
    }
}
