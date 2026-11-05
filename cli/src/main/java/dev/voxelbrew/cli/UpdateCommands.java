package dev.voxelbrew.cli;

import dev.voxelbrew.util.update.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Explicit authenticated app-image updates, shared with the desktop frontend. */
final class UpdateCommands {
    private UpdateCommands() {}

    static void register(CommandLine root) {
        root.addSubcommand("update", new Update());
    }

    abstract static class Options implements Callable<Integer> {
        @Option(
                names = {"-h", "--help"},
                usageHelp = true,
                description = "Show this help message and exit")
        boolean help;

        @Option(
                names = "--version",
                description = "Select a published A.B.C release (allows downgrade)")
        String version;

        @Option(
                names = "--force",
                description = "Reinstall the selected version even if already current")
        boolean force;

        @Spec CommandSpec spec;

        int execute(boolean checkOnly) throws Exception {
            var service = UpdateService.system(ApplicationImage.Component.CLI);
            var cancellation = new UpdateCancellation();
            var request =
                    new UpdateService.Request(
                            version == null ? null : ReleaseVersion.release(version), force);
            var result = service.check(request, cancellation);
            spec.commandLine().getOut().println(result.message());
            if (checkOnly || !result.available()) return 0;
            try (var prepared =
                    service.prepare(
                            result,
                            cancellation,
                            text -> spec.commandLine().getErr().println(text))) {
                prepared.handOff();
                spec.commandLine()
                        .getOut()
                        .println(
                                "Update scheduled after exit. Close other instances, then reopen VoxelBrewCLI.");
                spec.commandLine().getOut().println("Status: " + prepared.statusFile());
                spec.commandLine().getOut().println("Log: " + prepared.logFile());
            }
            return 0;
        }
    }

    @Command(
            name = "update",
            subcommands = Check.class,
            description =
                    "Download and install a verified application update after exit (requires gh authentication)")
    static final class Update extends Options {
        @Override
        public Integer call() throws Exception {
            return execute(false);
        }
    }

    @Command(name = "check", description = "Check releases without downloading or replacing files")
    static final class Check extends Options {
        @Override
        public Integer call() throws Exception {
            return execute(true);
        }
    }
}
