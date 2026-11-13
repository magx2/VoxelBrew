package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.importer.api.GcodeSource;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class JobInspectionTest {
    private String output;
    private final List<String> reads = new ArrayList<>();

    private GcodeJob.Segment segment(double z, GcodeJob.Role role) {
        return new GcodeJob.Segment(0, 0, z, 10, 0, z, 1, .4, .2, role);
    }

    private int run(CadDocument document, String... args) {
        var job = new GcodeJob(
                List.of(
                        segment(.2, GcodeJob.Role.PERIMETER),
                        segment(.2, GcodeJob.Role.INFILL),
                        segment(.4, GcodeJob.Role.SUPPORT),
                        segment(.4, GcodeJob.Role.TRAVEL)),
                List.of(.2, .4),
                OptionalDouble.of(12),
                Optional.of("1 minute"),
                Map.of("backend", "fixture"));
        var reader = new GcodeSource() {
            public GcodeJob read(Path path) {
                reads.add("file:" + path);
                return job;
            }

            public GcodeJob readLines(List<String> lines) {
                reads.addAll(lines);
                return job;
            }
        };
        var root = new CommandLine(CommandLine.Model.CommandSpec.create().name("test"));
        root.getCommandSpec()
                .addOption(CommandLine.Model.OptionSpec.builder("--document")
                        .type(Path.class)
                        .scopeType(CommandLine.ScopeType.INHERIT)
                        .build());
        root.addSubcommand("inspect", new PrintingCommands.InspectJob(reader, path -> document));
        var text = new StringWriter();
        root.setOut(new PrintWriter(text));
        root.setErr(new PrintWriter(text));
        root.setExecutionExceptionHandler((failure, command, parsed) -> {
            command.getErr().println(failure.getMessage());
            return 1;
        });
        int result = root.execute(args);
        output = text.toString();
        return result;
    }

    @Test
    void layerAndRoleFiltersSelectActualSegmentsAndRetainEstimates() {
        assertEquals(
                0,
                run(
                        CadDocument.empty(),
                        "inspect",
                        "job.gcode",
                        "--from-layer",
                        "2",
                        "--to-layer",
                        "2",
                        "--role",
                        "SUPPORT",
                        "--paths"));
        assertEquals(List.of("file:job.gcode"), reads);
        assertTrue(output.contains("filtered_segments=1"));
        assertTrue(output.contains("role=SUPPORT"));
        assertFalse(output.contains("role=PERIMETER"));
        assertFalse(output.contains("role=TRAVEL"));
        assertTrue(output.contains("estimated_time=1 minute"));
        assertTrue(output.contains("filament_mm=12.0"));
    }

    @Test
    void selectedStoredPlateUsesEmbeddedReaderInsteadOfOpeningAFile() {
        var initial = CadDocument.empty();
        var setup = initial.printSetup().addPlate("second");
        var document = new CadDocument(initial.id(), List.of(), 0, setup)
                .withGcode(Map.of("plate-1", List.of("first"), "second", List.of("second")));
        assertEquals(0, run(document, "--document", "design.vxb", "inspect"));
        assertEquals(List.of("second"), reads);
        reads.clear();
        assertEquals(0, run(document, "--document", "design.vxb", "inspect", "--plate", "plate-1"));
        assertEquals(List.of("first"), reads);
    }

    @Test
    void invalidRangesFailRatherThanReturningAnEmptySuccessfulPreview() {
        for (String[] range : new String[][] {{"0", "1"}, {"2", "1"}, {"1", "3"}}) {
            assertEquals(
                    1,
                    run(CadDocument.empty(), "inspect", "job.gcode", "--from-layer", range[0], "--to-layer", range[1]));
            assertTrue(output.contains("Invalid layer range"));
        }
    }

    @Test
    void ambiguousInputAndMissingStoredJobAreRejectedBeforeReadingToolpaths() {
        assertEquals(1, run(CadDocument.empty(), "--document", "design.vxb", "inspect", "job.gcode"));
        assertTrue(reads.isEmpty());
        assertEquals(1, run(CadDocument.empty(), "--document", "design.vxb", "inspect"));
        assertTrue(reads.isEmpty());
    }
}
