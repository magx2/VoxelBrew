package dev.voxelbrew.slicer;

import dev.voxelbrew.slicer.api.*;
import dev.voxelbrew.slicer.api.SlicerCatalog;
import dev.voxelbrew.slicer.api.Slicing;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.nio.file.Path;

/** Runtime implementation; the public API has no dependency on this module. */
public final class DefaultSlicerRuntime implements SlicerRuntime {
    @Override
    public SlicerCatalog.Factory slicers() {
        return SlicerRegistry::new;
    }

    @Override
    public Slicing slicing() {
        return SliceService::slice;
    }

    @Override
    public PrinterProfileCatalog printers(Path directory) {
        return new DefaultPrinterProfileCatalog(new PrinterResources(directory));
    }

    @Override
    public Path defaultSlicerExecutable() {
        return PrusaSlicerBackend.defaultExecutable();
    }
}
