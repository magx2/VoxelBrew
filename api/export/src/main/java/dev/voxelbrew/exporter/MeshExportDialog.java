package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import dev.voxelbrew.exporter.api.ExportDialog;
import dev.voxelbrew.exporter.api.FileExportDialog;
import java.util.Map;
import java.util.Objects;

/** Options request owned by the built-in geometry exporters, not a plugin SPI. */
@lombok.Getter
@lombok.experimental.Accessors(fluent = true)
@lombok.RequiredArgsConstructor
public final class MeshExportDialog extends ExportDialog<MeshExportDialog.Settings> {
    @lombok.NonNull
    private final MeshFormat format;

    @Override
    public String title() {
        return format.name();
    }

    public record Settings(
            ExportSelection selection,
            MeshOptions mesh,
            Map<String, String> options,
            FileExportDialog.Destination destination,
            ManufacturingModel snapshot) {
        public Settings {
            Objects.requireNonNull(selection);
            Objects.requireNonNull(mesh);
            Objects.requireNonNull(destination);
            options = Map.copyOf(options);
        }

        public Settings(
                ExportSelection selection,
                MeshOptions mesh,
                Map<String, String> options,
                FileExportDialog.Destination destination) {
            this(selection, mesh, options, destination, null);
        }
    }
}
