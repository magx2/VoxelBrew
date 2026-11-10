package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class SlicerWorkspaceTest {
    @Test
    void appliedChangesReplacePreviouslySavedFieldsAndPreserveUnrecognizedPreferences() {
        var field = new AtomicReference<>("2");
        var workspace = new SlicerWorkspace();
        workspace.add("walls", field::get, field::set);
        workspace.initialize();
        var document = new Object();
        var saved = Map.of("walls", "4", "future-setting", "preserved");
        workspace.restore(document, saved, workspace.pending(document));
        field.set("6");
        workspace.restore(document, saved, Map.of());
        assertEquals(Map.of("walls", "6", "future-setting", "preserved"), workspace.capture());
    }

    @Test
    void draftsPersistAcrossRefreshSaveAndReopenWithoutLeakingIntoAnotherDocument() {
        var field = new AtomicReference<>("default");
        var workspace = new SlicerWorkspace();
        workspace.add("job", field::get, field::set);
        workspace.initialize();
        var first = new Object();
        workspace.restore(first, Map.of(), workspace.pending(first));
        assertEquals(Map.of(), workspace.capture());
        field.set("custom.gcode");
        var pending = workspace.pending(first);
        field.set("generated.gcode");
        workspace.restore(first, Map.of(), pending);
        assertEquals("custom.gcode", field.get());
        var saved = workspace.capture();
        workspace.restore(first, saved, Map.of());
        assertEquals(saved, workspace.capture());
        var second = new Object();
        workspace.restore(second, Map.of(), workspace.pending(second));
        assertEquals("default", field.get());
        assertEquals(Map.of(), workspace.capture());
        var reopened = new Object();
        workspace.restore(reopened, saved, workspace.pending(reopened));
        assertEquals("custom.gcode", field.get());
    }
}
