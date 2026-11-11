# Printing workflow

Slicing stores each plate's complete G-code in the document. Save/export as
VoxelBrew (`.vxb`) or JSON to retain it; opening or importing either format also
restores its toolpath preview. File > Import can attach a standalone G-code job
to the active plate. Use **File > Export > G-code** to write that plate's stored
job; this choice is available when the plate has G-code. The Slicer Job tab has
no destination or overwrite controls and does not export files.

CAD edits and changes to slicer settings, placement, supports or draft input
clear stored jobs. Undo does not resurrect them. Selecting a plate or inspecting
preview layers does not change the job. Slice all retains separate jobs for all
nonempty plates. CLI `slice --output` continues writing its explicit destination
and also saves the result into the input `.vxb` document.

The M4 backend is **PrusaSlicer 2.7.2**, invoked as an external process. The reference
printer is generic Marlin 2, one **0.4 mm** nozzle, **1.75 mm** filament, a rectangular
**200 × 200 mm** bed without excluded zones, **200 mm** usable height, and text
`.gcode` output. PLA uses 210/60 °C nozzle/bed; PETG uses 240/80 °C. These are
software reference profiles, **not a physically qualified printer setup**.

The complete base process is committed in
`impl/exporter/src/main/resources/dev/voxelbrew/exporter/printing/prusa-2.7.2.ini`.
Document-owned settings override it explicitly. Default first/subsequent layer
height is 0.20 mm, two walls, 20% infill, no automatic supports/brim/raft, no
ironing, variable layers, Z-hop, arc moves, binary G-code or multiple tools.
Support threshold is **degrees from horizontal**. Contact gaps are millimeters;
Prusa's independent support layers may have different heights from model layers.

## Saved slicer settings

VXB and JSON use the same Protobuf print setup. They retain every applied placement,
process setting, support instruction, plate name/selection and printer snapshot,
including edited bed dimensions. Saving also captures the Placement, Process,
Supports and Job tab fields, formula text, selections, rotation/view preferences,
and preview role filters. Reopening
restores these fields; unfinished edits still require the corresponding **Apply**
button before they affect manufacturing. Generated toolpaths are saved as line lists.

When any slicer plate has instances, 3MF export uses the active slicer plate instead
of Designer coordinates or Designer body selection. An empty active plate is an
empty export, not a fallback to Designer. Explicit build-space selections still
apply. Portable Core 3MF includes the print setup as `voxelbrew:printSetup` metadata;
other slicers may ignore this application metadata. Use **Prusa project** for native
PrusaSlicer process/support settings. OBJ and other geometry formats retain their
existing coordinate-space choices.

## Desktop plates and placement

The **Supports** tab has an **Enable supports** master switch, off by default for
new desktop projects. Existing projects retain their saved setting. Turning it off
excludes automatic supports, manual enforcers/blockers/support solids and rafts
on every plate. Their saved settings and definitions are retained for re-enabling;
the switch is undoable and persists in VXB/JSON. Disabled support controls are
greyed out, support instructions disappear from the placement view, and existing
sliced jobs become outdated. The switch also applies to headless slicing and
build-space exports of the saved document.

The Slicer workspace has a plate strip above the canvas. **Add plate** creates an
empty plate; choose a plate to edit or slice it. Double-click a plate name to rename
it inline. Enter saves; Escape or clicking elsewhere cancels. Names accept 1–128
characters and are saved with the design; renaming is undoable. **Move object** transfers the
selected instance and its attached supports to another plate. Removing a populated
plate asks for confirmation; undo restores it. The last plate cannot be removed.
Printer, material and process settings are shared by all plates.

Click and drag an object in either 2D or the isometric 3D view to move it along the
bed. Shift snaps the movement to 1 mm steps. Release commits one undoable placement;
Escape cancels. Numeric placement and rotation remain available in the sidebar.
The selected object has colored X/Y/Z rotation rings in both views. Drag a ring
or its letter handle to rotate around that world axis; Shift snaps to 15 degrees.
Rotation preserves the object's clearance above the plate. Release commits one
undoable placement change; Escape discards the preview.

Camera gestures match Designer: right-drag or Shift + middle-drag pans; middle-drag
or left-drag on empty space orbits in 3D and pans in 2D; the wheel zooms. The 2D
view stays top-down. **Fit view** resets pan and zoom. Each view keeps its own
transient camera state, which never changes placements or creates undo entries.
New instances start at the center of the bed. Placement validation reports objects
outside the bed or overlapping other objects on the selected plate.

