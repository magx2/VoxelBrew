package dev.voxelbrew.exporter;

import java.nio.file.Path;
import java.util.concurrent.Executor;

/** Exact CAD exchange with STEP AP242; exports complete design solids in millimeters. */
public final class StepExporter extends FileExporter {
    public StepExporter(Executor worker, Path pluginDirectory) {
        super(
                "step",
                "STEP AP242 (*.step)",
                worker,
                (document, destination, overwrite) ->
                        StepDocumentWriter.save(document, destination, overwrite, pluginDirectory));
    }
}
