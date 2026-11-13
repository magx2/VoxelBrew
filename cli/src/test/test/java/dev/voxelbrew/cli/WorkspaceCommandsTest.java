package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.di.ApplicationModule;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class WorkspaceCommandsTest {
    @TempDir
    Path directory;

    @Test
    void disablingSupportsRetainsDefinitionsAndMovingAnInstanceMovesItsAttachments() {
        var owner = new dev.voxelbrew.engine.model.BodyInstance(
                "first", "body", dev.voxelbrew.engine.model.Transform.IDENTITY);
        var support = new dev.voxelbrew.engine.printing.SupportVolume(
                "support", "first", dev.voxelbrew.engine.printing.SupportVolume.Role.SOLID_SUPPORT, 0, 0, 0, 5, 5, 5);
        var setup = dev.voxelbrew.engine.printing.PrintSetup.empty()
                .withInstances(List.of(owner))
                .withSupports(List.of(support));
        var document = new AtomicReference<>(
                new CadDocument("doc", List.of(), 0, setup).withGcode(Map.of("plate-1", List.of("G1 X1"))));
        var saves = new ArrayList<CadDocument>();
        assertEquals(0, parser(document, saves).execute("--document", "virtual.vxb", "slicer", "supports", "disable"));
        assertTrue(document.get().printSetup().supportsDisabled());
        assertEquals(List.of(support), document.get().printSetup().supports());
        assertTrue(document.get().gcode().isEmpty());
        assertEquals(
                0,
                parser(document, saves)
                        .execute("--document", "virtual.vxb", "slicer", "plate", "add", "--plate", "second"));
        assertEquals(
                0,
                parser(document, saves)
                        .execute("--document", "virtual.vxb", "slicer", "plate", "select", "--plate", "plate-1"));
        assertEquals(
                0,
                parser(document, saves)
                        .execute(
                                "--document",
                                "virtual.vxb",
                                "slicer",
                                "placement",
                                "move-to-plate",
                                "--instance",
                                "first",
                                "--plate",
                                "second"));
        assertTrue(document.get().printSetup().instances().isEmpty());
        assertTrue(document.get().printSetup().supports().isEmpty());
        var destination = document.get().printSetup().selectPlate("second");
        assertEquals(List.of(owner), destination.instances());
        assertEquals(List.of(support), destination.supports());
    }

    private int script(AtomicReference<CadDocument> document, List<CadDocument> saves, String json) throws Exception {
        Path script = directory.resolve("script.json");
        Files.writeString(script, json);
        return parser(document, saves).execute("--document", "session.vxb", "session", "--script", script.toString());
    }

    @Test
    void sketchFinishCreatesOneDocumentUndoStepAndCancelDiscardsLocalUndoRedo() throws Exception {
        var document = new AtomicReference<>(CadDocument.empty());
        var saves = new ArrayList<CadDocument>();
        assertEquals(0, script(document, saves, """
                [["designer","sketch","begin"],
                 ["designer","sketch","new","--id","a"],
                 ["designer","sketch","new","--id","b"],
                 ["designer","sketch","finish"],["undo"],["document","save"]]
                """));
        assertTrue(document.get().features().isEmpty());
        assertEquals(1, saves.size());
        saves.clear();
        assertEquals(0, script(document, saves, """
                [["designer","sketch","begin"],["designer","sketch","new","--id","a"],
                 ["undo"],["redo"],["designer","sketch","cancel"],["document","save"]]
                """));
        assertTrue(document.get().features().isEmpty());
        assertEquals(1, saves.size());
    }

    @Test
    void openDraftRejectsDocumentAndSolidOperationsWithoutPublishing() throws Exception {
        for (String command : List.of("[\"document\",\"save\"]", "[\"slicer\",\"supports\",\"disable\"]")) {
            var initial = CadDocument.empty();
            var document = new AtomicReference<>(initial);
            var saves = new ArrayList<CadDocument>();
            assertEquals(1, script(document, saves, "[[\"designer\",\"sketch\",\"begin\"]," + command + "]"));
            assertTrue(saves.isEmpty());
            assertEquals(initial, document.get());
        }
    }

    @Test
    void cursorChangesAreNotUndoEntriesAndUndoNeverRewindsIdsOrRestoresStaleJobs() throws Exception {
        var model = new dev.voxelbrew.engine.model.ModelSession(CadDocument.empty());
        model.add(new dev.voxelbrew.engine.model.Feature.Sketch(
                "outline",
                "profile",
                dev.voxelbrew.engine.model.Feature.Plane.origin("XY"),
                new dev.voxelbrew.engine.model.Feature.Rectangle(0, 0, 10, 10)));
        var initial = model.document().withGcode(Map.of("plate-1", List.of("G1 X1")));
        long ids = initial.uuidGenerator().nextValue();
        var document = new AtomicReference<>(initial);
        var saves = new ArrayList<CadDocument>();
        assertEquals(0, script(document, saves, """
                [["designer","parameter","set","--name","width","--unit","mm","--expression","10"],
                 ["designer","history","cursor","--position","0"],["undo"],["document","save"]]
                """));
        assertEquals(0, document.get().cursor());
        assertTrue(document.get().parameters().parameters().isEmpty());
        assertTrue(document.get().gcode().isEmpty());
        assertTrue(document.get().uuidGenerator().nextValue() > ids);
    }

    @Test
    void redoIsDiscardedByANewEditAndFailedBatchDoesNotSave() throws Exception {
        var initial = CadDocument.empty();
        var document = new AtomicReference<>(initial);
        var saves = new ArrayList<CadDocument>();
        assertEquals(1, script(document, saves, """
                [["slicer","plate","add","--plate","a"],["undo"],
                 ["slicer","plate","add","--plate","b"],["redo"],["document","save"]]
                """));
        assertTrue(saves.isEmpty());
        assertEquals(initial, document.get());
    }

    @Test
    void jsonOutputHasAStableEnvelopeAndFlatCommandsStayUnavailable() {
        var cli = parser(new AtomicReference<>(CadDocument.empty()), new ArrayList<>());
        var output = new StringWriter();
        cli.setOut(new PrintWriter(output));
        assertEquals(0, cli.execute("--output-format", "json", "--document", "virtual.vxb", "slicer", "plate", "list"));
        var result = com.google.gson.JsonParser.parseString(output.toString()).getAsJsonObject();
        assertEquals(0, result.get("exitCode").getAsInt());
        assertTrue(result.getAsJsonArray("output").get(0).getAsString().contains("plate-1"));
    }

    private CommandLine parser(AtomicReference<CadDocument> document, List<CadDocument> saves) {
        var files = new FileIoModule(
                path -> document.get(),
                (next, path, overwrite) -> {
                    document.set(next);
                    saves.add(next);
                },
                path -> {
                    throw new IOException("No job");
                });
        return DaggerCliComponent.factory()
                .create(
                        new ApplicationModule(new UserConfiguration(directory.resolve("config.properties")), () -> {
                            throw new AssertionError("No update lookup");
                        }),
                        files)
                .commandLine()
                .setOut(new PrintWriter(new StringWriter()))
                .setErr(new PrintWriter(new StringWriter()));
    }

    @Test
    void workspaceTreeRejectsFlatCommandsAndEveryLeafHasUsableHelp() {
        var cli = parser(new AtomicReference<>(CadDocument.empty()), new ArrayList<>());
        assertEquals(
                Set.of("document", "designer", "slicer", "printer", "settings", "session"),
                cli.getSubcommands().keySet());
        assertEquals(2, cli.execute("new", "obsolete.vxb"));
        assertEquals(2, cli.execute("designer", "inspect"));
        verifyHelp(cli, new ArrayList<>());
    }

    private void verifyHelp(CommandLine command, List<String> path) {
        if (command.getSubcommands().isEmpty()) {
            var args = new ArrayList<>(path);
            args.add("--help");
            assertEquals(
                    0,
                    parser(new AtomicReference<>(CadDocument.empty()), new ArrayList<>())
                            .execute(args.toArray(String[]::new)),
                    path.toString());
        } else
            command.getSubcommands().forEach((name, child) -> {
                var next = new ArrayList<>(path);
                next.add(name);
                verifyHelp(child, next);
            });
    }

    @Test
    void documentOptionAndPlateMutationsPreserveUnrelatedSetupAndProtectLastPlate() {
        var document = new AtomicReference<>(CadDocument.empty());
        var saves = new ArrayList<CadDocument>();
        assertEquals(
                0,
                parser(document, saves)
                        .execute("--document", "part with spaces.vxb", "slicer", "plate", "add", "--plate", "second"));
        assertEquals(2, document.get().printSetup().plates().size());
        assertEquals(
                0,
                parser(document, saves)
                        .execute(
                                "--document",
                                "part with spaces.vxb",
                                "slicer",
                                "plate",
                                "rename",
                                "--plate",
                                "second",
                                "--name",
                                "Second plate"));
        assertEquals("Second plate", document.get().printSetup().activePlate().name());
        assertEquals(
                0,
                parser(document, saves)
                        .execute("slicer", "plate", "remove", "part with spaces.vxb", "--plate", "second"));
        var before = document.get();
        assertEquals(
                1,
                parser(document, saves)
                        .execute(
                                "--document",
                                "part with spaces.vxb",
                                "slicer",
                                "plate",
                                "remove",
                                "--plate",
                                "plate-1"));
        assertEquals(before, document.get());
        assertEquals(2, parser(document, saves).execute("--document", "a.vxb", "document", "new", "b.vxb"));
    }

    @Test
    void batchSessionOnlyPublishesOnSaveAndUndoRestoresThePreviousDocument() throws Exception {
        var initial = CadDocument.empty();
        var document = new AtomicReference<>(initial);
        var saves = new ArrayList<CadDocument>();
        Path script = directory.resolve("session.json");
        Files.writeString(
                script, "[[\"slicer\",\"plate\",\"add\",\"--plate\",\"second\"],[\"undo\"],[\"document\",\"save\"]]");
        assertEquals(
                0,
                parser(document, saves).execute("--document", "session.vxb", "session", "--script", script.toString()));
        assertEquals(List.of(initial), saves);
        saves.clear();
        Files.writeString(script, "[[\"document\",\"new\"],[\"unknown\"],[\"document\",\"save\"]]");
        assertEquals(
                2,
                parser(document, saves).execute("--document", "session.vxb", "session", "--script", script.toString()));
        assertTrue(saves.isEmpty());
    }
}
