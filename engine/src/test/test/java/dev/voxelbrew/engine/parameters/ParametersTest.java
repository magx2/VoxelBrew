package dev.voxelbrew.engine.parameters;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class ParametersTest {
    @Test
    void arithmeticUnitsAndFunctionsKeepDimensions() {
        var p =
                ParameterSet.empty()
                        .put("width", ParameterUnit.MM, "2 in", "")
                        .put("height", ParameterUnit.CM, "width / 2", "")
                        .put("diagonal", ParameterUnit.MM, "sqrt(width^2 + height^2)", "");
        p.validate();
        assertEquals(50.8, p.values().get("width").getValue().doubleValue(), 1e-10);
        assertEquals(25.4, p.values().get("height").getValue().doubleValue(), 1e-10);
        assertEquals(Math.hypot(50.8, 25.4), p.value("diagonal", ParameterUnit.MM), 1e-10);
        assertEquals(14, p.value("2 + 3 * 4", ParameterUnit.NUMBER));
        assertEquals(-4, p.value("-2^2", ParameterUnit.NUMBER));
        assertEquals(512, p.value("2^3^2", ParameterUnit.NUMBER));
        assertEquals(.5, p.value("sin(30 deg)", ParameterUnit.NUMBER), 1e-12);
        assertEquals(90, p.value("pi / 2 rad", ParameterUnit.DEG), 1e-12);
        assertEquals(8, p.value("max(3 mm,8 mm)", ParameterUnit.MM));
        assertEquals(70.8, p.value("width + 2", ParameterUnit.CM), 1e-10);
        assertEquals(100, p.value("max(width,10)", ParameterUnit.CM), 1e-10);
        assertEquals("1e3 + renamed", Expressions.rename("1e3 + e3", "e3", "renamed"));
        assertThrows(
                IllegalArgumentException.class, () -> p.value("width + 3 deg", ParameterUnit.MM));
        assertThrows(IllegalArgumentException.class, () -> p.value("width", ParameterUnit.DEG));
        for (String nonfinite : List.of("NaN", "Infinity", "-Infinity")) {
            assertTrue(
                    assertThrows(
                                    IllegalArgumentException.class,
                                    () -> p.value(nonfinite, ParameterUnit.MM))
                            .getMessage()
                            .contains("finite"));
            assertThrows(IllegalArgumentException.class, () -> ParameterSet.requireName(nonfinite));
        }
    }

    @Test
    void variantsInheritOverrideCloneAndRenameWithoutLosingReferences() {
        var p =
                ParameterSet.empty()
                        .put("width", ParameterUnit.MM, "60", "plate")
                        .put("half", ParameterUnit.MM, "width/2", "")
                        .addConfiguration("Large", false)
                        .override("width", "Large", "80")
                        .bind(new ParameterSet.Target("plate", "width"), "width");
        var id = p.parameter("width").id();
        assertEquals(30, p.values().get("half").getValue().doubleValue());
        p = p.select("Large");
        assertEquals(40, p.values().get("half").getValue().doubleValue());
        p = p.addConfiguration("Copy", true).override("width", "Large", "100");
        assertEquals(80, p.select("Copy").values().get("width").getValue().doubleValue());
        p = p.rename("width", "plateWidth");
        p.validate();
        assertEquals(id, p.parameter("plateWidth").id());
        assertEquals("plateWidth/2", p.parameter("half").expression());
        assertEquals("plateWidth", p.bindings().getFirst().expression());
        assertEquals(50, p.values().get("half").getValue().doubleValue());
        var finalSet = p;
        assertThrows(IllegalArgumentException.class, () -> finalSet.remove("plateWidth"));
        assertEquals("Default", p.removeConfiguration("Large").activeName());
    }

    @Test
    void cyclesMissingNamesAndBadInactiveOverridesAreDiagnosed() {
        for (String expression :
                List.of(
                        "missing+1",
                        "1/0",
                        "sqrt(-1)",
                        "1e999",
                        "sin(2 mm)",
                        "2 +",
                        "(".repeat(40) + "1" + ")".repeat(40))) {
            var p = ParameterSet.empty().put("width", ParameterUnit.MM, expression, "");
            assertThrows(IllegalArgumentException.class, p::validate, expression);
        }
        var cycle =
                ParameterSet.empty()
                        .put("a", ParameterUnit.MM, "b", "")
                        .put("b", ParameterUnit.MM, "a", "");
        assertTrue(
                assertThrows(IllegalArgumentException.class, cycle::validate)
                        .getMessage()
                        .contains("Cyclic"));
        var inactive =
                ParameterSet.empty()
                        .put("width", ParameterUnit.MM, "60", "")
                        .addConfiguration("Broken", false)
                        .override("width", "Broken", "missing");
        assertTrue(
                assertThrows(IllegalArgumentException.class, inactive::validate)
                        .getMessage()
                        .contains("Broken"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ParameterSet.empty().put("mm", ParameterUnit.MM, "5", ""));
        var deep = ParameterSet.empty();
        for (int i = 0; i < 34; i++)
            deep = deep.put("p" + i, ParameterUnit.MM, i == 33 ? "1" : "p" + (i + 1), "");
        assertTrue(
                assertThrows(IllegalArgumentException.class, deep::validate)
                        .getMessage()
                        .contains("depth exceeds 32"));
    }

    @Test
    void parameterTransactionsFollowUndoButNeverMoveRollbackCursor() {
        var session = new ModelSession(CadDocument.empty());
        session.add(
                new Feature.Sketch(
                        "plate",
                        "profile",
                        new Feature.Plane("origin-xy", 0),
                        new Feature.Rectangle(0, 0, 60, 40)));
        var p =
                ParameterSet.empty()
                        .put("width", ParameterUnit.MM, "60", "")
                        .addConfiguration("Large", false)
                        .override("width", "Large", "80");
        session.setParameters(p);
        session.bindExpression("plate", "width", "width");
        var beforeBadUnit = session.snapshot();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        session.setParameters(
                                p.put("width", ParameterUnit.DEG, "60", "")
                                        .bind(new ParameterSet.Target("plate", "width"), "width")));
        assertEquals(beforeBadUnit, session.snapshot());
        session.setCursor(0);
        long before = session.revision();
        session.selectConfiguration("Large");
        assertEquals(0, session.document().cursor());
        assertTrue(session.revision() > before);
        assertTrue(session.undo());
        assertEquals("Default", session.document().parameters().activeName());
        assertEquals(0, session.document().cursor());
        assertTrue(session.redo());
        assertEquals("Large", session.document().parameters().activeName());
        session.editDimension("plate", "width", 70);
        assertEquals(
                "",
                session.document().parameters().binding(new ParameterSet.Target("plate", "width")));
        assertTrue(session.undo());
        assertEquals("width", session.document().parameters().bindings().getFirst().expression());
    }
}
