package dev.voxelbrew.slicer;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.exporter.printing.PrusaConfiguration;
import dev.voxelbrew.slicer.api.SlicerRuntime;
import dev.voxelbrew.slicer.prusa.PrusaSlicerBackend;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterPresetSlicingTest {
    @TempDir
    Path directory;

    @Test
    void selectedHardwareControlsRealSliceIncludingVendorPresets() throws Exception {
        var catalog = SlicerRuntime.load().printers(directory).load();
        var session = SlicerAcceptanceTest.cube();
        session.setPrintSetup(session.document()
                .printSetup()
                .withPrinter(catalog.require("generic-marlin-200-0.4").rectangular(240, 230, 260)));
        var model = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
        assertEquals(
                "0.0x0.0,240.0x0.0,240.0x230.0,0.0x230.0",
                PrusaConfiguration.settings(model).get("bed_shape"));
        assertEquals("260.0", PrusaConfiguration.settings(model).get("max_print_height"));
        var target = directory.resolve("printer.gcode");
        var result = SliceService.slice(
                new PrusaSlicerBackend(),
                session.document(),
                model,
                PrusaSlicerBackend.defaultExecutable(),
                target,
                false,
                new Cancellation(),
                ignored -> {},
                dev.voxelbrew.slicer.api.Slicing.DIRECT);
        assertFalse(result.job().modelLayers().isEmpty());
        for (String id : java.util.List.of("bambu-lab-a1-mini", "prusa-xl-5-tool")) {
            session.setPrintSetup(session.document().printSetup().withPrinter(catalog.require(id)));
            var vendorModel = ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT);
            var settings = PrusaConfiguration.settings(vendorModel);
            assertEquals("marlin2", settings.get("gcode_flavor"));
            assertEquals(id, settings.get("printer_settings_id"));
            assertEquals(
                    Double.toString(catalog.require(id).printer().usableHeight()), settings.get("max_print_height"));
            var sliced = SliceService.slice(
                    new PrusaSlicerBackend(),
                    session.document(),
                    vendorModel,
                    PrusaSlicerBackend.defaultExecutable(),
                    target,
                    true,
                    new Cancellation(),
                    ignored -> {},
                    dev.voxelbrew.slicer.api.Slicing.DIRECT);
            assertFalse(sliced.job().modelLayers().isEmpty());
        }
        session.setPrintSetup(session.document()
                .printSetup()
                .withPrinter(catalog.require("generic-marlin-200-0.4").rectangular(10, 10, 5)));
        assertFalse(PrintValidation.validate(ManufacturingModels.evaluate(session.document(), MeshOptions.DEFAULT))
                .diagnostics()
                .isEmpty());
    }
}
