package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.exporter.ExportSelection;
import dev.voxelbrew.slicer.api.SlicerBackend;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UiTextTest {
    @Test
    void aboutMessagesFormatVersionsAndPolishLegalHelpWithoutAWindow() {
        var en = new Translations(Locale.ENGLISH);
        var pl = new Translations(Locale.forLanguageTag("pl"));
        assertEquals("Version 1.2.3", en.text("about.version", "1.2.3"));
        assertEquals("Wersja 1.2.3", pl.text("about.version", "1.2.3"));
        assertEquals("New version available: 1.1.0", en.text("about.available", "1.1.0"));
        assertEquals("Dostępna nowa wersja: 1.1.0", pl.text("about.available", "1.1.0"));
        assertEquals("Otwórz aktualizator…", pl.text("about.updates"));
        assertTrue(pl.text("help.about-license").contains("licencję"));
    }

    @Test
    void aboutLegalResourcesContainTheBundledLicenseAndNativeNotices() throws Exception {
        for (var entry : Map.of(
                        "LICENSE", "GNU AFFERO GENERAL PUBLIC LICENSE", "THIRD_PARTY_NOTICES.md", "Open CASCADE")
                .entrySet()) {
            try (var stream = AboutDialog.class.getResourceAsStream("legal/" + entry.getKey())) {
                assertNotNull(stream, entry.getKey());
                assertTrue(
                        new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
                                .contains(entry.getValue()),
                        entry.getKey());
            }
        }
    }

    @Test
    void everyGuiMessageAndEnumChoiceExistsInBothCatalogs() throws Exception {
        for (var locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            var t = new Translations(locale);
            try (var sources = Files.list(Path.of("src/main/java/dev/voxelbrew/gui"))) {
                for (var file :
                        sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher = Pattern.compile("translations\\.text\\(\"([^\"]+)\"\\s*[,)]")
                            .matcher(Files.readString(file));
                    while (matcher.find())
                        assertFalse(t.text(matcher.group(1)).isBlank(), file + ": " + matcher.group(1));
                }
            }
            choices(t, "constraint.", SketchGeometry.Type.values());
            choices(t, "constraint.hint.", SketchGeometry.Type.values());
            choices(t, "tool.", SketchTools.Tool.values());
            choices(t, "tool.hint.", SketchTools.Tool.values());
            choices(t, "print.role.", GcodeJob.Role.values());
            choices(t, "print.support.", SupportVolume.Role.values());
            choices(t, "print.capability.", SlicerBackend.Capability.values());
            choices(t, "print.state.", PrintController.State.values());
            choices(t, "export.space.", ExportSelection.Space.values());
            choices(t, "export.contents.", ExportSelection.Contents.values());
            for (var style : Rendering.Style.values()) {
                String name = style.name().toLowerCase(Locale.ROOT);
                assertFalse(t.text("rendering." + name).isBlank());
                assertFalse(t.text("help.rendering-" + name).isBlank());
            }
        }
    }

    @Test
    void compositeViewNamesTranslateWithoutChangingNavigationIdentifiers() {
        var pl = new Translations(Locale.forLanguageTag("pl"));
        assertEquals("Góra Przód Prawo", UiText.view(pl, "Top Front Right"));
        assertEquals("Top Front Right", UiText.view(new Translations(Locale.ENGLISH), "Top Front Right"));
    }

    private static void choices(Translations t, String prefix, Enum<?>[] values) {
        for (var value : values) assertFalse(t.text(prefix + value.name()).isBlank());
    }

    @Test
    void actionDescriptionsCoverButtonFactoriesAndDynamicSketchActions() throws Exception {
        var ids = new java.util.HashSet<String>();
        try (var sources = Files.list(Path.of("src/main/java/dev/voxelbrew/gui"))) {
            for (var file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                var matcher = Pattern.compile(
                                "button\\(\\s*(?:translations\\.text\\(\"[^\"]+\"\\)|\"[^\"]*\"),\\s*\"([^\"]+)\"\\s*,")
                        .matcher(Files.readString(file));
                while (matcher.find()) ids.add(matcher.group(1));
            }
        }
        assertTrue(ids.size() > 50, "Audit the actual production action factories");
        ids.addAll(List.of(
                "timeline-first",
                "timeline-previous",
                "timeline-play",
                "timeline-pause",
                "timeline-next",
                "timeline-last",
                "history-cursor"));
        for (var locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            var translations = new Translations(locale);
            for (var id : ids) assertFalse(UiHelp.description(translations, id).isBlank(), id);
            for (var draw : SketchEditor.Draw.values()) {
                if (draw != SketchEditor.Draw.ADVANCED)
                    assertFalse(UiHelp.description(
                                    translations, "sketch-tool-" + draw.name().toLowerCase(Locale.ROOT))
                            .isBlank());
            }
            for (var type : SketchGeometry.Type.values()) {
                if (!type.dimension()) {
                    var id = type.name().toLowerCase(Locale.ROOT);
                    assertFalse(
                            UiHelp.description(translations, "constraint-" + id).isBlank());
                    assertFalse(UiHelp.description(translations, "constraint-menu-" + id)
                            .isBlank());
                }
            }
            assertFalse(UiHelp.description(translations, "history-52").isBlank());
            assertFalse(UiHelp.description(translations, "apply-plugin-width").isBlank());
        }
    }
}
