package dev.voxelbrew.importer;

import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.util.*;

public final class GcodeImporter implements FileImporter {
    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor(
                "gcode",
                "G-code toolpath",
                API_VERSION,
                List.of("gcode", "gco", "gc"),
                ImportResult.Kind.GCODE,
                List.of());
    }

    @Override
    public ImportResult read(InputStream input, Map<String, String> options) throws IOException {
        return new ImportResult.Gcode(GcodeReader.read(input));
    }
}
