package dev.voxelbrew.util.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Release transport boundary; selection and installation remain util-owned. */
public interface ReleaseSource {
    record Release(ReleaseVersion version, Set<String> assets) {
        public Release {
            if (version.snapshot())
                throw new IllegalArgumentException("A published release cannot be a snapshot");
            assets = Set.copyOf(assets);
        }

        public String tag() {
            return "v" + version;
        }
    }

    Release fetch(ReleaseVersion requested, UpdateCancellation cancellation) throws IOException;

    void download(Release release, String asset, Path destination, UpdateCancellation cancellation)
            throws IOException;
}
