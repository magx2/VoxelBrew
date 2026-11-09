package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.Exporter;
import dev.voxelbrew.exporter.api.ExporterDescriptor;
import dev.voxelbrew.exporter.api.ExporterProvider;
import java.util.concurrent.Executor;

public final class JsonExporterProvider implements ExporterProvider {
    @javax.inject.Inject
    public JsonExporterProvider() {}

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("json", "JSON");
    }

    @Override
    public Exporter<?> create(Executor worker) {
        return new JsonExporter(worker);
    }
}
