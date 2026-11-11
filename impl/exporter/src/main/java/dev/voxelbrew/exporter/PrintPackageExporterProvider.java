package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.*;
import java.util.concurrent.Executor;

public final class PrintPackageExporterProvider implements ExporterProvider {
    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("gcode-package", "Sliced printer package");
    }

    @Override
    public Exporter<?> create(Executor worker) {
        return new FileExporter(
                "gcode.3mf", "Sliced printer package (*.gcode.3mf)", worker, (document, path, overwrite) -> {
                    var artifact =
                            document.printArtifacts().get(document.printSetup().activePlateId());
                    if (artifact == null || !artifact.format().equals("gcode.3mf"))
                        throw new java.io.IOException("No sliced package for this plate");
                    AtomicExport.write(
                            new FileExportDialog.Destination(path, overwrite),
                            output -> output.write(artifact.bytes()));
                }) {};
    }
}