**Slice selected plate** validates and slices only that plate. **Slice all**
retains a separate job for each nonempty plate. Select a plate and use File >
Export > G-code to write its job. Editing placement invalidates stored jobs. **Preview toolpaths** switches between the resulting job and
object placement. Layer controls appear only in preview; the expandable toolpath
legend groups color swatches and visibility filters. Property hints describe units
and the effect of process settings.

All plates, their instances/supports and the active plate survive `.vxb` save/reopen.
Single-plate documents retain print-setup version 1; adding a plate upgrades that
setup to version 2. Older versions of VoxelBrew reject version 2 rather than dropping
its extra plates. Naming a plate upgrades the setup to version 3 so older readers
cannot silently discard its name. CLI/build-space exporters use the saved active plate. Per-plate
process overrides and batch slicing are not implemented.

## Installation and demo

Keep Java 21 and the native prerequisites from the README. Bootstrap the slicer:

```powershell
.\scripts\bootstrap-slicer.ps1
.\gradlew.bat :cli:installDist :gui:installDist
.\scripts\print-reference.ps1 '.tools\print demo PLA' PLA
.\scripts\print-reference.ps1 '.tools\print demo PETG' PETG
```

```bash
bash scripts/bootstrap-slicer.sh
./gradlew :cli:installDist :gui:installDist
bash scripts/print-reference.sh '.tools/print demo PLA' PLA
bash scripts/print-reference.sh '.tools/print demo PETG' PETG
```

Choose new output directories. Windows bootstrap verifies the official ZIP's
SHA-256; Ubuntu 24.04 installs `2.7.2+dfsg-1build2`. Override discovery with
`VOXELBREW_SLICER`, CLI `slice --slicer PATH`, or the GUI executable field.
Gradle tests accept `-PslicerExecutable=PATH`. Capability/version probing rejects
an unqualified executable before slicing. The slicer is not bundled with VoxelBrew.

Use **Slicer → Configure slicers…** to configure installed providers from either
workspace. Each provider has its own tab and persisted options, including its
executable path. Cura exposes its machine-definition JSON and process-properties
file; Prusa exposes its profile option. Save applies the settings to subsequent
jobs without restarting. Closing the dialog without saving discards its edits.

Slicer options are stored separately beside the application configuration:
`~/.voxelbrew/slicers/prusa-slicer.properties`,
`~/.voxelbrew/slicers/cura.properties`, or `slicers/{id}.properties`
for another provider. Each file uses plain option keys such as `executable`,
`profile`, `definition`, and `settings`. GUI and CLI share these files.
Existing `slicer.{id}.*` entries in `config.properties` migrate automatically
when the provider is loaded; existing values in the separate file take precedence.
Legacy entries are removed only after the separate file is saved successfully.
Earlier `config-slicer-{id}.properties` files also migrate automatically into
`slicers/{id}.properties`; the old file is removed after successful migration.
With `-Dvoxelbrew.config=...`, the `slicers` directory lives beside that configuration file.

## CAD, placement and persistence

**Split body** in the Design view creates one editable operation. Select an
axis-aligned plane and signed offset; the two outputs are `negative` and
`positive`. The inspector edits the offset and repairs the input feature/output.
CLI: `cli split part.vxb --id halves --body-feature cut --axis X --offset 30`.
Each half exports separately with `--body halves.negative` or `halves.positive`.

The **Slicer** workspace has Placement, Process, Supports and Job tabs. Entering
Slicer saves the project first; cancelling Save As keeps Designer open. F1 shows
help for the current workspace. The full shortcut reference remains in Settings.

Use **2D** for the top view or **3D** for the isometric plate and placed geometry.
After slicing, 2D shows the selected layer and 3D shows paths up to that layer.
Double-click the dimensions above the plate (or focus them and press Enter) to
edit width, depth and usable height in millimeters. Dimensions must be positive
and at most 10000 mm. The custom rectangular Marlin profile is persisted in the
printer reference; preview, preflight and slicer export use the same dimensions.

