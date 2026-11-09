package dev.voxelbrew.exporter.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/** File-specific save interaction, including explicit overwrite consent. */
@lombok.Getter
@lombok.experimental.Accessors(fluent = true)
public final class FileExportDialog extends ExportDialog<FileExportDialog.Destination> {
    private final String title;
    private final String extension;

    public FileExportDialog(String title, String extension) {
        this.title = Objects.requireNonNull(title, "title");
        if (extension == null || !extension.matches("[a-zA-Z0-9]+"))
            throw new IllegalArgumentException("Invalid extension");
        this.extension = extension;
    }

    public record Destination(Path path, boolean overwrite) {
        public Destination {
            Objects.requireNonNull(path, "path");
        }
    }

    /** Noninteractive file handler using already-parsed command-line arguments. */
    public static ExportWindow destination(Path path, boolean overwrite) {
        var selected = new Destination(path, overwrite);
        return new ExportDialogs().register(FileExportDialog.class, request -> Optional.of(selected));
    }
}
