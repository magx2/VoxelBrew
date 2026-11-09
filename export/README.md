# Export module

The `:export` Gradle project implements VoxelBrew's document and geometry exporters,
runtime exporter discovery, and external-slicer file pipeline. Its Java package is
`dev.voxelbrew.exporter`. Both the JavaFX desktop and headless CLI use this module.

Public plugin contracts live in [`:api:export`](../api/export/README.md), not here. The engine
owns document codecs, evaluation, native geometry and tessellation; this module owns
format implementations and file publication. Production dependencies point from
export to engine, never in the opposite direction.

## Built-in formats

| Format ID | Output | Implementation |
| --- | --- | --- |
| `vxb` | Editable VoxelBrew document, including feature history | `JcadExporter` / `JcadExporterProvider` |
| `json` | Complete document schema as UTF-8 ProtoJSON | `JsonExporter` / `JsonExporterProvider` |
| `step` | Exact design solids as STEP AP242, in millimeters | `StepExporter` / `StepExporterProvider` |
| `stl` | Binary STL mesh, in millimeters | `BinaryStlExporter` |
| `3mf` | Portable 3MF with units and instance placements | `ThreeMfExporter` |
| `obj` | Wavefront OBJ mesh, with instance transforms applied | `ObjExporter` |
| `prusa-project` | PrusaSlicer project from a complete manufacturing view | `printing.PrusaProjectExporter` |

The native document extension is `.vxb`; the `Jcad` implementation class names are
retained from the earlier extension. Existing `.jcad` documents remain readable.
**File > Save** uses the native document writer; **File > Export** lists discovered
exporters through one registry. Exporting JSON or STEP does not change the active
project path or save counter. STEP exports complete design solids, without print-bed
placements, supports or editable history. See [file I/O](../docs/file-io.md) and the
[printing workflow](../docs/printing-workflow.md) for more detail.

## Write your own exporter

See the [export API developer guide](../api/export/README.md) for the public
contract, a complete implementation example, custom dialogs, plugin packaging,
runtime discovery, first-party registration and validation commands.

## Built-in geometry and slicing

Built-in STL, 3MF, OBJ and Prusa project exporters share `MeshExporter`, an
implementation helper in this module. They request a `MeshExportDialog` containing
format-specific options. Desktop and CLI handlers return a destination, tolerance,
body/instance selection and coordinate space. Evaluation and serialization happen
on the provider's worker; `AtomicExport` publishes the completed file atomically.

`ModelView`, `ManufacturingView`, `MeshFormat` and `MeshExportOption` are implementation
helpers, not plugin contracts in `export-api`. The public `ModelExporter` SPI and its
service resource have been removed. External exporters use `ExporterProvider` and
can define their own typed dialog requests. Existing plugins must be recompiled and
migrated to the unified contract; there is no legacy discovery adapter.

Slicing uses the same registry and exporters:

```text
CadDocument + captured manufacturing state
    -> Exporter.export(document, headless window)
    -> private input file
    -> SlicerBackend.slice(request)
    -> inspected G-code, atomically published
```

`SlicerBackend.inputFormat()` declares an exporter ID, extension and export options.
The host provides headless file/geometry dialog handlers and waits for successful
export before invoking the backend. `SlicerBackend.Request.input()` is the completed
input file; the backend consumes it rather than implementing another model writer.
The slicer SPI is version 2 because its request now includes this input path.

Prusa uses `prusa-project` (3MF) with solid supports included, preserving placements,
profiles and support modifiers. Other backends may select STL, STEP or portable 3MF
when those formats preserve the semantics they need. STEP currently exports design
coordinates, so it is not a substitute for a placed build plate. Failed or cancelled
exports prevent backend execution; temporary input is cleaned up with the slice job.

The geometry dialog can carry a detached manufacturing snapshot for slicing. Its
document ID and revision must match the exported document. Providers must not
export stale geometry, and the existing publication guard still rejects G-code if
the document changes while slicing.

## Verification

Format and slicing tests live under `src/test/integrationTest`; isolated writer,
discovery and dialog tests live under `src/test/test`. The independent consumer
fixture is in [`fixtures/exporter-plugin`](../fixtures/exporter-plugin).

See [validation commands](../api/export/README.md#validate-an-exporter) in the API
guide and the [root README](../README.md) for native build prerequisites. Focused
export tests do not replace full GUI, slicer or packaged-application qualification.
