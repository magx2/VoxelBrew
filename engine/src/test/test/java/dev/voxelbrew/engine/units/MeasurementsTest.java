package dev.voxelbrew.engine.units;

import static dev.voxelbrew.engine.units.Measurements.*;
import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.mesh.MeshOptions;
import dev.voxelbrew.engine.parameters.*;
import dev.voxelbrew.engine.printing.*;
import java.util.Map;
import javax.measure.MetricPrefix;
import javax.measure.Quantity;
import javax.measure.quantity.*;
import org.junit.jupiter.api.Test;

class MeasurementsTest {
    @Test
    void standardQuantitiesInteroperateAcrossMetricImperialAndDerivedUnits() {
        Quantity<Length> length = quantity(20, CENTIMETRE).add(quantity(1, INCH));
        assertEquals(225.4, length.to(MILLIMETRE).getValue().doubleValue(), 1e-10);
        Quantity<Area> area = quantity(1, INCH).multiply(quantity(1, INCH)).asType(Area.class);
        assertEquals(645.16, value(area, SQUARE_MILLIMETRE), 1e-10);
        assertEquals(1000, value(quantity(1, CUBIC_CENTIMETRE), CUBIC_MILLIMETRE), 1e-9);
        Quantity<Speed> speed = quantity(1, FOOT).divide(quantity(1, SECOND)).asType(Speed.class);
        assertEquals(304.8, value(speed, MILLIMETRES_PER_SECOND), 1e-10);
        assertEquals(3600, value(quantity(1, HOUR), SECOND));
        assertEquals(1000, value(quantity(1, KILOGRAM), GRAM));
        assertEquals(.2, value(quantity(20, PERCENT), ONE), 1e-12);
        assertThrows(IllegalArgumentException.class, () -> value(length, SECOND));
        assertThrows(IllegalArgumentException.class, () -> quantity(Double.NaN, METRE));
    }

    @Test
    void cadKeepsAngleKindWhileJsr385OwnsConversionAndAlgebra() {
        assertEquals(Math.PI, value(quantity(180, DEGREE), RADIAN), 1e-12);
        assertEquals(90, degrees(Math.PI / 2), 1e-12);
        assertFalse(sameKind(DEGREE, ONE));
        assertThrows(IllegalArgumentException.class, () -> value(quantity(1, RADIAN), ONE));
        var p =
                ParameterSet.empty()
                        .put("angle", ParameterUnit.DEG, "90", "")
                        .put("width", ParameterUnit.MM, "2 in", "");
        Quantity<Angle> angle = p.values().get("angle").asType(Angle.class);
        assertEquals(Math.PI / 2, angle.to(RADIAN).getValue().doubleValue(), 1e-12);
        assertEquals(.5, p.value("angle / (180 deg)", ParameterUnit.NUMBER), 1e-12);
        assertEquals(90, p.value("sqrt(angle^2)", ParameterUnit.DEG), 1e-12);
        assertThrows(
                IllegalArgumentException.class, () -> p.value("angle + width", ParameterUnit.MM));
        assertThrows(
                IllegalArgumentException.class, () -> p.value("sqrt(width)", ParameterUnit.MM));
        assertThrows(
                IllegalArgumentException.class, () -> p.value("width^8 * width", ParameterUnit.MM));
        Map<String, Quantity<?>> foreign =
                Map.of("distance", quantity(2, METRE), "scale", quantity(50, PERCENT));
        assertEquals(
                1000,
                value(
                        Expressions.evaluate("distance * scale", ParameterUnit.MM, foreign::get),
                        MILLIMETRE),
                1e-9);
        assertEquals(5.08, ParameterUnit.CM.displayValue(p.values().get("width")), 1e-12);
    }

    @Test
    void typedPrintSettingsConvertOffsetsPercentagesAndLengthAtTheBoundary() {
        var material =
                new PrintProfile.Material(
                        "PLA",
                        quantity(.175, CENTIMETRE),
                        quantity(483.15, KELVIN),
                        quantity(333.15, KELVIN));
        assertEquals(1.75, material.filamentDiameter(), 1e-12);
        assertEquals(210, material.nozzleTemperature(), 1e-10);
        assertEquals(333.15, value(material.bedTemperatureQuantity(), KELVIN), 1e-10);
        assertEquals(273.15, value(quantity(0, CELSIUS), KELVIN), 1e-10);
        var process =
                new ProcessSettings(
                        quantity(200, MetricPrefix.MICRO(METRE)),
                        2,
                        quantity(.2, ONE),
                        false,
                        false,
                        quantity(Math.PI / 4, RADIAN),
                        quantity(.02, CENTIMETRE),
                        3,
                        quantity(.5, CENTIMETRE),
                        0);
        assertEquals(.2, process.layerHeight(), 1e-12);
        assertEquals(20, process.infillPercent(), 1e-12);
        assertEquals(45, process.supportThreshold(), 1e-12);
        assertEquals(5, process.brimWidth(), 1e-12);
        var mesh =
                new MeshOptions(
                        quantity(20, MetricPrefix.MICRO(METRE)), quantity(Math.PI / 36, RADIAN));
        assertEquals(MeshOptions.DEFAULT.chordToleranceMm(), mesh.chordToleranceMm(), 1e-12);
        assertEquals(5, value(mesh.angularTolerance(), DEGREE), 1e-12);
    }

    @Test
    void unitCatalogueKeepsLegacyNamesAndDoesNotReserveAdditionalParameterNames() {
        assertEquals("MM", ParameterUnit.MM.name());
        assertSame(ParameterUnit.IN, ParameterUnit.parse("in"));
        for (String name : new String[] {"s", "kg", "K", "mm2", "degC"}) {
            var p = ParameterSet.empty().put(name, ParameterUnit.MM, "2", "");
            p.validate();
            assertEquals(2, p.value(name, ParameterUnit.MM));
        }
    }
}
