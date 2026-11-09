package dev.voxelbrew.cli;

import dev.voxelbrew.engine.feature.*;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.importer.api.DocumentSource;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.*;

final class FeatureCommands {
    private FeatureCommands() {}

    static void register(CommandLine cli) {
        cli.addSubcommand("feature-types", Types.class);
        cli.addSubcommand("custom", Custom.class);
    }

    @Command(
            name = "feature-types",
            mixinStandardHelpOptions = true,
            description = "List trusted persistent feature types and numeric parameters")
    @RequiredArgsConstructor(access = AccessLevel.PACKAGE, onConstructor_ = @javax.inject.Inject)
    static final class Types implements Callable<Integer> {
        private final FeatureRegistry.Factory features;

        @Option(names = "--plugin-dir")
        Path plugins = FeatureRegistry.defaultDirectory();

        @Spec
        CommandLine.Model.CommandSpec spec;

        public Integer call() throws Exception {
            try (var registry = features.open(plugins)) {
                for (var d : registry.descriptors())
                    spec.commandLine()
                            .getOut()
                            .println(d
                                    + " API="
                                    + d.apiVersion()
                                    + " payload="
                                    + d.payloadVersion()
                                    + " parameters="
                                    + d.parameters());
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
            }
            return 0;
        }
    }

    @Command(
            name = "custom",
            mixinStandardHelpOptions = true,
            description = "Add a persistent plugin feature; use edit to change a parameter later")
    static final class Custom extends VoxelBrew.AddFeature {
        private final FeatureRegistry.Factory features;

        @javax.inject.Inject
        Custom(DocumentSource documents, DocumentOutput documentWriter, FeatureRegistry.Factory features) {
            super(documents, documentWriter);
            this.features = features;
        }

        @Option(names = "--provider", required = true)
        String provider;

        @Option(names = "--type", required = true)
        String type;

        @Option(
                names = "--parameter",
                description = "Numeric KEY=VALUE; repeatable. Omitted fields use provider defaults.")
        Map<String, Double> parameters = new LinkedHashMap<>();

        public Integer call() throws Exception {
            var session = session();
            try (var registry = features.open(pluginDirectory)) {
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                session.add(registry.create(id, provider, type, parameters));
            }
            return save(session);
        }
    }
}
