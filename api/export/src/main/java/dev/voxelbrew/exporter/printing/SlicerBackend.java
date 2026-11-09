package dev.voxelbrew.exporter.printing;

import dev.voxelbrew.engine.printing.Cancellation;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Versioned plugin SPI. Providers write only the private output supplied by the host. */
public interface SlicerBackend {
    int API_VERSION = 2;

    enum Capability {
        AUTOMATIC_SUPPORTS,
        BUILD_PLATE_SUPPORTS,
        SUPPORT_VOLUMES,
        BRIM,
        RAFT,
        ADAPTIVE_LAYERS,
        GENERATED_SUPPORT_MESH,
        ORGANIC_SUPPORTS,
        PAINTING,
        MULTI_MATERIAL
    }

    record Descriptor(
            String id,
            String name,
            int apiVersion,
            String version,
            Set<Capability> capabilities,
            Set<String> outputFormats) {
        public Descriptor {
            capabilities = Set.copyOf(capabilities);
            outputFormats = Set.copyOf(outputFormats);
        }
    }

    record Probe(String actualVersion, Set<Capability> capabilities) {
        public Probe {
            capabilities = Set.copyOf(capabilities);
        }
    }

    record Request(
            ManufacturingModel model,
            Path input,
            Path executable,
            Path workingDirectory,
            Path output,
            Cancellation cancellation,
            Consumer<String> diagnostics) {}

    record RunResult(Map<String, String> resolvedSettings) {
        public RunResult {
            resolvedSettings = Map.copyOf(resolvedSettings);
        }
    }

    Descriptor descriptor();

    /** Format exported by the host before this backend is invoked. */
    record InputFormat(String exporterId, String extension, Map<String, String> options) {
        public InputFormat(String exporterId, String extension) {
            this(exporterId, extension, Map.of());
        }

        public InputFormat {
            options = Map.copyOf(options);
            if (exporterId == null
                    || !exporterId.matches("[a-z0-9][a-z0-9.-]*")
                    || extension == null
                    || !extension.matches("[a-zA-Z0-9]+"))
                throw new IllegalArgumentException("Invalid slicer input format");
        }
    }

    default InputFormat inputFormat() {
        return new InputFormat("stl", "stl");
    }

    Probe probe(Path executable, Cancellation cancellation) throws IOException;

    RunResult slice(Request request) throws IOException;
}
