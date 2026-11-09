package dev.voxelbrew.cli;

import dev.voxelbrew.exporter.api.DocumentSink;
import dev.voxelbrew.importer.*;
import dev.voxelbrew.importer.api.ImportResult;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.*;

final class ImportCommands {
    private ImportCommands() {}

    static void register(CommandLine command) {
        command.addSubcommand("importers", new CommandLine(Importers.class, command.getFactory()));
        command.addSubcommand("import", new CommandLine(ImportFile.class, command.getFactory()));
    }

    @Command(name = "importers", mixinStandardHelpOptions = true, description = "List built-in and external importers")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @javax.inject.Inject)
    static final class Importers implements Callable<Integer> {
        private final ImporterRegistry.Factory importers;

        @Option(names = "--plugin-dir")
        Path plugins = ImporterRegistry.defaultDirectory();

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = importers.open(plugins)) {
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                for (var descriptor : registry.descriptors()) {
                    spec.commandLine()
                            .getOut()
                            .println(descriptor.id()
                                    + "\t"
                                    + descriptor.name()
                                    + "\t"
                                    + descriptor.resultKind()
                                    + "\t"
                                    + String.join(",", descriptor.extensions()));
                    for (var option : descriptor.options())
                        spec.commandLine()
                                .getOut()
                                .println(
                                        "  " + option.key() + "=" + option.defaultValue() + " (" + option.type() + ")");
                }
            }
            return 0;
        }
    }

    @Command(
            name = "import",
            mixinStandardHelpOptions = true,
            description = "Import a document to .jcad or inspect an imported toolpath")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @javax.inject.Inject)
    static final class ImportFile implements Callable<Integer> {
        private final ImporterRegistry.Factory importers;
        private final DocumentSink documents;

        @Parameters(index = "0")
        Path source;

        @Option(names = "--format", required = true)
        String format;

        @Option(names = "--output", description = "Destination .jcad for document importers")
        Path output;

        @Option(names = "--option", split = ",")
        Map<String, String> options = new LinkedHashMap<>();

        @Option(names = "--plugin-dir")
        Path plugins = ImporterRegistry.defaultDirectory();

        @Option(names = "--overwrite")
        boolean overwrite;

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = importers.open(plugins)) {
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                var kind = registry.require(format).descriptor().resultKind();
                if (kind == ImportResult.Kind.DOCUMENT && output == null)
                    throw new IllegalArgumentException("Document import requires --output");
                if (kind == ImportResult.Kind.GCODE && output != null)
                    throw new IllegalArgumentException(
                            "Toolpath import produces a preview, not an editable .jcad document; omit --output");
                if (output != null
                        && (source.toAbsolutePath()
                                        .normalize()
                                        .equals(output.toAbsolutePath().normalize())
                                || java.nio.file.Files.exists(output)
                                        && java.nio.file.Files.isSameFile(source, output)))
                    throw new IllegalArgumentException("Import destination must differ from its source");
                var result = ImportService.read(registry, format, source, options);
                if (result instanceof ImportResult.Document document) {
                    documents.save(document.document(), output, overwrite);
                    spec.commandLine()
                            .getOut()
                            .println("Imported " + document.document().id() + " to " + output);
                } else if (result instanceof ImportResult.Gcode gcode) {
                    spec.commandLine()
                            .getOut()
                            .println("model_layers="
                                    + gcode.job().modelLayers().size()
                                    + " deposition_moves="
                                    + gcode.job().segments().stream()
                                            .filter(dev.voxelbrew.engine.printing.GcodeJob.Segment::deposits)
                                            .count());
                }
            }
            return 0;
        }
    }
}
