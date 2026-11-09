package consumer;

import dev.voxelbrew.engine.printing.Cancellation;
import dev.voxelbrew.slicer.api.SlicerBackend;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Independent API-only consumer; intentionally does not execute an external process. */
public final class ProbeSlicer implements SlicerBackend {
    public Descriptor descriptor() {
        return new Descriptor("probe-slicer", "API fixture slicer", API_VERSION, "1", Set.of(), Set.of("gcode"));
    }

    public Probe probe(Path executable, Cancellation cancellation) {
        cancellation.check();
        return new Probe("fixture", Set.of());
    }

    public RunResult slice(Request request) throws IOException {
        throw new IOException("Fixture slicer does not produce jobs");
    }
}
