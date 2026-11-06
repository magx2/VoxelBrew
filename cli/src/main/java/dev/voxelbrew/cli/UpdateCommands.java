package dev.voxelbrew.cli;

import dev.voxelbrew.util.update.*;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

/** Explicit public app-image updates, shared with the desktop frontend. */
final class UpdateCommands {
    private UpdateCommands() {}

    static void register(CommandLine root) {
        root.addSubcommand("update", Update.class);
    }

    abstract static class Options implements Callable<Integer> {
        final UpdateServiceFactory updates;

        Options(UpdateServiceFactory updates) {
            this.updates = updates;
        }

        @Option(
                names = {"-h", "--help"},
                usageHelp = true,
                description = "Show this help message and exit")
        boolean help;

        @Option(names = "--version", description = "Select a published A.B.C release (allows downgrade)")
        String version;

        @Option(names = "--force", description = "Reinstall the selected version even if already current")
        boolean force;

        @Spec
        CommandSpec spec;

        int execute(boolean checkOnly) throws Exception {
            var service = updates.create();
            var cancellation = new UpdateCancellation();
            var request = new UpdateService.Request(version == null ? null : ReleaseVersion.release(version), force);
            var result = service.check(request, cancellation);
            spec.commandLine().getOut().println(result.message());
            if (checkOnly || !result.available()) return 0;
            try (var prepared = service.prepare(
                    result, cancellation, text -> spec.commandLine().getErr().println(text))) {
                prepared.handOff();
                spec.commandLine()
                        .getOut()
                        .println("Update scheduled after exit. Close other instances, then reopen VoxelBrewCLI.");
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
                    "Download and install a verified public application update after exit (no GitHub login required)")
    static final class Update extends Options {

        @javax.inject.Inject
        Update(UpdateServiceFactory updates) {
            super(updates);
        }

        @Override
        public Integer call() throws Exception {
            return execute(false);
        }
    }

    @Command(name = "check", description = "Check releases without downloading or replacing files")
    static final class Check extends Options {

        @javax.inject.Inject
        Check(UpdateServiceFactory updates) {
            super(updates);
        }

        @Override
        public Integer call() throws Exception {
            return execute(true);
        }
    }
}
