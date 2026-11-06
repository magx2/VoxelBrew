package consumer;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.importer.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** A separate consumer compiled only against published import-api/export-api artifacts. */
public class ProbeImporter implements FileImporter {
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor(
                "plate",
                "External plate",
                API_VERSION,
                List.of("vbplate"),
                ImportResult.Kind.DOCUMENT,
                List.of(
                        new ImportOption(
                                "scale", "Scale", ImportOption.Type.DECIMAL, "1", List.of())));
    }

    public ImportResult read(InputStream input, Map<String, String> options) throws IOException {
        double scale = Double.parseDouble(options.get("scale"));
        if (scale <= 0) throw new IOException("Scale must be positive");
        String[] dimensions =
                new String(input.readAllBytes(), StandardCharsets.UTF_8).strip().split(",");
        if (dimensions.length != 3) throw new IOException("Expected width,height,thickness");
        // Closing a borrowed stream must not take ownership away from the host.
        input.close();
        var sketch =
                new Feature.Sketch(
                        "plate",
                        "profile",
                        new Feature.Plane("origin-xy", 0),
                        new Feature.Rectangle(
                                0,
                                0,
                                Double.parseDouble(dimensions[0]) * scale,
                                Double.parseDouble(dimensions[1]) * scale));
        var extrude =
                new Feature.Extrude(
                        "thickness",
                        new Feature.ProfileRef("plate", "profile"),
                        Double.parseDouble(dimensions[2]) * scale);
        return new ImportResult.Document(
                new CadDocument("imported-plate", List.of(sketch, extrude), 2));
    }

    public static final class Duplicate extends ProbeImporter {}

    public static final class Incompatible extends ProbeImporter {
        @Override
        public ImporterDescriptor descriptor() {
            return new ImporterDescriptor(
                    "future",
                    "Future",
                    999,
                    List.of("vbplate"),
                    ImportResult.Kind.DOCUMENT,
                    List.of());
        }
    }

    public static final class Broken extends ProbeImporter {
        @Override
        public ImporterDescriptor descriptor() {
            throw new AssertionError("Broken descriptor");
        }
    }

    public static class Failing extends ProbeImporter {
        @Override
        public ImporterDescriptor descriptor() {
            return new ImporterDescriptor(
                    "failing",
                    "Failing reader",
                    API_VERSION,
                    List.of("vbplate"),
                    ImportResult.Kind.DOCUMENT,
                    List.of());
        }

        @Override
        public ImportResult read(InputStream input, Map<String, String> options)
                throws IOException {
            input.read();
            throw new IOException("Deliberate importer failure");
        }
    }

    public static final class WrongKind extends Failing {
        @Override
        public ImporterDescriptor descriptor() {
            return new ImporterDescriptor(
                    "wrong-kind",
                    "Wrong result",
                    API_VERSION,
                    List.of("vbplate"),
                    ImportResult.Kind.GCODE,
                    List.of());
        }

        @Override
        public ImportResult read(InputStream input, Map<String, String> options) {
            return new ImportResult.Document(CadDocument.empty());
        }
    }
}
