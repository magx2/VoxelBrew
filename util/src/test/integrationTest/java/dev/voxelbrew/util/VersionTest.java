package dev.voxelbrew.util;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class VersionTest {
    @Test
    void ideClassesReturnDevelopmentVersionEvenWithProcessedResources() {
        assertNotNull(Version.class.getResource("/voxelbrew-util-version.properties"));
        assertEquals("0.0.0", Version.version());
    }

    @Test
    void builtJarReturnsGradleVersion() throws Exception {
        var artifact = Path.of(System.getProperty("voxelbrew.test.utilJar"));
        try (var loader =
                new URLClassLoader(new URL[] {artifact.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var version = loader.loadClass("dev.voxelbrew.util.Version");
            assertEquals(
                    System.getProperty("voxelbrew.test.buildVersion"),
                    version.getMethod("version").invoke(null));
        }
    }
}
