package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.util.config.UserConfiguration;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RenderingTest {
    @TempDir Path directory;

    @Test
    void remembersStyleWithoutChangingOtherPreferencesAndRecoversFromUnknownValues()
            throws Exception {
        var configuration = new UserConfiguration(directory.resolve("config.properties"));
        configuration.set("language", "pl");
        var rendering = new Rendering(configuration);
        assertEquals(Rendering.Style.SHADED, rendering.load());
        rendering.save(Rendering.Style.TECHNICAL);
        assertEquals(Rendering.Style.TECHNICAL, new Rendering(configuration).load());
        assertEquals("pl", configuration.get("language", ""));
        configuration.set("rendering.style", "UNSUPPORTED");
        assertEquals(Rendering.Style.SHADED, rendering.load());
        rendering.save(Rendering.Style.TECHNICAL);
        assertEquals(Rendering.Style.TECHNICAL, rendering.load());
    }
}
