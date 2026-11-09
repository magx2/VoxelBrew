# Printing workflow

The M4 backend is **PrusaSlicer 2.7.2**, invoked as an external process. The reference
printer is generic Marlin 2, one **0.4 mm** nozzle, **1.75 mm** filament, a rectangular
**200 × 200 mm** bed without excluded zones, **200 mm** usable height, and text
`.gcode` output. PLA uses 210/60 °C nozzle/bed; PETG uses 240/80 °C. These are
software reference profiles, **not a physically qualified printer setup**.

The complete base process is committed in
`export/src/main/resources/dev/voxelbrew/exporter/printing/prusa-2.7.2.ini`.
Document-owned settings override it explicitly. Default first/subsequent layer
height is 0.20 mm, two walls, 20% infill, no automatic supports/brim/raft, no
ironing, variable layers, Z-hop, arc moves, binary G-code or multiple tools.
Support threshold is **degrees from horizontal**. Contact gaps are millimeters;
Prusa's independent support layers may have different heights from model layers.

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

## CAD, placement and persistence

**Split body** in the Design view creates one editable operation. Select an
axis-aligned plane and signed offset; the two outputs are `negative` and
`positive`. The inspector edits the offset and repairs the input feature/output.
CLI: `cli split part.vxb --id halves --body-feature cut --axis X --offset 30`.
Each half exports separately with `--body halves.negative` or `halves.positive`.

The **Print** workspace has Placement, Process, Supports and Job tabs. Add a CAD
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

Select the profile, apply settings, choose a destination, then Slice. The engine
requires a successful complete rebuild at the timeline end. Both frontends use
the same validation and backend. CLI separates `validate`, `slice`, and
`inspect-job`. Existing jobs require explicit overwrite.

The GUI previews actual parsed G-code with layer scrubbing and role filters.
Unavailable roles are disabled; unknown deposition is labeled unknown. Time and
filament are labeled estimates from the backend. The job embeds the actual
backend version, printer/nozzle, material, all resolved settings, units and input
fingerprint. Only successful, independently parsed output is atomically published.
Cancel kills the process tree; edits invalidate running/completed jobs. An old
result cannot replace the current preview or publish over an existing job.

`dev.voxelbrew.exporter.api.printing.SlicerBackend` API 1 is headless and discovered through a dedicated class loader
in the same trusted `~/.voxelbrew/plugins` directory as exporters. Both frontends
accept `--plugin-dir`. Providers declare versions, formats and capabilities;
incompatible/duplicate/broken providers are diagnosed independently.

Adaptive layers, organic/tree supports, painted regions, multiple materials and
generated support mesh export are **unavailable**, not qualified capabilities.
The paths-only backend offers a sliced job and explicit custom pillars instead.
General constraints and sketches attached to generated faces remain later work.
