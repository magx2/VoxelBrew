package dev.voxelbrew.cli;

import dev.voxelbrew.engine.parameters.*;
import picocli.CommandLine;
import picocli.CommandLine.*;

final class ParameterCommands {
    private ParameterCommands() {}

    static void register(CommandLine cli) {
        cli.addSubcommand("parameters", new Parameters());
        cli.addSubcommand("configurations", new Configurations());
    }

    @Command(
            name = "parameters",
            mixinStandardHelpOptions = true,
            description = "Named dimensions, formulas and feature bindings",
            subcommands = {
                ListParameters.class,
                SetParameter.class,
                RenameParameter.class,
                RemoveParameter.class,
                Bind.class
            })
    static class Parameters implements Runnable {
        @Spec CommandLine.Model.CommandSpec spec;

        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(
            name = "configurations",
            mixinStandardHelpOptions = true,
            description = "Named parameter variants in one editable design",
            subcommands = {
                ListConfigurations.class,
                AddConfiguration.class,
                Override.class,
                Activate.class,
                RenameConfiguration.class,
                RemoveConfiguration.class
            })
    static class Configurations implements Runnable {
        @Spec CommandLine.Model.CommandSpec spec;

        public void run() {
            spec.commandLine().usage(spec.commandLine().getOut());
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListParameters extends VoxelBrew.FileCommand {
        @Option(names = "--configuration")
        String configuration;

        public Integer call() throws Exception {
            var doc = session().document();
            var p = doc.parameters();
            if (configuration != null) p = p.select(configuration);
            var values = p.values();
            var out = spec.commandLine().getOut();
            out.println("configuration=" + p.activeName());
            for (var parameter : p.parameters())
                out.printf(
                        java.util.Locale.ROOT,
                        "%s\t%s\texpression=%s\tvalue=%.12g\tid=%s\t%s%n",
                        parameter.name(),
                        parameter.unit(),
                        p.expression(parameter, p.active()),
                        values.get(parameter.name()).value() / parameter.unit().scale(),
                        parameter.id(),
                        parameter.comment());
            for (var binding : p.bindings())
                out.println("bind\t" + binding.target() + "\t" + binding.expression());
            return 0;
        }
    }

    @Command(
            name = "set",
            mixinStandardHelpOptions = true,
            description = "Create a parameter or edit its expression in the active configuration")
    static class SetParameter extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        @Option(names = "--unit", defaultValue = "mm")
        String unit;

        @Option(names = "--expression", required = true)
        String expression;

        @Option(names = "--comment", defaultValue = "")
        String comment;

        public Integer call() throws Exception {
            var s = session();
            s.setParameters(
                    s.document()
                            .parameters()
                            .put(name, ParameterUnit.parse(unit), expression, comment));
            return save(s);
        }
    }

    @Command(name = "rename", mixinStandardHelpOptions = true)
    static class RenameParameter extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        @Option(names = "--to", required = true)
        String next;

        public Integer call() throws Exception {
            var s = session();
            s.setParameters(s.document().parameters().rename(name, next));
            return save(s);
        }
    }

    @Command(
            name = "remove",
            mixinStandardHelpOptions = true,
            description = "Remove an unused parameter; references prevent removal")
    static class RemoveParameter extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        public Integer call() throws Exception {
            var s = session();
            s.setParameters(s.document().parameters().remove(name));
            return save(s);
        }
    }

    @Command(
            name = "bind",
            mixinStandardHelpOptions = true,
            description =
                    "Bind a sketch/3D dimension to an expression; empty expression restores its stored numeric value")
    static class Bind extends VoxelBrew.FileCommand {
        @Option(names = "--feature", required = true)
        String feature;

        @Option(names = "--field", required = true)
        String field;

        @Option(names = "--expression", required = true)
        String expression;

        public Integer call() throws Exception {
            var s = session();
            s.bindExpression(feature, field, expression);
            return save(s);
        }
    }

    @Command(name = "list", mixinStandardHelpOptions = true)
    static class ListConfigurations extends VoxelBrew.FileCommand {
        public Integer call() throws Exception {
            var p = session().document().parameters();
            var out = spec.commandLine().getOut();
            out.println((p.active().isEmpty() ? "* " : "  ") + "Default");
            for (var c : p.configurations()) {
                out.println(
                        (p.active().equals(c.id()) ? "* " : "  ") + c.name() + "\tid=" + c.id());
                for (var parameter : p.parameters())
                    out.println(
                            "  "
                                    + parameter.name()
                                    + "="
                                    + p.expression(parameter, c.id())
                                    + (c.overrides().containsKey(parameter.id())
                                            ? ""
                                            : " (inherited)"));
            }
            return 0;
        }
    }

    @Command(name = "add", mixinStandardHelpOptions = true)
    static class AddConfiguration extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        @Option(
                names = "--copy-active",
                description = "Snapshot current expressions; otherwise inherit Default")
        boolean copy;

        public Integer call() throws Exception {
            var s = session();
            s.setParameters(s.document().parameters().addConfiguration(name, copy));
            return save(s);
        }
    }

    @Command(
            name = "set",
            mixinStandardHelpOptions = true,
            description =
                    "Set an override; empty expression restores inheritance for a nondefault configuration")
    static class Override extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        @Option(names = "--parameter", required = true)
        String parameter;

        @Option(names = "--expression", required = true)
        String expression;

        public Integer call() throws Exception {
            var s = session();
            s.setParameters(s.document().parameters().override(parameter, name, expression));
            return save(s);
        }
    }

    @Command(name = "activate", mixinStandardHelpOptions = true)
    static class Activate extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        public Integer call() throws Exception {
            var s = session();
            s.selectConfiguration(name);
            return save(s);
        }
    }

    @Command(name = "rename", mixinStandardHelpOptions = true)
    static class RenameConfiguration extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        @Option(names = "--to", required = true)
        String next;

        public Integer call() throws Exception {
            var s = session();
            s.setParameters(s.document().parameters().renameConfiguration(name, next));
            return save(s);
        }
    }

    @Command(name = "remove", mixinStandardHelpOptions = true)
    static class RemoveConfiguration extends VoxelBrew.FileCommand {
        @Option(names = "--name", required = true)
        String name;

        public Integer call() throws Exception {
            var s = session();
            s.setParameters(s.document().parameters().removeConfiguration(name));
            return save(s);
        }
    }
}
