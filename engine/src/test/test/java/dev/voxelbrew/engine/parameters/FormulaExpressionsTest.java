package dev.voxelbrew.engine.parameters;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormulaExpressionsTest {
    @Test
    void dottedNamesValidateEvaluateAndRenameAsWholeReferences() {
        for (var name : List.of("x", "x.fo", "x.bo", "Plate.width", "a_b", "a.."))
            assertDoesNotThrow(() -> ParameterSet.requireName(name));
        for (var name : List.of("_x", ".x", "x1", "1x", "vb.width", "vb.", "a-b", "é"))
            assertThrows(IllegalArgumentException.class, () -> ParameterSet.requireName(name));
        var p = ParameterSet.empty()
                .put("x.fo", ParameterUnit.MM, "10", "")
                .put("x.bo", ParameterUnit.MM, "=$x.fo * 2", "");
        assertEquals(30, p.value("=$x.fo + x.bo", ParameterUnit.MM));
        var renamed = p.rename("x.fo", "y.width");
        assertEquals(30, renamed.value("=$y.width + x.bo", ParameterUnit.MM));
        assertEquals(
                "$x.foobar + $y.width + 1e-3 + \"x.fo\"",
                Expressions.rename("$x.foobar + $x.fo + 1e-3 + \"x.fo\"", "x.fo", "y.width"));
    }

    @Test
    void literalFieldsRemainUsableWhenRepairingBrokenParameters() {
        var broken = ParameterSet.empty().put("width", ParameterUnit.MM, "=$missing", "");
        assertEquals("repair.vxb", broken.text("repair.vxb"));
        assertEquals("repair.vxb", broken.text("=\"repair\"+\".vxb\""));
        assertEquals(5, broken.value("=2+3", ParameterUnit.MM));
        assertThrows(IllegalArgumentException.class, () -> broken.text("=\"Width: \"+$width"));
        assertThrows(IllegalArgumentException.class, broken::validate);
        assertEquals("=literal", broken.text(Expressions.literalText("=literal")));
    }

    @Test
    void excelPrefixesReferencesAndTextConcatenationRespectPrecedenceAndUnits() {
        var p = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "=60", "")
                .put("half", ParameterUnit.MM, "=$width/2", "");
        assertEquals(1 + 2.0 / 3, p.value("=1+2/3", ParameterUnit.NUMBER), 1e-12);
        assertEquals(30, p.value("=$width/2", ParameterUnit.MM));
        assertEquals(60, p.value("=3 cm + $half", ParameterUnit.MM));
        assertEquals("Width: 5", p.text("=\"Width: \" + 5"));
        assertEquals("Width: 60", p.text("=\"Width: \" + $width"));
        assertEquals("Half: 30", p.text("=\"Half: \" + ($width / 2)"));
        assertEquals("3 mm", p.text("=1+2+\" mm\""));
        assertEquals("Width: 12", p.text("=\"Width: \"+1+2"));
        assertEquals("Say \"hello\"", p.text("=\"Say \"\"hello\"\"\""));
        assertEquals("$width + 5", p.text("$width + 5"));
        assertEquals("=literal", p.text("=\"=literal\""));
    }

    @Test
    void formulaFailuresAreBoundedAndDoNotCoerceTextIntoNumbers() {
        var p = ParameterSet.empty();
        for (var input : List.of(
                "=",
                "=$",
                "=$missing",
                "=1/0",
                "=1e309",
                "=sqrt(-1)",
                "=1+",
                "==2",
                "=\"unclosed",
                "=\"x\"*2",
                "=sin(\"x\")")) assertThrows(IllegalArgumentException.class, () -> p.text(input), input);
        assertThrows(IllegalArgumentException.class, () -> p.value("=\"5\"", ParameterUnit.NUMBER));
        assertThrows(IllegalArgumentException.class, () -> p.value("=2 mm", ParameterUnit.DEG));
        assertThrows(IllegalArgumentException.class, () -> p.text("=" + "(".repeat(40) + "1" + ")".repeat(40)));
        assertThrows(IllegalArgumentException.class, () -> p.text("=\"" + "a".repeat(513) + "\""));
    }

    @Test
    void renamingPreservesQuotedTextAndVariantsTrackExplicitReferences() {
        var target = new ParameterSet.Target("sketch", "text:label:content");
        var p = ParameterSet.empty()
                .put("width", ParameterUnit.MM, "5", "")
                .addConfiguration("Wide", false)
                .override("width", "Wide", "8")
                .bind(target, "=\"width $width \" + $width")
                .rename("width", "size");
        p.validate();
        assertEquals("=\"width $width \" + $size", p.binding(target));
        assertEquals("width $width 5", p.text(p.binding(target)));
        assertEquals("width $width 8", p.select("Wide").text(p.binding(target)));
        assertThrows(IllegalArgumentException.class, () -> p.remove("size"));
        assertEquals("1e3 + $renamed", Expressions.rename("1e3 + $eValue", "eValue", "renamed"));
    }
}
