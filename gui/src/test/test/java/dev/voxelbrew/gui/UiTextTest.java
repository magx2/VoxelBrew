package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.export.ExportSelection;
import dev.voxelbrew.engine.printing.*;
import dev.voxelbrew.engine.sketch.*;
import dev.voxelbrew.util.i18n.Translations;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class UiTextTest {
    @Test
    void everyGuiMessageAndEnumChoiceExistsInBothCatalogs() throws Exception {
        for (var locale : List.of(Locale.ENGLISH, Locale.forLanguageTag("pl"))) {
            var t = new Translations(locale);
            try (var sources = Files.list(Path.of("src/main/java/dev/voxelbrew/gui"))) {
                for (var file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    var matcher =
                            Pattern.compile("translations\\.text\\(\"([^\"]+)\"\\s*[,)]")
                                    .matcher(Files.readString(file));
                    while (matcher.find())
                        assertFalse(
                                t.text(matcher.group(1)).isBlank(), file + ": " + matcher.group(1));
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
            choices(t, "export.contents.", ManufacturingModel.Selection.values());
        }
    }

    @Test
    void compositeViewNamesTranslateWithoutChangingNavigationIdentifiers() {
        var pl = new Translations(Locale.forLanguageTag("pl"));
        assertEquals("Góra Przód Prawo", UiText.view(pl, "Top Front Right"));
        assertEquals(
                "Top Front Right",
                UiText.view(new Translations(Locale.ENGLISH), "Top Front Right"));
    }

    private static void choices(Translations t, String prefix, Enum<?>[] values) {
        for (var value : values) assertFalse(t.text(prefix + value.name()).isBlank());
    }
}
