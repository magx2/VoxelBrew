package dev.voxelbrew.importer.api;

import java.nio.file.Path;
import java.util.*;

/** Standard local source request. No filesystem access occurs in this API. */
@lombok.Getter
@lombok.experimental.Accessors(fluent = true)
public final class FileImportDialog extends ImportDialog<FileImportDialog.Source> {
    private final String title;
    private final List<String> extensions;
    private final List<ImportOption> options;

    public FileImportDialog(String title, List<String> extensions, List<ImportOption> options) {
        this.title = Objects.requireNonNull(title);
        this.extensions = List.copyOf(extensions);
        this.options = List.copyOf(options);
        if (extensions.isEmpty() || extensions.stream().anyMatch(e -> !e.matches("[a-zA-Z0-9]+")))
            throw new IllegalArgumentException("Invalid extensions");
        var keys = new HashSet<String>();
        for (var option : options)
            if (!keys.add(option.key())) throw new IllegalArgumentException("Duplicate option " + option.key());
    }

    public record Source(@lombok.NonNull Path path, Map<String, String> options) {
        public Source {
            options = Map.copyOf(options);
        }
    }

    public Source settings(Path path, Map<String, String> supplied) {
        var values = new LinkedHashMap<String, String>();
        for (var option : options) values.put(option.key(), option.defaultValue());
        for (var value : supplied.entrySet()) {
            var option = options.stream()
                    .filter(o -> o.key().equals(value.getKey()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown option '" + value.getKey() + "'"));
            option.validate(value.getValue());
            values.put(value.getKey(), value.getValue());
        }
        return new Source(path, values);
    }

    public static ImportWindow source(Path path) {
        return source(path, Map.of());
    }

    public static ImportWindow source(Path path, Map<String, String> options) {
        var values = Map.copyOf(options);
        Objects.requireNonNull(path);
        return new ImportDialogs()
                .register(FileImportDialog.class, request -> Optional.of(request.settings(path, values)));
    }
}