Body choices use the names from Designer. Instance choices show that body name
and an instance number. Empty instance/support selectors are disabled and explain
what to add first. Add a CAD
body as an instance, enter translations (mm) and rotations (degrees), duplicate,
or lay a current planar face on the bed. Pick the face in Design first, then use
its revision-local ID. Placements never change CAD coordinates. All these edits
participate in undo/redo. Removing a referenced instance retains support
instructions with a repair diagnostic.

`.vxb` schema 2 persists a versioned print setup: instance identities and rigid
transforms, backend/printer/material references, process settings, and attached
support volumes. Schema 1 files still open with an empty setup; newer/unknown
fields remain protected. Toolpaths, camera, appearance and selection remain
outside the document.

Equivalent CLI operations are `place`, `duplicate`, `lay-on-face`,
`print-settings`, `support`, `remove-instance`, `remove-support`, and
`print-setup`; each has `--help`. `validate` reports object-specific mesh, rotated
footprint, bed, exclusion, overlap, height and broken-attachment diagnostics.
Overlaps are conservatively checked from XY footprints when Z intervals overlap.

## Supports and exports

Automatic supports, bed-only/everywhere, threshold, contact gap, interface layers,
brim and raft are explicit controls. Add body-coordinate **BLOCKER** or
**ENFORCER** boxes attached to a build instance. They move with that instance and
remain instructions; they are never exported as printable model solids.
**SOLID_SUPPORT** adds an explicit closed pillar. This is custom geometry, not a
substitute for an automatically generated support mesh.

Geometry export defaults to model-only, design coordinates, millimeters, and no
overwrite. Choose `--space BUILD_PLATE` to apply placement, then
`--selection MODEL|SUPPORTS|ALL`; use `--instance ID` for a single STL instance.
Support IDs in exchange views are `support.SUPPORT_ID`. STL bakes placement once;
portable `3mf` preserves separate resources/build items and tags closed pillars
`solidsupport`. It carries geometry, not slicer settings.

`prusa-project` is a separate vendor exporter. Choose the complete build setup;
it preserves baked placements, named multipart volumes, blockers, enforcers and
resolved process settings. Explicit pillars require its
`include-support-solids=true` option. The receiver sees a multipart assembly,
not independently centered objects. Painting and multiple materials are not
supported by this exporter. Opening the project twice and slicing with the pinned
receiver is covered by real integration tests.

For separate STL handoff, export model and support files from the same build
setup. Import them **as one multipart assembly**, disable automatic centering and
bed snapping, and assign the pillar's intended role in the receiver. STL has no
support-role metadata; never assume two independently centered imports align.
PrusaSlicer's CLI `--merge` keeps separate objects and is insufficient for stacked
multipart assemblies. Prefer `prusa-project`, or use its GUI multipart import.

## Slicing and preview

Select the profile, apply settings, then Slice. Export the stored job through
File > Export when needed. The engine
requires a successful complete rebuild at the timeline end. Both frontends use
the same validation and backend. CLI separates `validate`, `slice`, and
`inspect-job`. File export and CLI output require explicit overwrite for existing destinations.

The GUI previews actual parsed G-code with layer scrubbing and role filters.
Unavailable roles are disabled; unknown deposition is labeled unknown. Time and
filament are labeled estimates from the backend. The job embeds the actual
backend version, printer/nozzle, material, all resolved settings, units and input
fingerprint. Only successful, independently parsed output is atomically published.
Cancel kills the process tree; edits invalidate running/completed jobs. An old
result cannot replace the current preview or publish over an existing job.

`dev.voxelbrew.slicer.api.SlicerBackend` API 2 (from `slicer-api`) is headless and discovered through a dedicated class loader
in the same trusted `~/.voxelbrew/plugins` directory as exporters. Both frontends
accept `--plugin-dir`. Providers declare versions, formats and capabilities;
incompatible/duplicate/broken providers are diagnosed independently.

Adaptive layers, organic/tree supports, painted regions, multiple materials and
generated support mesh export are **unavailable**, not qualified capabilities.
The paths-only backend offers a sliced job and explicit custom pillars instead.
General constraints and sketches attached to generated faces remain later work.

## Printer presets

Use the searchable printer selector above the Slicer tabs. A choice applies to all
plates in this project, updates the bed outline and preflight limits, and creates
one undoable edit. Existing objects are not moved. The project stores a complete
hardware snapshot (print-setup version 4), so another computer does not need the
same preset installed. Existing version 1?3 setups still load normally.

