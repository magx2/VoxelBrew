package dev.voxelbrew.engine.parameters;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class BuiltinParametersTest {
    @Test
    void reservedMetadataWorksInFormulasAndSurvivesEdits() {
        var p = ParameterSet.empty();
        assertEquals(12, p.allParameters().size());
        assertEquals("Untitled", p.text("=$vb.project.name"));
        assertEquals("", p.text("=$vb.project.path"));
        assertEquals("", p.text("=$vb.project.folder"));
        assertEquals("", p.text("=$vb.project.filename"));
        assertTrue(p.allParameters().stream().allMatch(v -> !v.comment().isBlank()));
        assertEquals(dev.voxelbrew.util.Version.version(), p.text("=$vb.version"));
        java.time.OffsetDateTime.parse(p.text("=$vb.date_time"));
        p.builtins().fileVersion(3);
        var edited = p.put("count", ParameterUnit.NUMBER, "$vb.project.version + 1", "");
        assertEquals(4, edited.value("count", ParameterUnit.NUMBER));
        assertEquals(3, edited.builtins().fileVersion());
        assertThrows(IllegalArgumentException.class, () -> p.put("vb.custom", ParameterUnit.MM, "1", ""));
        assertThrows(IllegalArgumentException.class, () -> p.remove("vb.version"));
        assertThrows(IllegalArgumentException.class, () -> p.override("vb.version", "", "1"));
    }
}
