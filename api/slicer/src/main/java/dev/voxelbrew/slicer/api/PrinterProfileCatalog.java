package dev.voxelbrew.slicer.api;

import dev.voxelbrew.engine.printing.PrinterProfile;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Reloadable printer presets supplied by the slicer runtime. */
public interface PrinterProfileCatalog {
    public record Entry(PrinterProfile definition, String image, String source, boolean bundled) {
        @Override
        public String toString() {
            return definition.manufacturer() + " " + definition.printer().name();
        }
    }

    public record Result(List<Entry> entries, List<String> diagnostics) {
        public Result {
            entries = List.copyOf(entries);
            diagnostics = List.copyOf(diagnostics);
        }

        public PrinterProfile require(String id) {
            return entries.stream()
                    .filter(e -> e.definition().printer().id().equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Missing printer preset: " + id))
                    .definition();
        }
    }

    Path directory();

    Result load();

    int exportDefaults(Set<String> selectedIds) throws IOException;

    /** Bundled illustration URL used when a printer image is unavailable. */
    String fallbackImage();
}
