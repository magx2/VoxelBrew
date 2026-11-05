package dev.voxelbrew.cli;

import static org.junit.jupiter.api.Assertions.*;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;

class UpdateCommandsTest {
    @Test
    void updateAndCheckHelpDoNotAttemptNetworkAccess() {
        for (String[] args :
                new String[][] {
                    {"update", "--help"}, {"update", "check", "--help"}, {"update", "-h"}
                }) {
            var output = new StringWriter();
            var command = VoxelBrew.commandLine().setOut(new PrintWriter(output));
            assertEquals(0, command.execute(args));
            assertTrue(output.toString().contains("--version=<version>"));
            assertTrue(output.toString().contains("--force"));
            assertFalse(output.toString().contains("requires gh"));
        }
    }

    @Test
    void releaseVersionIsAnArgumentRatherThanTheStandardVersionHelpFlag() {
        var root = VoxelBrew.commandLine();
        var parsed =
                root.parseArgs("update", "check", "--version", "1.2.3", "--force")
                        .subcommand()
                        .subcommand();
        assertFalse(parsed.isVersionHelpRequested());
        assertEquals("1.2.3", parsed.matchedOptionValue("--version", ""));
        assertEquals(Boolean.TRUE, parsed.matchedOptionValue("--force", false));
    }
}
