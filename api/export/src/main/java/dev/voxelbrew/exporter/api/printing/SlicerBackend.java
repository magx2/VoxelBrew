package dev.voxelbrew.exporter.api.printing;

import dev.voxelbrew.engine.printing.Cancellation;
import dev.voxelbrew.engine.printing.ManufacturingModel;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Versioned plugin SPI. Providers write only the private output supplied by the host. */
public interface SlicerBackend {
    int API_VERSION = 1;

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

    Probe probe(Path executable, Cancellation cancellation) throws IOException;

    RunResult slice(Request request) throws IOException;
}
