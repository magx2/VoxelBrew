package dev.voxelbrew.util.update;

import java.io.IOException;

/** Creates an updater only after the user requests an update operation. */
@FunctionalInterface
public interface UpdateServiceFactory {
    UpdateService create() throws IOException;
}
