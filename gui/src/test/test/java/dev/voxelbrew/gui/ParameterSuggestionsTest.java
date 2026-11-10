package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.parameters.ParameterSet;
import dev.voxelbrew.engine.parameters.ParameterUnit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParameterSuggestionsTest {
    @Test
    void numericFieldsExcludeStringParametersForEmptyAndPartialReferences() {
        var parameters = ParameterSet.empty().put("width", ParameterUnit.MM, "10", "");
        assertEquals(
                List.of("vb.project.version", "width"),
                ParameterSuggestions.matches(parameters, "", FormulaField.ExpressionType.NUMBER));
        assertEquals(
                List.of("vb.project.version"),
                ParameterSuggestions.matches(parameters, "vb.project.", FormulaField.ExpressionType.NUMBER));
        assertEquals(List.of(), ParameterSuggestions.matches(parameters, "vb.os.", FormulaField.ExpressionType.NUMBER));
        assertTrue(ParameterSuggestions.matches(parameters, "vb.project.", FormulaField.ExpressionType.STRING)
                .contains("vb.project.name"));
    }

    @Test
    void completesNumericBuiltinInsideArithmetic() {
        var parameters = ParameterSet.empty();
        String formula = "=$vb.project.ver+1";
        var reference = ParameterSuggestions.reference(formula, formula.indexOf('+'));
        assertNotNull(reference);
        assertEquals(List.of("vb.project.version"), ParameterSuggestions.matches(parameters, reference.prefix()));
        String completed =
                formula.substring(0, reference.start()) + "$vb.project.version" + formula.substring(reference.end());
        assertEquals(1, parameters.value(completed, ParameterUnit.NUMBER));
    }

    @Test
    void completesBuiltinsInStringFormulasWithoutUserParameters() {
        var parameters = ParameterSet.empty();
        String formula = "=\"D:\\repos\\VoxelBrew\\build\\Untitled-1\"+$vb.project.version";
        var reference = ParameterSuggestions.reference(formula, formula.length());
        assertNotNull(reference);
        assertEquals(List.of("vb.project.version"), ParameterSuggestions.matches(parameters, reference.prefix()));
        assertEquals(
                List.of(
                        "vb.project.filename",
                        "vb.project.folder",
                        "vb.project.name",
                        "vb.project.path",
                        "vb.project.version"),
                ParameterSuggestions.matches(parameters, "vb.project."));
        assertTrue(ParameterSuggestions.matches(parameters, "").contains("vb.version"));
        assertEquals(List.of(), ParameterSuggestions.matches(parameters, "VB."));
    }

    @Test
    void dottedReferencesCompleteAndTableGroupsByFirstPrefix() {
        assertEquals(new ParameterSuggestions.Reference(1, 6, "x.f"), ParameterSuggestions.reference("=$x.fo", 5));
        var p = ParameterSet.empty()
                .put("x.fo", ParameterUnit.MM, "1", "")
                .put("y.fo", ParameterUnit.MM, "2", "")
                .put("x.bo", ParameterUnit.MM, "3", "")
                .put("width", ParameterUnit.MM, "4", "");
        assertEquals(List.of("x.bo", "x.fo"), ParameterSuggestions.matches(p, "x."));
        assertEquals(
                List.of(
                        "width",
                        "vb.date",
                        "vb.date_time",
                        "vb.os.arch",
                        "vb.os.name",
                        "vb.os.version",
                        "vb.project.filename",
                        "vb.project.folder",
                        "vb.project.name",
                        "vb.project.path",
                        "vb.project.version",
                        "vb.time",
                        "vb.version",
                        "x.bo",
                        "x.fo",
                        "y.fo"),
                ParameterDialog.groupedParameters(p).stream()
                        .map(ParameterSet.Parameter::name)
                        .toList());
    }

    @Test
    void findsOnlyTheReferenceAtTheCaretAndReplacesItsEntireToken() {
        assertEquals(new ParameterSuggestions.Reference(1, 2, ""), ParameterSuggestions.reference("=$", 2));
        assertEquals(
                new ParameterSuggestions.Reference(2, 9, "foo"),
                ParameterSuggestions.reference("=($fooBar/2)+$width", 6));
        assertEquals(
                new ParameterSuggestions.Reference(13, 19, "width"),
                ParameterSuggestions.reference("=($fooBar/2)+$width", 19));
        assertNull(ParameterSuggestions.reference("=$foo/2", 7));
        assertNull(ParameterSuggestions.reference("=$1bad", 6));
        assertNull(ParameterSuggestions.reference("=foo", 4));
        assertNull(ParameterSuggestions.reference("=$foo", -1));
        assertNull(ParameterSuggestions.reference("=$foo", 6));
    }

    @Test
    void ignoresQuotedTextIncludingEscapedQuotesButFindsFollowingReferences() {
        assertNull(ParameterSuggestions.reference("=\"price $foo\"", 12));
        assertNull(ParameterSuggestions.reference("=\"say \"\"$foo", 12));
        String text = "=\"say \"\"hello\"\"\" + $foo";
        assertEquals("foo", ParameterSuggestions.reference(text, text.length()).prefix());
        assertEquals("", ParameterSuggestions.reference("$", 1).prefix());
    }

    @Test
    void filtersByCaseSensitivePrefixRatherThanSubstringAndUsesCurrentNames() {
        var parameters = ParameterSet.empty()
                .put("food", ParameterUnit.MM, "20", "")
                .put("foo", ParameterUnit.MM, "10", "")
                .put("Foo", ParameterUnit.MM, "10", "")
                .put("afoo", ParameterUnit.MM, "30", "");
        assertEquals(List.of("foo", "food"), ParameterSuggestions.matches(parameters, "foo"));
        var allMatches = ParameterSuggestions.matches(parameters, "");
        assertEquals(
                List.of("Foo", "afoo", "foo", "food"),
                allMatches.stream().filter(name -> !name.startsWith("vb.")).toList());
        assertTrue(allMatches.contains("vb.project.version"));
        assertEquals(List.of(), ParameterSuggestions.matches(parameters, "missing"));
        assertEquals(
                List.of("foo", "food", "foot"),
                ParameterSuggestions.matches(parameters.put("foot", ParameterUnit.MM, "40", ""), "foo"));
    }
}
