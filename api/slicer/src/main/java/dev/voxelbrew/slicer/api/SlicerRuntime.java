package dev.voxelbrew.slicer.api;

import java.nio.file.Path;

/** Host services implemented by the runtime slicer pipeline. */
public interface SlicerRuntime {
    SlicerCatalog.Factory slicers();

    Slicing slicing();

    Path defaultSlicerExecutable();

    static SlicerRuntime load() {
        return java.util.ServiceLoader.load(SlicerRuntime.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No slicer runtime installed"));
    }
}
