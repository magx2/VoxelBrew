package dev.voxelbrew.exporter;

import java.util.concurrent.Executor;

/** Editable binary document exporter; retains schema and overwrite protections. */
public final class JcadExporter extends FileExporter {
    public JcadExporter(Executor worker) {
        this(worker, DocumentWriter::save);
    }

    public JcadExporter(Executor worker, DocumentOutput writer) {
        super("vxb", "VoxelBrew design (*.vxb)", worker, writer);
    }
}
