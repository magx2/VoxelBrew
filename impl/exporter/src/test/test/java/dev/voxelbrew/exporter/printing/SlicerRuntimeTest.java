package dev.voxelbrew.exporter.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.Cancellation;
import dev.voxelbrew.slicer.api.SlicerRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SlicerRuntimeTest {
    @TempDir
    Path directory;

    @Test
    void runtimeDiscoversBuiltinsAndIndependentApiOnlyProvider() throws Exception {
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), directory.resolve("provider.jar"));
        var runtime = SlicerRuntime.load();
        assertInstanceOf(DefaultSlicerRuntime.class, runtime);
        assertNotNull(runtime.slicing());
        assertNotNull(runtime.defaultSlicerExecutable());
        try (var catalog = runtime.slicers().open(directory)) {
            assertNotNull(catalog.require("prusa-slicer"));
            var external = catalog.require("probe-slicer");
            assertEquals(
                    "fixture",
                    external.probe(Path.of("unused"), new Cancellation()).actualVersion());
            assertTrue(catalog.diagnostics().isEmpty());
        }
    }
}
