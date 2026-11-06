package dev.voxelbrew.exporter.api;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

public record ExporterDescriptor(
        String id,
        String name,
        int apiVersion,
        List<String> extensions,
        boolean multipleInstances,
        List<ExportOption> options) {
    public ExporterDescriptor {
        if (id == null || !id.matches("[a-z0-9][a-z0-9.-]*")) throw new IllegalArgumentException("Invalid exporter ID");
        Objects.requireNonNull(name);
        extensions = List.copyOf(extensions);
        options = List.copyOf(options);
        if (extensions.isEmpty() || extensions.stream().anyMatch(e -> !e.matches("[a-zA-Z0-9]+")))
            throw new IllegalArgumentException("Invalid extensions");
        var keys = new HashSet<String>();
        for (var option : options)
            if (!keys.add(option.key())) throw new IllegalArgumentException("Duplicate option " + option.key());
    }
}
