package dev.voxelbrew.slicer.cura;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CuraSlicerBackendTest {
    @TempDir
    Path directory;

    @Test
    void curaSettingsRespectDocumentAndRejectUnsupportedInstructions() throws Exception {
        var setup = PrintSetup.empty();
        var model = new ManufacturingModel("document", "revision", setup, PrintProfile.resolve(setup), Map.of());
        Path file = directory.resolve("cura settings.properties");
        Files.writeString(file, "speed_print=35\nlayer_height=9\nsupport_enable=true\n");
        var backend = (CuraSlicerBackend) new CuraSlicerBackend().configure(Map.of("settings", file.toString()));
        var settings = backend.settings(model);
        assertEquals("35", settings.get("speed_print"));
        assertEquals("0.2", settings.get("layer_height"));
        assertEquals("false", settings.get("support_enable"));
        assertEquals("false", settings.get("center_object"));
        assertThrows(UnsupportedOperationException.class, () -> settings.put("layer_height", "1"));
        assertThrows(
                java.io.IOException.class,
                () -> backend.slice(new dev.voxelbrew.slicer.api.SlicerBackend.Request(
                        model,
                        file,
                        Path.of("unused"),
                        directory,
                        directory.resolve("out.gcode"),
                        new Cancellation(),
                        line -> {})));
    }
}
