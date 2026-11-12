package dev.voxelbrew.cli;

import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.importer.api.*;
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
        private final ImporterCatalog.Factory importers;

        @Option(names = "--plugin-dir")
        Path plugins = dev.voxelbrew.util.config.ApplicationPaths.plugins();

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = importers.open(plugins)) {
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                for (var descriptor : registry.descriptors()) {
                    spec.commandLine()
                            .getOut()
                            .println(descriptor.id() + "\t" + descriptor.name() + "\t" + descriptor.resultKind());
                }
            }
            return 0;
        }
    }

    @Command(
            name = "import",
            mixinStandardHelpOptions = true,
            description = "Import a document to .vxb or inspect an imported toolpath")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @javax.inject.Inject)
    static final class ImportFile implements Callable<Integer> {
        private final ImporterCatalog.Factory importers;
        private final DocumentOutput documents;

        @Parameters(index = "0")
        Path source;

        @Option(names = "--format", required = true)
        String format;

        @Option(names = "--output", description = "Destination .vxb for document importers")
        Path output;

        @Option(names = "--option", split = ",")
        Map<String, String> options = new LinkedHashMap<>();

        @Option(names = "--plugin-dir")
        Path plugins = dev.voxelbrew.util.config.ApplicationPaths.plugins();

        @Option(names = "--overwrite")
        boolean overwrite;

        @Spec
        CommandLine.Model.CommandSpec spec;

        @Override
        public Integer call() throws Exception {
            try (var registry = importers.open(plugins)) {
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                if (output == null) throw new IllegalArgumentException("Document import requires --output");
                if (output != null
                        && (source.toAbsolutePath()
                                        .normalize()
                                        .equals(output.toAbsolutePath().normalize())
                                || java.nio.file.Files.exists(output)
                                        && java.nio.file.Files.isSameFile(source, output)))
                    throw new IllegalArgumentException("Import destination must differ from its source");
                var result = ImportInvocation.read(registry, format, source, options);
                if (result instanceof ImportResult.Document document) {
                    documents.save(document.document(), output, overwrite);
                    spec.commandLine()
                            .getOut()
                            .println("Imported " + document.document().id() + " to " + output);
                }
            }
            return 0;
        }
    }
}
