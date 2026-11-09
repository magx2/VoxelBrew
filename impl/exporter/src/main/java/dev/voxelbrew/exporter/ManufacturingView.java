package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.printing.ManufacturingModel;

/**
 * Export input for a complete build-plate setup. The detached manufacturing snapshot includes
 * printer/material/process profiles and support instructions in addition to the model meshes.
 * Filtered body, instance or support-only views do not provide this capability.
 */
public interface ManufacturingView extends ModelView {
    ManufacturingModel manufacturingModel();
}
