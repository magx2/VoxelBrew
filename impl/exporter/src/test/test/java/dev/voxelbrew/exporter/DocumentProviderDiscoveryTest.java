package dev.voxelbrew.exporter;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.api.FileExportDialog;
import java.nio.file.*;
import java.util.jar.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentProviderDiscoveryTest {
    @TempDir
    Path directory;

    @Test
    void newlyInstalledApiOnlyJarIsDiscoveredAndRunsWithoutRestartingTheRegistry() throws Exception {
        var document = CadDocument.empty();
        try (var registry = new ExporterRegistry(directory)) {
            assertFalse(registry.contains("document-probe"));
            Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), directory.resolve("provider.jar"));
            registry.refresh();
            var entry = registry.require("document-probe");
            assertEquals("Document probe", entry.descriptor().name());
            assertNotSame(
                    getClass().getClassLoader(), entry.provider().getClass().getClassLoader());
            Path target = directory.resolve("report.txt");
            entry.provider()
                    .create(Runnable::run)
                    .export(document, FileExportDialog.destination(target, false))
                    .get();
            assertEquals("document=" + document.id(), Files.readString(target));
            registry.refresh();
            assertSame(entry.provider(), registry.require("document-probe").provider());
        }
        // Closing the registry releases the JAR for replacement on Windows.
        Files.delete(directory.resolve("provider.jar"));
    }

    @Test
    void brokenProvidersCannotReplaceBuiltinsOrHideHealthyExporters() throws Exception {
        Path jar = directory.resolve("broken.jar");
        try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new JarEntry("META-INF/services/dev.voxelbrew.exporter.api.ExporterProvider"));
            out.write(("missing.Provider\ndev.voxelbrew.exporter.VoxelBrewExporterProvider\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        try (var registry = new ExporterRegistry(directory)) {
            assertTrue(registry.contains("vxb"));
            assertTrue(registry.contains("json"));
            assertTrue(registry.diagnostics().stream().anyMatch(message -> message.contains("missing.Provider")));
            assertTrue(registry.diagnostics().stream()
                    .anyMatch(message -> message.contains("Duplicate exporter ID: vxb")));
        }
    }
}
