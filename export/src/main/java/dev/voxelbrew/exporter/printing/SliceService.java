package dev.voxelbrew.exporter.printing;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.*;
import dev.voxelbrew.exporter.api.*;
import dev.voxelbrew.importer.GcodeReader;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.slicer.api.Slicing.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

@lombok.extern.slf4j.Slf4j
public final class SliceService {
    private SliceService() {}

    /** Standalone composition for scripts/tests; application roots supply their registry. */
    public static Result slice(
            SlicerBackend backend,
            CadDocument document,
            ManufacturingModel model,
            Path executable,
            Path destination,
            boolean overwrite,
            Cancellation cancellation,
            Consumer<String> diagnostics,
            PublicationGuard guard)
            throws IOException {
        try (var exporters = new ExporterRegistry(ExporterRegistry.defaultDirectory())) {
            return slice(
                    backend,
                    document,
                    model,
                    exporters,
                    executable,
                    destination,
                    overwrite,
                    cancellation,
                    diagnostics,
                    guard);
        }
    }

    public static Result slice(
            SlicerBackend backend,
            CadDocument document,
            ManufacturingModel model,
            ExporterCatalog exporters,
            Path executable,
            Path destination,
            boolean overwrite,
            Cancellation cancellation,
            Consumer<String> diagnostics,
            PublicationGuard guard)
            throws IOException {
        cancellation.check();
        PrintValidation.validate(model).requireValid();
        var descriptor = backend.descriptor();
        if (!descriptor.id().equals(model.setup().backendId()))
            throw new IOException("Selected backend does not match print setup");
        if (!descriptor.outputFormats().contains(model.profile().printer().outputFormat()))
            throw new IOException("Backend cannot produce required printer format "
                    + model.profile().printer().outputFormat());
        if (!model.profile().printer().outputFormat().equals("gcode"))
            throw new IOException("No independent job inspector for selected output format");
        if (!destination.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gcode"))
            throw new IOException("The selected printer requires a .gcode destination");
        Path target = destination.toAbsolutePath().normalize();
        if (Files.exists(target) && !overwrite)
            throw new IOException("Destination exists; explicit overwrite required: " + target);
        Path command =
                executable.getParent() != null || Files.exists(executable) ? executable.toAbsolutePath() : executable;
        var probe = backend.probe(command, cancellation);
        var required = java.util.EnumSet.noneOf(SlicerBackend.Capability.class);
        var settings = model.profile().process();
        if (settings.automaticSupports()) required.add(SlicerBackend.Capability.AUTOMATIC_SUPPORTS);
        if (settings.supportsOnBedOnly()) required.add(SlicerBackend.Capability.BUILD_PLATE_SUPPORTS);
        if (settings.brimWidth() > 0) required.add(SlicerBackend.Capability.BRIM);
        if (settings.raftLayers() > 0) required.add(SlicerBackend.Capability.RAFT);
        if (model.setup().supports().stream().anyMatch(s -> s.role() != SupportVolume.Role.SOLID_SUPPORT))
            required.add(SlicerBackend.Capability.SUPPORT_VOLUMES);
        if (!descriptor.capabilities().containsAll(required)
                || !probe.capabilities().containsAll(required))
            throw new IOException("Backend does not support the requested process capabilities: " + required);
        diagnostics.accept("Backend: " + probe.actualVersion());
        log.info(
                "Slicing document {} revision {} using {} ({})",
                model.documentId(),
                model.revision(),
                descriptor.id(),
                probe.actualVersion());
        Path work = Files.createTempDirectory(target.getParent(), ".voxelbrew-slice-");
        try {
            Path raw = work.resolve("sliced.gcode"), complete = work.resolve("complete.gcode");
            var format = backend.inputFormat();
            Path input = work.resolve("input." + format.extension());
            var parent = new ExportDialogs()
                    .register(
                            FileExportDialog.class,
                            request -> Optional.of(new FileExportDialog.Destination(input, false)))
                    .register(
                            MeshExportDialog.class,
                            request -> Optional.of(new MeshExportDialog.Settings(
                                    new ExportSelection(
                                            ExportSelection.Space.BUILD_PLATE,
                                            ExportSelection.Contents.MODEL,
                                            null,
                                            null),
                                    MeshOptions.DEFAULT,
                                    format.options(),
                                    new FileExportDialog.Destination(input, false),
                                    model)));
            try {
                exporters
                        .require(format.exporterId())
                        .provider()
                        .create(Runnable::run, exporters.directory())
                        .export(document, parent)
                        .join();
            } catch (java.util.concurrent.CompletionException failure) {
                if (failure.getCause() instanceof java.util.concurrent.CancellationException cancelled) throw cancelled;
                throw new IOException("Slicer input export failed: " + format.exporterId(), failure.getCause());
            }
            cancellation.check();
            if (!Files.isRegularFile(input)) throw new IOException("Exporter did not create slicer input: " + input);
            var run = backend.slice(
                    new SlicerBackend.Request(model, input, command, work, raw, cancellation, diagnostics));
            cancellation.check();
            var inspected = GcodeReader.read(raw);
            if (inspected.modelLayers().isEmpty())
                throw new IOException("Slicer output has no identifiable model deposition layers");
            var report = new LinkedHashMap<String, String>();
            report.put("document", model.documentId());
            report.put("revision", model.revision());
            report.put("backend", descriptor.id());
            report.put("backend_version", probe.actualVersion());
            report.put("printer", model.profile().printer().id());
            report.put("nozzle_mm", Double.toString(model.profile().printer().nozzleMm()));
            report.put("material", model.profile().material().id());
            report.put("firmware", model.profile().printer().firmware());
            report.put("format", model.profile().printer().outputFormat());
            report.put("units", "millimeter");
            report.put("model_layers", Integer.toString(inspected.modelLayers().size()));
            report.put("hardware_verified", "false");
            for (var setting : new TreeMap<>(run.resolvedSettings()).entrySet())
                report.put("process." + setting.getKey(), setting.getValue());
            try (var output = Files.newBufferedWriter(complete, StandardCharsets.UTF_8)) {
                for (var entry : report.entrySet())
                    output.write("; VOXELBREW "
                            + entry.getKey()
                            + " = "
                            + entry.getValue().replace("\r", "\\r").replace("\n", "\\n")
                            + "\n");
                try (var rawInput = Files.newBufferedReader(raw, StandardCharsets.UTF_8)) {
                    rawInput.transferTo(output);
                }
                output.write("\n; VOXELBREW complete = true\n");
            }
            try (var channel = FileChannel.open(complete, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            var job = GcodeReader.read(complete);
            guard.publish(
                    model.revision(),
                    () -> cancellation.publish(() -> {
                        if (overwrite)
                            Files.move(
                                    complete,
                                    target,
                                    StandardCopyOption.ATOMIC_MOVE,
                                    StandardCopyOption.REPLACE_EXISTING);
                        else Files.createLink(target, complete);
                    }));
            log.info(
                    "Published sliced job {} with {} model layers",
                    target,
                    job.modelLayers().size());
            return new Result(target, job, probe.actualVersion());
        } catch (java.util.concurrent.CancellationException cancelled) {
            log.info("Slicing cancelled for document {}", model.documentId());
            throw cancelled;
        } catch (OutdatedException outdated) {
            log.warn("Discarded sliced job for outdated revision {}", model.revision());
            throw outdated;
        } catch (IOException | RuntimeException failure) {
            log.error("Slicing failed for document {}", model.documentId(), failure);
            throw failure;
        } finally {
            try (var files = Files.walk(work)) {
                for (var path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
}
