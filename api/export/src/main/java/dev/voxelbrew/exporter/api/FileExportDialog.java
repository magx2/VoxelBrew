package dev.voxelbrew.exporter.api;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Standard request for a local output path and explicit overwrite consent.
 *
 * <p>Use this request when a destination is the only setting needed. Exporters needing additional
 * settings may define another {@link ExportDialog} type and arrange matching host handlers. This
 * request neither writes files nor reserves a destination: the exporter must validate the path,
 * honor overwrite consent even if another process creates a file later, and publish safely.
 */
@lombok.Getter
@lombok.experimental.Accessors(fluent = true)
public final class FileExportDialog extends ExportDialog<FileExportDialog.Destination> {
    /**
     * Title shown by a file chooser or prompt.
     *
     * @return human-readable interaction title
     */
    private final String title;
    /**
     * Suggested filename extension and host filter, without a leading dot.
     *
     * @return nonempty ASCII alphanumeric extension; original case is preserved
     */
    private final String extension;

    /**
     * Creates a file request; no filesystem access or dialog interaction occurs here.
     *
     * @param title title for the host's file interaction
     * @param extension suggested extension without a dot, such as {@code vxb} or {@code stl}
     * @throws NullPointerException if the title is null
     * @throws IllegalArgumentException if the extension is null, empty or not ASCII alphanumeric
     */
    public FileExportDialog(String title, String extension) {
        this.title = Objects.requireNonNull(title, "title");
        if (extension == null || !extension.matches("[a-zA-Z0-9]+"))
            throw new IllegalArgumentException("Invalid extension");
        this.extension = extension;
    }

    /**
     * Accepted file destination and replacement policy.
     *
     * <p>The path is retained as supplied, without normalization, extension insertion or existence
     * checks. Consent to overwrite permits replacement; it does not require the file to exist.
     * Exporters remain responsible for safe publication and preserving existing data on failure.
     *
     * @param path output path, which may be relative
     * @param overwrite whether replacing an existing output is authorized
     */
    public record Destination(Path path, boolean overwrite) {
        /**
         * Creates destination settings without accessing the filesystem.
         *
         * @param path output path, retained unchanged
         * @param overwrite whether replacement is authorized
         * @throws NullPointerException if the path is null
         */
        public Destination {
            Objects.requireNonNull(path, "path");
        }
    }

    /**
     * Creates a headless host that always confirms this destination for file requests.
     *
     * <p>Useful for parsed CLI arguments and tests. The host supports only {@link FileExportDialog};
     * other request types fail explicitly. It does not show a prompt, append an extension or check
     * the filesystem. For multiple request types, configure an {@link ExportDialogs} registry.
     *
     * @param path destination to return for every file request
     * @param overwrite explicit replacement policy
     * @return host that records and returns the supplied destination on confirmation
     * @throws NullPointerException if the path is null
     */
    public static ExportWindow destination(Path path, boolean overwrite) {
        var selected = new Destination(path, overwrite);
        return new ExportDialogs().register(FileExportDialog.class, request -> Optional.of(selected));
    }
}
