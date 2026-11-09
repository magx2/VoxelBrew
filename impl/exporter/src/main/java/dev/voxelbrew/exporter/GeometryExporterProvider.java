package dev.voxelbrew.exporter;

import dev.voxelbrew.exporter.api.*;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.function.BiFunction;

/** Built-in constructor binding; external JARs implement ExporterProvider directly. */
public record GeometryExporterProvider(
        ExporterDescriptor descriptor, BiFunction<Executor, Path, ? extends Exporter<?>> factory)
        implements ExporterProvider {
    @Override
    public Exporter<?> create(Executor worker) {
        return create(worker, ExporterRegistry.defaultDirectory());
    }

    @Override
    public Exporter<?> create(Executor worker, Path plugins) {
        return factory.apply(worker, plugins);
    }
}
