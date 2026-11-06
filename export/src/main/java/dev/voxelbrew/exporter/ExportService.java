package dev.voxelbrew.exporter;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.exporter.api.ModelView;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceConfigurationError;

@lombok.extern.slf4j.Slf4j
public final class ExportService {
    private ExportService() {}

    public static void export(
            ExporterRegistry registry,
            String format,
            ModelView view,
            MeshOptions meshOptions,
            Map<String, String> supplied,
            Path destination,
            boolean overwrite)
            throws IOException {
        var entry = registry.require(format);
        var descriptor = entry.descriptor();
        if (view.instances().isEmpty()) throw new IllegalArgumentException("Nothing to export");
        if (!descriptor.multipleInstances() && view.instances().size() != 1)
            throw new IllegalArgumentException("Exporter " + format + " requires one selected body/instance");
        var options = new LinkedHashMap<String, String>();
        for (var option : descriptor.options()) options.put(option.key(), option.defaultValue());
        for (var option : supplied.entrySet()) {
            var spec = descriptor.options().stream()
                    .filter(o -> o.key().equals(option.getKey()))
                    .findFirst()
                    .orElseThrow(() ->
                            new IllegalArgumentException("Unknown option '" + option.getKey() + "' for " + format));
            spec.validate(option.getValue());
            options.put(option.getKey(), option.getValue());
        }
        Path target = destination.toAbsolutePath();
        if (Files.exists(target) && !overwrite)
            throw new IOException("Destination exists; explicit overwrite required: " + target);
        Path temporary = Files.createTempFile(target.getParent(), ".voxelbrew-export-", ".tmp");
        log.info("Exporting {} instances as {} to {}", view.instances().size(), format, target);
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE);
                    var stream = Channels.newOutputStream(channel)) {
                // A provider cannot close the host-owned channel before the durability flush.
                OutputStream borrowed = new FilterOutputStream(stream) {
                    @Override
                    public void close() throws IOException {
                        flush();
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        out.write(b, off, len);
                    }
                };
                entry.provider().write(view, meshOptions, Map.copyOf(options), borrowed);
                borrowed.flush();
                channel.force(true);
            }
            if (overwrite)
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.createLink(target, temporary);
            log.info("Exported {} to {}", format, target);
        } catch (IOException | RuntimeException | LinkageError | ServiceConfigurationError | AssertionError failure) {
            log.error("Export failed for {} using {}", target, format, failure);
            throw new IOException("Exporter '" + format + "' failed: " + failure.getMessage(), failure);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
