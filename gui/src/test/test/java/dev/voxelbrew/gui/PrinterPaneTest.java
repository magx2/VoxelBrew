package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.PrintArtifact;
import dev.voxelbrew.printer.api.Printer;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PrinterPaneTest {
    @Test
    void slicedJobDeterminesTheOnlyPrinterDevice() {
        var other = new Printer("other", "Other", Printer.Kind.MOONRAKER, "profile", "http://other", "", "", "");
        var target = new Printer("target", "Target", Printer.Kind.MOONRAKER, "profile", "http://target", "", "", "");
        var devices = List.of(other, target);
        var sliced =
                PrintArtifact.of("gcode", new byte[] {1}, Map.of("target_kind", "printer", "target_id", target.id()));
        var missing =
                PrintArtifact.of("gcode", new byte[] {1}, Map.of("target_kind", "printer", "target_id", "deleted"));
        var profileOnly = PrintArtifact.of("gcode", new byte[] {1}, Map.of("target_kind", "profile"));

        assertSame(target, PrinterPane.deviceForJob(sliced, devices));
        assertNull(PrinterPane.deviceForJob(missing, devices));
        assertNull(PrinterPane.deviceForJob(profileOnly, devices));
        assertNull(PrinterPane.deviceForJob(null, devices));
    }
}
