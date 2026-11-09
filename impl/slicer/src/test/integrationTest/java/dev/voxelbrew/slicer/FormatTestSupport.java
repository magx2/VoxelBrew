package dev.voxelbrew.slicer;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.api.FileExportDialog;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

/** Exercise serializers against selected synthetic views without inventing CAD history. */
public final class FormatTestSupport {
    private FormatTestSupport() {}

    public static void write(
            ExporterRegistry registry,
            String format,
            ModelView view,
            MeshOptions mesh,
            Map<String, String> options,
            Path target,
            boolean overwrite)
            throws IOException {
        var exporter = (MeshExporter) registry.require(format).provider().create(Runnable::run, registry.directory());
        exporter.writeFile(
                view,
                new MeshExportDialog.Settings(
                        ExportSelection.design(null),
                        mesh,
                        options,
                        new FileExportDialog.Destination(target, overwrite)));
    }
}
