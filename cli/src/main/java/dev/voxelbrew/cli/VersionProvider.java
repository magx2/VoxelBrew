package dev.voxelbrew.cli;

import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.util.update.BuildVersion;
import picocli.CommandLine;

/** Product version generated from the same Gradle property as the release archives. */
public final class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[] {"VoxelBrew " + BuildVersion.current() + " / vxb schema " + DocumentCodec.SCHEMA_VERSION};
    }
}
