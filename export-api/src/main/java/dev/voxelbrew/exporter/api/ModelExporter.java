package dev.voxelbrew.exporter.api;

import dev.voxelbrew.engine.mesh.MeshOptions;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;

/** Version 1 SPI. Providers do not own the model or stream and must not retain either. */
public interface ModelExporter {
    int API_VERSION = 1;

    ExporterDescriptor descriptor();

    void write(
            ModelView model,
            MeshOptions meshOptions,
            Map<String, String> options,
            OutputStream output)
            throws IOException;
}
