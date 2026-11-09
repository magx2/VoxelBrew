package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.Exporter;
import dev.voxelbrew.exporter.api.ExporterDescriptor;
import dev.voxelbrew.exporter.api.ExporterProvider;
import java.util.concurrent.Executor;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor(onConstructor_ = @javax.inject.Inject)
public final class VoxelBrewExporterProvider implements ExporterProvider {
    private final DocumentOutput documents;

    public VoxelBrewExporterProvider() {
        this(DocumentWriter::save);
    }

    @Override
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("vxb", "VXB");
    }

    @Override
    public Exporter<?> create(Executor worker) {
        return new VoxelBrewExporter(worker, documents);
    }
}
