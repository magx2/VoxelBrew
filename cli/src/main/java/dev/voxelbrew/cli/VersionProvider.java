package dev.voxelbrew.cli;

import dev.voxelbrew.engine.persistence.DocumentStore;
import java.io.IOException;
import java.util.Properties;
import picocli.CommandLine;

/** Product version generated from the same Gradle property as the release archives. */
public final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() throws IOException {
        var properties = new Properties();
        try (var input =
                VersionProvider.class.getResourceAsStream("/voxelbrew-version.properties")) {
            if (input == null) throw new IOException("Missing VoxelBrew build version");
            properties.load(input);
        }
        return new String[] {
            "VoxelBrew "
                    + properties.getProperty("version")
                    + " / jcad schema "
                    + DocumentStore.SCHEMA_VERSION
        };
    }
}
