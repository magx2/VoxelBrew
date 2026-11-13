package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.importer.DocumentReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentExportTest {
    @TempDir
    Path directory;

    @Test
    @org.junit.jupiter.api.parallel.ResourceLock(org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES)
    void defaultUserPluginsAreDiscoveredAndCanExportWithoutPluginArguments() throws Exception {
        String home = System.getProperty("user.home");
        String override = System.getProperty("voxelbrew.home");
        try {
            System.setProperty("user.home", directory.toString());
            System.clearProperty("voxelbrew.home");
            Path plugins = Files.createDirectories(directory.resolve(".voxelbrew/plugins"));
            Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
            Files.writeString(plugins.resolve("README.txt"), "Ignored non-JAR file");
            for (var listing : java.util.Map.of(
                            "document exporters",
                            "document-probe",
                            "document importers",
                            "plate",
                            "designer feature types",
                            "example.spacer")
                    .entrySet()) {
                var cli = VoxelBrew.commandLine();
                var output = new StringWriter();
                cli.setOut(new PrintWriter(output));
                cli.setErr(new PrintWriter(new StringWriter()));
                assertEquals(0, cli.execute(listing.getKey().split(" ")));
                assertTrue(output.toString().contains(listing.getValue()), output::toString);
            }
            var document = CadDocument.empty();
            Path source = directory.resolve("source.vxb");
            Path target = directory.resolve("report.txt");
            DocumentWriter.save(document, source, false);
            assertEquals(
                    0,
                    VoxelBrew.commandLine()
                            .execute(
                                    "document",
                                    "export",
                                    source.toString(),
                                    "--format",
                                    "document-probe",
                                    "--output",
                                    target.toString()));
            assertEquals("document=" + document.id(), Files.readString(target));
        } finally {
            System.setProperty("user.home", home);
            if (override == null) System.clearProperty("voxelbrew.home");
            else System.setProperty("voxelbrew.home", override);
        }
    }

    @Test
    void daggerRegistryDiscoversAndInvokesAnIndependentDocumentProvider() throws Exception {
        Path plugins = Files.createDirectory(directory.resolve("plugins"));
        Files.copy(Path.of(System.getProperty("voxelbrew.test.plugin")), plugins.resolve("provider.jar"));
        Path source = directory.resolve("source.vxb");
        var document = CadDocument.empty();
        DocumentWriter.save(document, source, false);
        var cli = VoxelBrew.commandLine();
        var output = new StringWriter();
        cli.setOut(new PrintWriter(output));
        cli.setErr(new PrintWriter(new StringWriter()));
        assertEquals(0, cli.execute("document", "exporters", "--plugin-dir", plugins.toString()));
        assertTrue(output.toString().contains("document-probe"));
        Path target = directory.resolve("report.txt");
        assertEquals(
                0,
                cli.execute(
                        "document",
                        "export",
                        source.toString(),
                        "--format",
                        "document-probe",
                        "--plugin-dir",
                        plugins.toString(),
                        "--output",
                        target.toString()));
        assertEquals("document=" + document.id(), Files.readString(target));
    }

    @Test
    void documentFormatsWorkWithoutGeometryAndRequireExplicitOverwrite() throws Exception {
        var document = CadDocument.empty();
        Path source = directory.resolve("source.vxb");
        DocumentWriter.save(document, source, false);
        for (String format : java.util.List.of("vxb", "json")) {
            Path target = directory.resolve("copy." + format);
            var cli = VoxelBrew.commandLine();
            cli.setOut(new PrintWriter(new StringWriter()));
            cli.setErr(new PrintWriter(new StringWriter()));
            String[] args = {"document", "export", source.toString(), "--format", format, "--output", target.toString()
            };
            assertEquals(0, cli.execute(args));
            byte[] original = Files.readAllBytes(target);
            assertNotEquals(0, cli.execute(args));
            assertArrayEquals(original, Files.readAllBytes(target));
            assertEquals(
                    0,
                    cli.execute(
                            "document",
                            "export",
                            source.toString(),
                            "--format",
                            format,
                            "--output",
                            target.toString(),
                            "--overwrite"));
            if (format.equals("vxb"))
                assertEquals(document.id(), DocumentReader.load(target).id());
            else assertTrue(Files.readString(target).contains("\"documentId\""));
        }
    }
}
