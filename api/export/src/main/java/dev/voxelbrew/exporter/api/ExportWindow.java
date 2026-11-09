package dev.voxelbrew.exporter.api;

import java.util.Optional;

/** Parent window abstraction shared by desktop dialogs and noninteractive CLI exports. */
public interface ExportWindow {
    /** Presents an exporter-defined interaction; empty means user cancellation.
     * Hosts must reject unsupported dialogs explicitly rather than treating them as cancelled.
     * Implementations must invoke dialog.collectSettings around the interaction so getSettings
     * exposes only the newly confirmed result. ExportDialogs provides this lifecycle automatically.
     */
    <R> Optional<R> show(ExportDialog<R> dialog);
}
