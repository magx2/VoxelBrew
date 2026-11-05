# JSR 385 units and quantities

The engine publishes **Units of Measurement API 2.2** (`javax.measure:unit-api`)
as an API dependency and uses **Indriya 2.2.4** as its runtime implementation.
These are the [JSR 385 API](https://unitsofmeasurement.github.io/unit-api/apidocs/)
and [reference implementation](https://unitsofmeasurement.github.io/indriya/).
The published engine artifact supplies the API transitively to plugin consumers;
the independent spacer fixture compiles and executes typed geometry calls.

`dev.voxelbrew.engine.units.Measurements` owns standard `Unit<Q>` constants,
quantity creation and explicit conversion at application boundaries. SI units,
metric prefixes, transformed imperial units, compound units, temperature offsets
and conversion arithmetic come from JSR 385/Indriya. Constants include length,
area, volume, angle, time, mass, speed, temperature and percentage units.

## Parameters and expressions

`ParameterSet.values()` and `Expressions.evaluate()` return standard
`Quantity<?>` objects. Arithmetic delegates to quantity operations; powers and
roots use unit algebra. The bounded expression parser still owns syntax,
references, precedence, supported functions and implicit-number rules.
`ParameterUnit` is only the stable persisted-name/UI catalogue. Its eight
existing choices and symbols remain unchanged, so old parameter names such as
`s`, `kg` or `mm2` do not unexpectedly become reserved words.

The GUI and CLI convert evaluated quantities into the selected display unit
through `UnitConverter`. A width parameter expressed as `6` in `cm` drives a
60 mm sketch; an `8` override drives 80 mm. Dimension bindings use the same
standard conversion before evaluation. Incompatible unit changes are rejected
before committing a document. Unitless expressions retain M9's implicit-unit
behavior.

JSR 385 follows SI, where angle and a pure ratio both have dimension one.
VoxelBrew additionally requires equal system-unit kinds at CAD boundaries:
an angle cannot silently become a length or a plain ratio. Angle/angle cancels
normally. All degree/radian conversions in sketches, placement, the viewport,
text creation and tessellation use the shared JSR 385 converters.

## Typed application boundaries

```java
import static dev.voxelbrew.engine.units.Measurements.*;
import javax.measure.Quantity;
import javax.measure.quantity.Length;
import dev.voxelbrew.engine.geometry.Solid;
import dev.voxelbrew.engine.mesh.MeshOptions;

Quantity<Length> origin = quantity(0, MILLIMETRE);
try (var plate = Solid.rectanglePrism(origin, origin, origin,
        quantity(6, CENTIMETRE), quantity(40, MILLIMETRE), quantity(.8, CENTIMETRE))) {
    double cubicCentimeters = plate.volumeQuantity().to(CUBIC_CENTIMETRE)
            .getValue().doubleValue(); // 19.2
    var width = plate.bounds().width().to(CENTIMETRE); // 6 cm
    var mesh = plate.tessellate(new MeshOptions(
            quantity(.002, CENTIMETRE), quantity(5, DEGREE)));
}
```

- `Feature.Plane`, `Rectangle`, `Circle`, `Extrude` and `SplitPlane` accept
  length quantities. `ModelSession.editDimension` accepts `Quantity<?>` and
  checks the target field's unit before the normal transaction. Dimension
  descriptors expose `measurementUnit()` and `quantity()`.
- `Solid` accepts typed prism inputs, circle cuts, split offsets and point
  containment queries. `volumeQuantity()` and bounds `width()`, `height()` and
  `depth()` provide typed measurements. `MeshOptions` accepts length/angle
  quantities and exposes typed tolerances.
- `ProcessSettings` accepts length, angle and percentage quantities;
  `PrintProfile.Material` accepts filament length and temperature quantities.
  Typed profile accessors, process accessors and G-code segment lengths expose
  the same units to consumers. The Prusa configuration writer converts lengths
  to millimeters, temperatures to Celsius, angles to degrees and infill to percent.
  A material supplied at 483.15 K writes 210 °C, and a ratio of 0.2 writes 20%.

The low-level coordinate arrays, sketch solver, native OCCT bridge, protobuf
fields and existing numeric entry points remain doubles in their documented
canonical units. These loops and wire formats convert at their boundaries;
they do not allocate a quantity for each mesh vertex or solver residual. Integer
counts, IDs, pixel coordinates and tolerances that are pure ratios remain counts
or scalars. This preserves API-1 plugin payloads and the existing `.jcad` schema 6.
The migration does not expand the parameter dialog's unit catalogue or change
numeric-only CLI options into expression options.

Runtime licenses are retained under `third_party/` and copied into app images.
See [M10 acceptance](acceptance/M10.md) for tests, commands and evidence.
