package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.util.List;
import java.util.concurrent.Executor;

public final class GcodeImporter extends FileImporter {
    public GcodeImporter(Executor worker) {
        super(worker, "G-code toolpath", List.of("gcode", "gco", "gc"));
    }

    @Override
    protected ImportResult read(InputStream input) throws IOException {
        return new ImportResult.Gcode(GcodeReader.read(input));
    }
}
