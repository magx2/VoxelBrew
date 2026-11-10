package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.*;
import java.util.concurrent.Executor;

public final class GcodeExporterProvider implements ExporterProvider {
    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("gcode", "G-code");
    }

    @Override
    public Exporter<?> create(Executor worker) {
        return new FileExporter("gcode", "G-code (*.gcode)", worker, (document, path, overwrite) -> {
            var lines = document.activeGcode();
            if (lines.isEmpty()) throw new java.io.IOException("No G-code for the active plate; slice it first");
            AtomicExport.write(new FileExportDialog.Destination(path, overwrite), output -> {
                var writer = new java.io.OutputStreamWriter(output, java.nio.charset.StandardCharsets.UTF_8);
                for (String line : lines) {
                    writer.write(line);
                    writer.write('\n');
                }
                writer.flush();
            });
        }) {};
    }
}
