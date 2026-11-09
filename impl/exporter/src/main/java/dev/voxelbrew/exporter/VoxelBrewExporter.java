package dev.voxelbrew.exporter;

import java.util.concurrent.Executor;

/** Editable binary document exporter; retains schema and overwrite protections. */
public final class VoxelBrewExporter extends FileExporter {
    public VoxelBrewExporter(Executor worker) {
        this(worker, DocumentWriter::save);
    }

    public VoxelBrewExporter(Executor worker, DocumentOutput writer) {
        super("vxb", "VoxelBrew design (*.vxb)", worker, writer);
    }
}
