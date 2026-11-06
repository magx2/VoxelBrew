package dev.voxelbrew.util.update;

import java.io.IOException;
import java.util.Properties;

/** Shared build identity, generated from projectVersion for both frontends. */
public final class BuildVersion {
    private BuildVersion() {}

    public static String current() {
        try (var input = BuildVersion.class.getResourceAsStream("/voxelbrew-util-version.properties")) {
            if (input == null) throw new IOException("Missing VoxelBrew build version");
            var properties = new Properties();
            properties.load(input);
            return ReleaseVersion.parse(properties.getProperty("version")).toString();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read VoxelBrew build version", failure);
        }
    }
}