**Reload printers** discovers local changes without changing the saved selection.
Select the preset again to adopt updated values. Editing dimensions creates a
project-local **Modified** snapshot; it does not edit the JSON source. Bed polygons
and red excluded areas are rendered in both views.

Bundled JSON files and schematic images live in `util` resources. See the
[dated model inventory](printer-catalog.md). These are hardware/layout presets;
manufacturer-specific startup, calibration, toolchanging and output packaging
are not implemented. All selected printers can use the generic single-tool slicing
process with their bed, height, nozzle and filament dimensions. Vendor firmware
uses generic Marlin 2 text G-code; this is not a manufacturer-specific machine
profile. Backend process and output-format restrictions still apply.

CLI: `printers` lists IDs; `print-settings part.vxb --printer bambu-lab-a1-mini`
saves that preset into the project. `print-setup part.vxb` reports its identity.

### Creating a custom printer

Create a UTF-8 `.json` file directly in `~/.voxelbrew/printers/` (Windows:
`%USERPROFILE%\.voxelbrew\printers`). `-Dvoxelbrew.home=...` relocates that folder.
Use **Open folder** to create/open it, then **Reload printers** after saving.
All dimensions are in millimeters. IDs must be unique; a custom file cannot
replace a bundled ID. Conflicting custom IDs are both excluded. Invalid files
produce individual diagnostics while the remaining catalog stays available.

```json
{
  "version": 1,
  "id": "my-printer",
  "manufacturer": "Custom",
  "name": "Workshop printer",
  "bed": [
    [0, 0],
    [220, 0],
    [220, 220],
    [0, 220]
  ],
  "excludedZones": [],
  "usableHeightMm": 250,
  "nozzleMm": 0.4,
  "filamentDiameterMm": 1.75,
  "toolCount": 1,
  "firmware": "marlin2",
  "outputFormat": "gcode",
  "genericGcode": true
}
```

`genericGcode: true` explicitly requests the generic process, without any
manufacturer startup routines. Use `false` for hardware that needs vendor
integration; this metadata no longer disables generic slicing. `true` requires
one tool, Marlin 2 and text G-code.
Place an optional image beside the JSON with the same filename stem: `foo.json`
uses `foo.png`. Images are selected in this order: PNG, JPG, JPEG, WebP, GIF, BMP.
Extensions are case-insensitive (for example, `foo.PNG` is supported). The stem
must match the JSON filename, not the printer ID. Custom presets ignore the
`image` field; bundled presets retain their shared resource images.

Missing or unreadable images fall back to a bundled illustration. Inaccessible
or oversized candidates are skipped; an image that fails decoding uses the
bundled illustration. No network images are loaded. JSON files are limited to
1 MiB and images to 5 MiB. SVG and TIFF are not supported by the current
JavaFX 21 image loader.

WebP printer thumbnails use the first frame and preserve transparency. WebP images
above 16 million pixels use the fallback illustration.

The top-level **Printers** menu provides **Export default printers...**, **Open
printers folder** and **Reload printers**. Export opens a searchable checklist
with nothing selected. Choose one or more bundled printers and click **Export
selected** to write editable copies into `~/.voxelbrew/printers/` (or the
configured application home). Cancel closes the dialog without writing files.
Copies use `custom-` IDs and filenames so they coexist with built-ins and appear
at the top of the list. Each image matches its JSON filename. Existing files are
never overwritten; the completion dialog reports the number of new presets.
Photo attribution and licenses are exported alongside the presets.

**Printers > Show/hide default printers...** saves which built-in presets appear
in the selector. Uncheck unused models and save; reopen the dialog to restore
them. Custom printers remain visible. Hidden presets remain available for export,
and saved projects retain their hardware settings. The currently selected project
printer remains displayed even when hidden, until another printer is selected.

The visibility dialog also provides **Select all**, **Deselect all**, and
**Invert selection**. These affect all default printers, including entries
filtered out by search. Changes take effect only after saving visibility.

Click the current printer in the Slicer tab to open a searchable picker, matching
More sketch types. Search is focused immediately; results filter as you type.
Use Up/Down to navigate, Enter or a double-click to choose, and Escape to cancel.
Custom presets remain first, built-ins are marked, and hidden defaults are omitted.

# Device handoff

The **Printer** workspace sends a stored plate job to a physical printer.
See [device configuration, Bambu packages and verification limits](printer-devices.md).
