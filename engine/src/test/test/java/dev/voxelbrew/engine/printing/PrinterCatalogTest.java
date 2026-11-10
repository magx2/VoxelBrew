package dev.voxelbrew.engine.printing;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import dev.voxelbrew.engine.persistence.DocumentCodec;
import dev.voxelbrew.util.config.PrinterResources;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PrinterCatalogTest {
    @TempDir
    Path directory;

    private PrinterCatalog catalog() {
        return new PrinterCatalog(new PrinterResources(directory));
    }

    @Test
    void exportsEditableDefaultsWithMatchingImagesWithoutOverwritingUserFiles() throws Exception {
        assertEquals(62, catalog().exportDefaults());
        var loaded = catalog().load();
        assertTrue(loaded.diagnostics().isEmpty(), loaded.diagnostics().toString());
        assertEquals(124, loaded.entries().size());
        var copy = loaded.entries().stream()
                .filter(entry -> entry.definition().printer().id().equals("custom-bambu-lab-a1-mini"))
                .findFirst()
                .orElseThrow();
        assertFalse(copy.bundled());
        assertEquals(directory.resolve("custom-bambu-lab-a1-mini.png").toUri().toString(), copy.image());
        assertEquals(
                loaded.require("bambu-lab-a1-mini").printer().bed(),
                copy.definition().printer().bed());
        var json = directory.resolve("custom-bambu-lab-a1-mini.json");
        Files.writeString(json, Files.readString(json).replace("A1 mini", "My edited printer"));
        var edited = Files.readAllBytes(json);
        var picture = directory.resolve("custom-bambu-lab-a1-mini.png");
        Files.write(picture, new byte[] {1, 2, 3});
        assertEquals(0, catalog().exportDefaults());
        assertArrayEquals(edited, Files.readAllBytes(json));
        assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(picture));
        assertTrue(Files.exists(directory.resolve("PHOTO-LICENSE.txt")));
        assertTrue(Files.exists(directory.resolve("photo-sources.txt")));
    }

    @Test
    void bundledCatalogIsCompleteAndHasImages() throws Exception {
        var result = catalog().load();
        assertEquals(List.of(), result.diagnostics());
        assertEquals(62, result.entries().size());
        for (var entry : result.entries()) {
            assertTrue(entry.bundled());
            assertNotNull(entry.image(), entry.toString());
            try (var image = new java.net.URI(entry.image()).toURL().openStream()) {
                assertTrue(image.readAllBytes().length > 0);
            }
        }
        assertEquals(180, result.require("bambu-lab-a1-mini").printer().usableHeight());
        assertEquals(4, result.require("snapmaker-u1").toolCount());
        assertEquals(128, result.require("prusa-pro-ht90").printer().bed().size());
    }

    @Test
    void snapshotSurvivesCatalogRemovalAllPlateEditsAndRoundTrips() throws Exception {
        var preset = catalog().load().require("bambu-lab-a1-mini");
        var setup = PrintSetup.empty()
                .withPrinter(preset)
                .withInstances(List.of(new BodyInstance("part", "body", Transform.IDENTITY)))
                .addPlate("second")
                .renamePlate("second", "Second")
                .selectPlate("plate-1")
                .moveInstance("part", "second")
                .withProfile("prusa-slicer", preset.printer().id(), "PETG", ProcessSettings.DEFAULT);
        assertEquals(preset, setup.printerSnapshot());
        var doc = new CadDocument("doc", List.of(), 0, setup);
        assertEquals(doc, DocumentCodec.decode(DocumentCodec.encode(doc)));
        assertEquals(doc, DocumentCodec.decodeJson(DocumentCodec.encodeJson(doc)));
        assertEquals(preset, setup.removeActivePlate().printerSnapshot());
        assertEquals(180, PrintProfile.resolve(setup).printer().usableHeight());
        var session = new ModelSession(doc);
        session.setPrintSetup(setup.withPrinter(preset.rectangular(220, 240, 260)));
        assertEquals(
                260,
                PrintProfile.resolve(session.document().printSetup()).printer().usableHeight());
        session.undo();
        assertEquals(setup, session.document().printSetup());
        assertEquals(200, PrintProfile.resolve(PrintSetup.empty()).printer().usableHeight());
    }

    private String custom(String id) throws Exception {
        try (var stream =
                PrinterResources.class.getResourceAsStream(PrinterResources.ROOT + "generic-marlin-200-0.4.json")) {
            return new String(Objects.requireNonNull(stream).readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                    .replace("generic-marlin-200-0.4", id);
        }
    }

    @Test
    void badFilesAndDuplicateIdsAreIsolatedAndReloadDoesNotChangeSnapshots() throws Exception {
        Files.writeString(directory.resolve("custom.json"), custom("my-printer"));
        assertFalse(catalog().load().entries().stream()
                .filter(entry -> entry.definition().printer().id().equals("my-printer"))
                .findFirst()
                .orElseThrow()
                .bundled());
        var saved = PrintSetup.empty().withPrinter(catalog().load().require("my-printer"));
        Files.writeString(directory.resolve("broken.json"), "not json");
        Files.writeString(directory.resolve("collision.json"), custom("generic-marlin-200-0.4"));
        var loaded = catalog().load();
        assertEquals(2, loaded.diagnostics().size());
        assertEquals(
                "my-printer", loaded.entries().getFirst().definition().printer().id());
        assertEquals(saved.printerSnapshot(), loaded.require("my-printer"));
        Files.writeString(directory.resolve("duplicate.json"), custom("my-printer"));
        assertThrows(IllegalArgumentException.class, () -> catalog().load().require("my-printer"));
        Files.delete(directory.resolve("custom.json"));
        Files.delete(directory.resolve("duplicate.json"));
        assertEquals(200, PrintProfile.resolve(saved).printer().usableHeight());
        assertEquals(
                saved,
                DocumentCodec.decode(DocumentCodec.encode(new CadDocument("doc", List.of(), 0, saved)))
                        .printSetup());
    }

    private String customImage() {
        return catalog().load().entries().stream()
                .filter(e -> e.definition().printer().id().equals("pictured"))
                .findFirst()
                .orElseThrow()
                .image();
    }

    @Test
    void customImagesFollowJsonFilenameAndFormatPriorityOnReload() throws Exception {
        Files.writeString(directory.resolve("custom.printer.json"), custom("pictured"));
        Files.write(directory.resolve("generic.png"), new byte[] {1});
        Files.write(directory.resolve("pictured.png"), new byte[] {1});
        assertNull(customImage(), "Neither the JSON image field nor the printer ID selects the image");
        var extensions = List.of("PNG", "jpg", "jpeg", "webp", "gif", "bmp");
        for (var extension : extensions) Files.write(directory.resolve("custom.printer." + extension), new byte[] {1});
        for (var extension : extensions) {
            var expected = directory.resolve("custom.printer." + extension);
            assertEquals(expected.toUri().toString(), customImage());
            Files.delete(expected);
        }
        Files.write(directory.resolve("custom.printer.svg"), new byte[] {1});
        assertNull(customImage(), "Unsupported formats retain the default illustration");
    }

    @Test
    void customImageFieldIsIgnoredAndOversizeCandidatesDoNotHideJpeg() throws Exception {
        Files.writeString(directory.resolve("foo.json"), custom("pictured").replace("\"generic.png\"", "{}"));
        assertEquals(List.of(), catalog().load().diagnostics());
        Files.write(directory.resolve("foo.png"), new byte[5 * 1024 * 1024 + 1]);
        Files.write(directory.resolve("foo.jpg"), new byte[] {1});
        assertEquals(directory.resolve("foo.jpg").toUri().toString(), customImage());
        Files.delete(directory.resolve("foo.jpg"));
        assertNull(customImage());
    }

    @Test
    void invalidHardwareIsRejected() throws Exception {
        Files.writeString(
                directory.resolve("bad.json"),
                custom("bad").replace("\"usableHeightMm\": 200", "\"usableHeightMm\": -1"));
        assertEquals(1, catalog().load().diagnostics().size());
        assertThrows(IllegalArgumentException.class, () -> catalog().load().require("bad"));
    }
}
