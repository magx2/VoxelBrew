package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.importer.DocumentReader;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocumentImportTest {
    @TempDir
    Path directory;

    @Test
    void daggerImportsVxbAndJsonAndProtectsSourcesAndExistingOutputs() throws Exception {
        var expected = CadDocument.empty();
        for (var format : List.of("vxb", "json")) {
            Path source = directory.resolve("source." + format);
            Path target = directory.resolve(format + "-copy.vxb");
            if (format.equals("vxb")) Files.write(source, DocumentCodec.encode(expected));
            else Files.writeString(source, DocumentCodec.encodeJson(expected));
            byte[] original = Files.readAllBytes(source);
            var cli = VoxelBrew.commandLine();
            cli.setOut(new PrintWriter(new StringWriter()));
            cli.setErr(new PrintWriter(new StringWriter()));
            String[] args = {"import", source.toString(), "--format", format, "--output", target.toString()};
            assertEquals(0, cli.execute(args));
            assertEquals(expected.id(), DocumentReader.load(target).id());
            byte[] output = Files.readAllBytes(target);
            assertNotEquals(0, cli.execute(args));
            assertArrayEquals(output, Files.readAllBytes(target));
            assertNotEquals(
                    0,
                    cli.execute(
                            "import",
                            source.toString(),
                            "--format",
                            format,
                            "--output",
                            source.toString(),
                            "--overwrite"));
            assertArrayEquals(original, Files.readAllBytes(source));
        }
    }
}
