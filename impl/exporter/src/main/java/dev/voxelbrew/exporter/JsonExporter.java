package dev.voxelbrew.exporter;

import java.util.concurrent.Executor;

/** Human-readable export of the complete persisted document schema. */
public final class JsonExporter extends FileExporter {
    public JsonExporter(Executor worker) {
        super("json", "VoxelBrew JSON (*.json)", worker, JsonDocumentWriter::save);
    }
}
