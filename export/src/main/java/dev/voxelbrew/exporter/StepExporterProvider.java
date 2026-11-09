package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.*;
import java.nio.file.Path;
import java.util.concurrent.Executor;

public final class StepExporterProvider implements ExporterProvider {
    @javax.inject.Inject
    public StepExporterProvider() {}

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("step", "STEP AP242 (exact solids)", API_VERSION);
    }

    @Override
    public Exporter<?> create(Executor worker) {
        return create(worker, dev.voxelbrew.engine.feature.FeatureRegistry.defaultDirectory());
    }

    @Override
    public Exporter<?> create(Executor worker, Path pluginDirectory) {
        return new StepExporter(worker, pluginDirectory);
    }
}
