package dev.voxelbrew.cli;

import com.google.gson.Gson;
import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.importer.api.DocumentSource;
import dev.voxelbrew.importer.api.GcodeSource;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.Callable;
import javax.inject.Inject;
import lombok.RequiredArgsConstructor;
import picocli.CommandLine;
import picocli.CommandLine.*;

/** Process-local editing with explicit publication and fresh parsers for every operation. */
@Command(
        name = "session",
        mixinStandardHelpOptions = true,
        description = "Edit in memory; JSON scripts contain arrays of command argument arrays. Save explicitly.")
@RequiredArgsConstructor(onConstructor_ = @Inject)
final class SessionCommand implements Callable<Integer> {
    private final DocumentSource source;
    private final DocumentOutput sink;
    private final GcodeSource gcode;

    @Option(names = "--script")
    Path script;

    @Spec
    CommandLine.Model.CommandSpec spec;

    @Override
    public Integer call() throws Exception {
        Path document = spec.root().findOption("--document").getValue();
        if (document == null) throw new IllegalArgumentException("session requires --document");
        try (var state = new State(document, source.load(document))) {
            var shutdown = new Thread(
                    () -> {
                        try {
                            state.shutdown();
                        } catch (IOException failure) {
                            spec.commandLine().getErr().println("ERROR: " + failure.getMessage());
                        }
                    },
                    "voxelbrew-session-cancel");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                if (script != null) {
                    try (var reader = Files.newBufferedReader(script)) {
                        String[][] commands = new Gson().fromJson(reader, String[][].class);
                        if (commands == null)
                            throw new IllegalArgumentException("Expected an array of argument arrays");
                        for (var args : commands) {
                            if (args == null) throw new IllegalArgumentException("Null command");
                            int result = state.execute(args);
                            if (result != 0) return result;
                        }
                    }
                } else {
                    var reader = new BufferedReader(
                            new InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8));
                    String line;
                    spec.commandLine()
                            .getOut()
                            .println(
                                    "Session: enter a JSON argument array; document save publishes changes; quit discards unsaved edits.");
                    while ((line = reader.readLine()) != null) {
                        if (line.strip().equals("quit")) break;
                        try {
                            var args = new Gson().fromJson(line, String[].class);
                            if (args == null || args.length == 0) continue;
                            state.execute(args);
                        } catch (RuntimeException failure) {
                            spec.commandLine().getErr().println("ERROR: " + failure.getMessage());
                        }
                    }
                }
                return 0;
            } finally {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdown);
                } catch (IllegalStateException shuttingDown) {
                    /* The cancellation hook owns cleanup. */
                }
            }
        }
    }

    private final class State implements AutoCloseable {
        private Path path;
        private CadDocument current;
        private CadDocument sketchDraft;
        private volatile Background background;

        private final class Background {
            Path directory, snapshot, log;
            volatile Process process;
            Thread reader;
            volatile IOException outputFailure;
            String fingerprint;
            String target;
            String state = "running";
        }

        private final Deque<CadDocument> draftUndo = new ArrayDeque<>(), draftRedo = new ArrayDeque<>();
        private final Deque<CadDocument> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();

        State(Path path, CadDocument current) {
            this.path = path.toAbsolutePath().normalize();
            this.current = current;
        }

        int execute(String[] args) throws Exception {
            if (args.length == 0) return 0;
            if (args[0].equals("session")) throw new IllegalArgumentException("Nested sessions are unavailable");
            if (args.length >= 3 && args[0].equals("slicer") && args[1].equals("job")) {
                if (args[2].equals("slice") && Arrays.asList(args).contains("--background"))
                    return startBackground(args);
                if (args.length == 3 && Set.of("status", "wait", "cancel").contains(args[2]))
                    return background(args[2]);
            }
            if (args.length == 3 && args[0].equals("designer") && args[1].equals("sketch")) {
                switch (args[2]) {
                    case "begin" -> {
                        if (sketchDraft != null) throw new IllegalArgumentException("A sketch draft is already open");
                        sketchDraft = current;
                        draftUndo.clear();
                        draftRedo.clear();
                        return 0;
                    }
                    case "finish", "cancel" -> {
                        if (sketchDraft == null) throw new IllegalArgumentException("No sketch draft is open");
                        if (args[2].equals("cancel")) restore(sketchDraft);
                        else if (!sketchDraft.equals(current)) {
                            undo.push(sketchDraft);
                            redo.clear();
                        }
                        sketchDraft = null;
                        draftUndo.clear();
                        draftRedo.clear();
                        return 0;
                    }
                    default -> {}
                }
            }
            if (Arrays.equals(args, new String[] {"undo"}) || Arrays.equals(args, new String[] {"redo"})) {
                var backwards = sketchDraft == null ? undo : draftUndo;
                var forwards = sketchDraft == null ? redo : draftRedo;
                var from = args[0].equals("undo") ? backwards : forwards;
                var to = args[0].equals("undo") ? forwards : backwards;
                if (from.isEmpty()) throw new IllegalArgumentException("Nothing to " + args[0]);
                to.push(current);
                restore(from.pop());
                return 0;
            }
            if (args.length >= 2 && args[0].equals("document")) {
                if (sketchDraft != null)
                    throw new IllegalArgumentException("Finish or cancel the sketch draft before document operations");
                switch (args[1]) {
                    case "save" -> {
                        if (args.length != 2)
                            throw new IllegalArgumentException("Use document save-as PATH for a new destination");
                        sink.save(current, path, true);
                        return 0;
                    }
                    case "save-as" -> {
                        if (args.length != 3) throw new IllegalArgumentException("document save-as PATH");
                        var destination = Path.of(args[2]).toAbsolutePath().normalize();
                        sink.save(current, destination, false);
                        path = destination;
                        return 0;
                    }
                    case "open" -> {
                        if (args.length != 3)
                            throw new IllegalArgumentException("document open PATH (discards unsaved edits)");
                        var destination = Path.of(args[2]).toAbsolutePath().normalize();
                        var loaded = source.load(destination);
                        current = loaded;
                        path = destination;
                        undo.clear();
                        redo.clear();
                        return 0;
                    }
                    default -> {}
                }
            }
            if (sketchDraft != null
                    && !(args.length > 1
                            && args[0].equals("designer")
                            && Set.of("sketch", "parameter", "configuration").contains(args[1])))
                throw new IllegalArgumentException("Finish or cancel the sketch draft first");
            var before = current;
            var files = new FileIoModule(
                    requested -> same(requested) ? current : source.load(requested),
                    (next, requested, overwrite) -> {
                        if (same(requested)) current = next;
                        else sink.save(next, requested, overwrite);
                    },
                    gcode);
            var parser = DaggerCliComponent.factory()
                    .create(
                            dev.voxelbrew.util.di.ApplicationModule.system(
                                    dev.voxelbrew.util.update.ApplicationImage.Component.CLI),
                            files)
                    .commandLine();
            parser.setOut(spec.commandLine().getOut());
            parser.setErr(spec.commandLine().getErr());
            var invocation = new ArrayList<String>(List.of("--document", path.toString()));
            invocation.addAll(Arrays.asList(args));
            int result = parser.execute(invocation.toArray(String[]::new));
            if (!before.equals(current)
                    && !(args.length > 2
                            && args[0].equals("designer")
                            && args[1].equals("history")
                            && args[2].equals("cursor"))) {
                (sketchDraft == null ? undo : draftUndo).push(before);
                (sketchDraft == null ? redo : draftRedo).clear();
            }
            return result;
        }

        private boolean same(Path candidate) {
            return path.equals(candidate.toAbsolutePath().normalize());
        }

        private int startBackground(String[] args) throws Exception {
            if (sketchDraft != null) throw new IllegalArgumentException("Finish or cancel the sketch draft first");
            if (background != null && background.process != null && background.process.isAlive())
                throw new IllegalArgumentException("A slice is already running");
            if (Arrays.stream(args)
                    .anyMatch(a -> a.equals("--output")
                            || a.startsWith("--output=")
                            || a.equals("--document")
                            || a.startsWith("--document=")))
                throw new IllegalArgumentException(
                        "Background slicing stores jobs in the session; export after job wait");
            cleanupBackground();
            var job = new Background();
            job.directory = Files.createTempDirectory("voxelbrew-session-slice-");
            job.snapshot = job.directory.resolve("snapshot.vxb");
            job.log = job.directory.resolve("backend.log");
            background = job;
            try {
                sink.save(current, job.snapshot, false);
                job.fingerprint = dev.voxelbrew.engine.persistence.DocumentCodec.fingerprint(current);
                job.target = current.printSetup()
                        .workspaceSettings()
                        .get(dev.voxelbrew.engine.printing.PrintTarget.PRINTER_ID);
                var command = new ArrayList<String>();
                boolean windows =
                        System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
                command.add(Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
                        .toString());
                for (String property : List.of("voxelbrew.native.path", "voxelbrew.home", "voxelbrew.config")) {
                    String value = System.getProperty(property);
                    if (value != null) command.add("-D" + property + "=" + value);
                }
                command.add("-cp");
                command.add(System.getProperty("java.class.path"));
                command.add(VoxelBrew.class.getName());
                command.add("--document");
                command.add(job.snapshot.toString());
                Arrays.stream(args).filter(a -> !a.equals("--background")).forEach(command::add);
                job.process =
                        new ProcessBuilder(command).redirectErrorStream(true).start();
                job.reader = Thread.ofVirtual().start(() -> {
                    try (var input = job.process.getInputStream();
                            var output = Files.newOutputStream(job.log)) {
                        input.transferTo(output);
                    } catch (IOException failure) {
                        job.outputFailure = failure;
                    }
                });
                spec.commandLine().getOut().println("Slice running");
                return 0;
            } catch (Exception failure) {
                cleanupBackground();
                throw failure;
            }
        }

        private int background(String action) throws Exception {
            if (background == null) throw new IllegalArgumentException("No background slice");
            var job = background;
            if (action.equals("cancel") && job.process.isAlive()) {
                stop(job.process);
                job.state = "cancelled";
            }
            if (action.equals("wait") && job.process.isAlive()) job.process.waitFor();
            if (!job.process.isAlive() && job.reader != null) job.reader.join();
            if (!job.process.isAlive() && job.state.equals("running")) {
                if (job.process.exitValue() != 0 || job.outputFailure != null) job.state = "failed";
                else if (!job.fingerprint.equals(dev.voxelbrew.engine.persistence.DocumentCodec.fingerprint(current))
                        || !Objects.equals(
                                job.target,
                                current.printSetup()
                                        .workspaceSettings()
                                        .get(dev.voxelbrew.engine.printing.PrintTarget.PRINTER_ID)))
                    job.state = "outdated";
                else {
                    var result = source.load(job.snapshot);
                    current = current.withGcode(result.gcode()).withPrintArtifacts(result.printArtifacts());
                    job.state = "complete";
                }
            }
            spec.commandLine().getOut().println("Slice " + job.state);
            if (!job.process.isAlive())
                Files.readAllLines(job.log).forEach(spec.commandLine().getErr()::println);
            return job.state.equals("failed") || job.state.equals("outdated") ? 1 : 0;
        }

        private void stop(Process process) throws InterruptedException {
            var children = process.descendants().toList();
            children.forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                children.forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor();
            }
        }

        private synchronized void cleanupBackground() throws IOException, InterruptedException {
            if (background == null) return;
            if (background.process != null && background.process.isAlive()) stop(background.process);
            if (background.reader != null) background.reader.join();
            try (var files = Files.list(background.directory)) {
                for (var file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(background.directory);
            background = null;
        }

        @Override
        public void close() throws IOException {
            shutdown();
        }

        void shutdown() throws IOException {
            try {
                cleanupBackground();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while stopping the background slice", interrupted);
            }
        }

        private void restore(CadDocument previous) {
            boolean followEnd = current.cursor() == current.features().size();
            int cursor = followEnd
                    ? previous.features().size()
                    : Math.min(current.cursor(), previous.features().size());
            var ids = new dev.voxelbrew.engine.model.UuidGenerator(Math.max(
                    current.uuidGenerator().nextValue(),
                    previous.uuidGenerator().nextValue()));
            current = new CadDocument(
                    previous.id(),
                    previous.features(),
                    cursor,
                    previous.printSetup(),
                    previous.parameters().withUuidGenerator(ids),
                    previous.bodyStyles(),
                    previous.bodyNames(),
                    previous.featureNames(),
                    Map.of(),
                    Map.of());
        }
    }
}
