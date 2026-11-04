package dev.voxelbrew.cli;

import dev.voxelbrew.engine.feature.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.*;

final class FeatureCommands {
    private FeatureCommands() {}

    static void register(CommandLine cli) {
        cli.addSubcommand("feature-types", new Types());
        cli.addSubcommand("custom", new Custom());
    }

    @Command(
            name = "feature-types",
            mixinStandardHelpOptions = true,
            description = "List trusted persistent feature types and numeric parameters")
    static final class Types implements Callable<Integer> {
        @Option(names = "--plugin-dir")
        Path plugins = FeatureRegistry.defaultDirectory();

        @Spec CommandLine.Model.CommandSpec spec;

        public Integer call() throws Exception {
            try (var registry = new FeatureRegistry(plugins)) {
                for (var d : registry.descriptors())
                    spec.commandLine()
                            .getOut()
                            .println(
                                    d
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
        @Option(names = "--provider", required = true)
        String provider;

        @Option(names = "--type", required = true)
        String type;

        @Option(
                names = "--parameter",
                description =
                        "Numeric KEY=VALUE; repeatable. Omitted fields use provider defaults.")
        Map<String, Double> parameters = new LinkedHashMap<>();

        public Integer call() throws Exception {
            var session = session();
            try (var registry = new FeatureRegistry(pluginDirectory)) {
                registry.diagnostics().forEach(spec.commandLine().getErr()::println);
                session.add(registry.create(id, provider, type, parameters));
            }
            return save(session);
        }
    }
}
