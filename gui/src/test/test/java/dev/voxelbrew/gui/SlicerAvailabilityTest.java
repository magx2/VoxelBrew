package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.Cancellation;
import dev.voxelbrew.slicer.api.SlicerBackend;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SlicerAvailabilityTest {
    @Test
    void reportsActualVersionInsteadOfAdvertisedVersion() throws Exception {
        var result = SlicerAvailability.check(backend("Installed 3.4.5", false));
        assertTrue(result.working());
        assertEquals("Installed 3.4.5", result.detail());
    }

    @Test
    void rejectsFailedProbeAndMissingVersion() {
        assertThrows(IOException.class, () -> SlicerAvailability.check(backend("3.4.5", true)));
        assertThrows(IOException.class, () -> SlicerAvailability.check(backend("", false)));
    }

    private SlicerBackend backend(String version, boolean fails) {
        return new SlicerBackend() {
            public Descriptor descriptor() {
                return new Descriptor("test", "Test", API_VERSION, "advertised", Set.of(), Set.of("gcode"));
            }

            public Path executable() {
                return Path.of("configured-executable");
            }

            public Probe probe(Path executable, Cancellation cancellation) throws IOException {
                assertEquals(executable(), executable);
                if (fails) throw new IOException("Executable is unavailable");
                return new Probe(version, Set.of());
            }

            public RunResult slice(Request request) {
                throw new AssertionError("Availability must not slice");
            }
        };
    }
}
