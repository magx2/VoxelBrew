# VoxelBrew implementation plan

## Assignment

Build a desktop parametric CAD application for designing mechanical parts and preparing them for FDM printing. A user must be able to draw a dimensioned 2D sketch, pull it into a solid, edit its history, reopen the editable design, export it, and slice it for a chosen printer. All modeling operations must also run headlessly.

Start by inspecting the repository and its build instructions. If it is empty, use Java 21, Gradle Kotlin DSL and the Gradle wrapper. Use the milestones below in order. Deliver one runnable increment at a time. At each handoff give the change summary, exact build and demo commands, tests run, and any platform or hardware checks still pending.

## Project identity

Use `voxelbrew.dev` as the project domain and `dev.voxelbrew` as the Gradle group and Java package root. Place module code under `dev.voxelbrew.engine`, `dev.voxelbrew.gui` and `dev.voxelbrew.cli`.

## Mandatory milestone completion rule

Apply this rule to every milestone, including the first and last. After meeting the milestone’s exit criteria, run `./gradlew check` from the repository root (on Windows, `gradlew.bat check`). Run it across all subprojects with no task exclusions. If it fails, fix the failure and rerun the full command. Any further change to the milestone requires another full check.

Once the check passes, review and stage the milestone changes and create a Git commit whose subject includes the milestone ID and outcome. Report the check result and commit hash at handoff. A milestone is complete only after both the full check and the commit succeed. Complete this sequence before beginning the next milestone.

## Project boundaries

| Gradle project | Responsibilities                                                                                                                                            | Dependencies                                                             |
| -------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------ |
| engine         | Document, units, sketch math, feature evaluation, native geometry adapter, persistence, tessellation, export and slicing services, public plugin interfaces | Java, Protobuf, a pinned OCCT build and a narrow JNI bridge under engine |
| gui            | JavaFX desktop, sketch tools, viewport, property editor, timeline, export and print dialogs                                                                 | engine                                                                   |
| cli            | picocli commands for modeling, editing, inspection, export, plugin listing and slicing                                                                      | engine                                                                   |

Keep exactly these three first-party subprojects. Example plugins belong in test fixtures or a separate consumer build. Publish the versioned plugin API from engine. Keep JavaFX, CLI parsing, generated Protobuf classes and native pointers out of plugin contracts. Both frontends submit the same engine operations.

## Reference part

Use one part from the first native experiment to the final packaged demo.

1. On XY, draw a 60 × 40 mm rectangle with its lower-left corner at (0, 0).
2. Extrude it 5 mm along +Z as a new body.
3. Draw a diameter-4 mm circle centered at (10, 10) on XY. Cut it through the plate along +Z.
4. Save, close, reopen, and change the extrusion to 8 mm. The hole must still go through.
5. Verify the solid has bounds (0, 0, 0) to (60, 40, 8) mm and volume (60 × 40 − 4π) × 8 mm³. Compare exact-solid volume within a documented numerical tolerance. Use a separate tolerance for mesh approximation.

## Milestones

### M0 — Prove the native geometry path

Create the three-project build. In engine, pin OCCT and the build toolchain. Reuse a suitable binding if its redistribution terms, platform coverage and required operations check out. Otherwise implement a small JNI adapter. Expose only the operations needed by the reference part. Define who owns and closes each native handle and translate native failures into Java diagnostics.

**Exit test:** On the development platform, a clean checkout can build and run a headless integration test that constructs the plate, cuts the hole and queries volume using the real kernel. Record the native build, load and test commands. A mock result cannot satisfy this milestone. If native setup fails, report the failing command and investigate it before starting the GUI.

### M1 — Build the editable engine and headless workflow

Represent the document as ordered feature definitions with stable IDs and explicit references to earlier inputs. Support dimensioned rectangles and circles on origin or offset planes, new-body extrusion and through-all cuts. Use millimeters and double precision in the model. Reject non-finite dimensions, invalid profiles and invalid references with feature-specific diagnostics.

Evaluate the feature list up to a timeline cursor. Start with full replay. Keep the rollback cursor independent of undo/redo transactions. On a failed rebuild, preserve editable definitions, identify failed and blocked features, and prevent exporting stale geometry. Use stable sketch/profile IDs and named feature outputs. Defer picking generated faces as sketch supports until persistent naming can handle splits and merges.

Define a versioned .jcad Protobuf schema for feature definitions, IDs, dimensions, units and references. Keep meshes disposable. Reserve retired field numbers, protect unsupported newer documents from destructive writes, and use a temporary sibling file plus safe replacement when saving.

Expose CLI commands for creating a document, adding sketches and features, editing dimensions, listing history, and inspecting the evaluated solid. Include a reproducible CLI script that builds the reference part through the public commands.

**Exit test:** Run the script, reopen the file in another process and complete the 5-to-8 mm edit. Assert bounds, volume, hole and stable IDs. Roll back before the cut and verify the solid volume is then 60 × 40 × 8. Undo and redo the thickness edit in an engine session. A truncated file yields a useful error.

### M2 — Ship mesh export and prove external plugins work

Introduce a versioned ModelExporter SPI and an engine-owned read-only model view with a tessellation operation. Register binary STL and 3MF Core exporters through the same registry as external providers. Take tessellation tolerance as an explicit export option. Write STL coordinates in mm. Write units, object identities, build items and placements in 3MF.

Discover trusted JARs from a configurable plugin directory using a dedicated class loader and ServiceLoader. Define provider IDs, API compatibility and diagnostics for duplicate IDs, incompatible versions and load errors. Compile a small independent example exporter against the published engine API.

**Exit test:** Load the example JAR without recompiling the application and export from the CLI. Independently read the STL and 3MF outputs and check scale, bounds, oriented closed mesh and an open through-hole. Open the 3MF in Bambu Studio and verify dimensions and layer preview, or record that manual check as pending.

### M3 — Deliver the interactive desktop workflow

Build the JavaFX viewport with orbit, pan, zoom, picking, sketch-plane coordinates and profile selection. Use an engine-supplied mesh with triangle-to-face mapping. Add rectangle and circle tools, numeric dimensions, sketch completion and an extrusion handle. During drag, show a temporary preview. Accepting a typed distance or releasing the handle commits one operation. Escape discards the preview.

Add a timeline with an editable cursor, a dimension inspector, undo/redo and feature diagnostics. Make the Export dialog use the engine plugin registry. Run expensive rebuilds away from the UI thread and discard stale results when newer edits finish first.

**Exit test:** Create the reference part entirely in the GUI, change thickness, roll back, redo and export it. Open the GUI-saved document with the CLI and verify identical model measurements. One drag creates one undo step. Cancelling a drag leaves the document intact.

### M4 — Split bodies and produce a printer job

Implement SplitBody(bodyId, plane) as an editable modeling feature with two addressable outputs. Keep placement on the print bed separate from the CAD part coordinates. Introduce a SlicerBackend SPI that accepts a manufacturing model, explicit printer/nozzle/material/process profiles, output location, cancellation and a progress/diagnostic sink.

Implement one external slicer adapter first. Choose and pin a tested executable version with a documented headless workflow for one target printer. Probe its capabilities before invocation. Use ProcessBuilder argument lists, collect diagnostics, and publish job output only after successful completion. Let both GUI and CLI select the backend and profile. Treat the required printer-specific output format as part of that profile. Use a real profile for hardware acceptance.

**Exit test:** Split the reference part at X = 30 mm, verify that the output volumes sum to the original, and export each half. Slice a placed part from the CLI and GUI with the same profile. Inspect the generated job in a compatible viewer for layer count, footprint, hole and bed placement. Test cancellation and missing profiles. Record the tested printer/profile and distinguish viewer verification from any physical print.

### M5 — Package and validate on each supported platform

Build installers or app images and a headless CLI distribution on Windows, Linux and macOS. Include the matching JVM, architecture-specific native dependencies and required notices. Document supported OS/CPU pairs, plugin directory and external slicer installation. Add a CI matrix early enough to catch native loading issues.

**Exit test:** On each declared platform, run the packaged CLI on the fixture, edit, export, and load an external plugin from a path containing spaces. Launch the packaged GUI and verify rendering and picking. Publish an explicit tested-platform matrix. Qualify additional Unix variants individually if they become targets.

### M6 — Extend beyond the first printable release

Add a FeatureProvider SPI for persistent custom operations once the built-in evaluator is stable. Store provider ID, feature type, payload version and opaque bytes. Verify that a missing plugin leaves the document repairable and preserves its payload on save. Prove the extension with a parameterized gear or spacer feature. Later work can add general sketch constraints and sketches attached to generated faces, each with its own compatibility fixtures.

### M7 — Constrained 2D sketches

Add the requested Fusion-style constraint workspace on existing XY-parallel
planes: line, rectangle, circle, arc, cubic Bezier and regular polygon tools;
horizontal/vertical, coincident, tangent, equal, parallel, perpendicular, fix,
midpoint, concentric, collinear, symmetry, curvature and polygon constraints;
driving dimensions and remaining degrees of freedom. Keep solving in engine
and off the GUI thread, expose the same operations through CLI, and persist
stable entities and constraints compatibly. Exact closed curves support
extrusion and through cuts. Generated-face attachment remains later work.

**Exit test:** Exercise every constraint family, conflicts and repair, local
and document history, transient dragging and Escape, stale solve rejection,
save/reopen and exact-kernel parity. Create the reference plate in the GUI,
inspect it through CLI, and exercise spline curvature and polygons through
Robot. Run full Windows and Ubuntu gates; preserve both palettes and reports.
See [M7 evidence](acceptance/M7.md) and [the workflow](sketch-constraints.md).

### M8 — Expanded 2D sketch geometry

Audit Autodesk's current 2D sketch geometry families and implement the missing
types and creation variants in engine, CLI and GUI: ellipse, rational conic,
fit/control-point splines (degrees 3/5), straight/arc slots, text and text on a
path, plus alternate rectangle/circle/arc/polygon constructors. Preserve exact
kernel curves, editable definitions and existing identities. Persist text
outlines for font-independent reopening and permit separate closed regions to
extrude as separate bodies. Keep existing constraints/history behavior.

**Exit test:** Validate all creation variants, analytic curve/slot volumes,
indexed spline editing, text persistence and path association, invalid-input
recovery and CLI parity. Exercise real GUI drawing, numeric entry, picking,
local/global history, files, cancellation and both palettes. Run full Windows
and Ubuntu checks and preserve reports/screenshots. See [M8 evidence](acceptance/M8.md)
and the [audited geometry inventory](sketch-types.md).

### M9 — Named parameters and design configurations

Provide engine-owned named numeric parameters, unit-aware expressions, dimension
bindings and named configuration overrides. Expose one cancellable manager in
the workbench and sketch editor, formula entry in driving dimensions and
extrusion, and configuration switching. Keep stable IDs, atomic document
history, editable failures, coalesced worker evaluation and old-file compatibility.
Expose the same operations through CLI; persist parameters in schema 6.

**Exit test:** Exercise units, dependent formulas, cycles, rename/reference
preservation, inherited and copied variants, signed extrusion, shared planes,
split offsets and legacy sketch conversion. Rebuild the reference hole part at
5/8 mm, preserve IDs through save/reopen, and reject export of failed variants.
Use real Robot controls for sketch-local parameters, formula dimensions,
configuration table edits, history, cancellation and file/CLI parity in both
palettes. Run full Windows and Ubuntu gates and preserve reports/screenshots.
See [M9 evidence](acceptance/M9.md) and [the workflow and scope](parameters.md).

### M10 — JSR 385 units and quantities

Replace custom parameter unit arithmetic with `javax.measure` and the pinned
Indriya implementation. Centralize CAD and manufacturing unit conversions;
expose typed feature inputs, edits, solid/bounds measurements, mesh tolerances
and print settings. Preserve the schema, saved unit names, numeric interfaces,
solver/native millimeters and existing angle conventions. Keep quantity types
available to independent published-engine consumers and bundle runtime notices.

**Exit test:** Verify metric/imperial and compound units, angle/scalar separation,
offset temperatures and percentages. Reopen a pre-migration schema-6 fixture
without rewriting bytes; check typed real-kernel dimensions and reference volume.
Exercise centimeter parameter variants through CLI and Robot and reject a unit
change incompatible with a bound dimension. Run full Windows/Ubuntu gates and
the existing platform CI matrix. See [M10 evidence](acceptance/M10.md) and
[units](units.md).

### M11 — Bounded parallel computation

Audit CPU-heavy engine and GUI work. Parallelize independent body tessellation,
larger numerical sketch Jacobian columns and detached print-instance preparation.
Keep timeline replay, plugin invocation, result ordering, editing and publication
semantics intact. Use a shared bounded worker budget, a sequential override and
coarse tasks; join every task before releasing native resources, including on
failure and cancellation. Reuse body mesh validation across repeated instances.

**Exit test:** Prove concurrent execution, worker bounds, nested-call safety,
draining on failure/interruption, deterministic solver diagnostics, unchanged
real-kernel meshes/face mappings/volumes and zero leaked handles. Measure warmed
serial/multithreaded workloads without timing assertions in CI. Run full Windows
and Ubuntu checks with the existing platform matrix and GUI interaction suite.
See [M11 evidence](acceptance/M11.md) and [computation](computation.md).

### M12 — Versioned native releases

Use `release/A.B.x` maintenance branches, `vA.B.C` tags, next-minor development
on master and next-patch development on release branches. Centralize product
versioning. Build and test an immutable release candidate on Windows/Ubuntu
and the qualified macOS CLI, then publish bundled native app archives with
checksums. Add a read-only rehearsal and version-preserving merge-back, with
atomic ref updates, concurrent-change checks and documented failure recovery.
Keep Actions artifacts for three days and macOS GUI qualification deferred.

**Exit test:** Exercise temporary Git remotes for preparation, bundle transfer,
publication ordering, races and failures. Verify extracted archives and release
versions with actual native applications. Run full Windows/Ubuntu checks and
the platform matrix; run the release workflow in dry-run mode without creating
a real release. See [release steps](releases.md) and [M12 evidence](acceptance/M12.md).

## Acceptance contract for the implementation agent

Use these cases as the backlog for M0–M6. A feature passes when its observable results match the case through the public entry point. Capture the fixture revision, command or GUI steps, expected and actual values, artifact path, software versions and result. Keep automated, manually verified, failed and untested results distinct. A sample generated by a test double is sufficient for testing process failure handling only. Use a real kernel, independent format reader and real slicer for their respective release gates.

### Geometry, editing and history (M0, M1, M3)

| ID     | Action                                                                                                                                         | Pass condition                                                                                                                                                                                           |
| ------ | ---------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CAD-01 | Draw the reference rectangle on XY, then on a plane offset +12 mm. Extrude each by +5 mm in separate documents.                                | XY extents are 60 × 40 mm in both. Z bounds are [0, 5] and [12, 17] mm. Reopening preserves plane identity.                                                                                              |
| CAD-02 | Draw a circle by center and diameter. Edit diameter 4 → 6 mm. Attempt diameter 0, a negative value and NaN.                                    | The center remains (10, 10). The cut radius becomes 3 mm. Each invalid edit identifies the field and leaves the previous valid revision intact.                                                          |
| CAD-03 | Select a closed profile, drag the arrow, type 8 mm and commit. Repeat on an offset plane with a signed distance of −3 mm. Cancel another drag. | The measured signed extrusion matches the input. Each successful gesture creates one feature and one undo step. Cancel creates neither.                                                                  |
| CAD-04 | Increase plate thickness 5 → 8 mm with a through-all hole. Sample the center and a point in the surrounding material halfway up the plate.     | Center is outside solid, material point is inside. Bounds and analytic volume match the reference part. For this fixture use 0.001 mm bounds tolerance and 0.01 mm³ exact-volume tolerance.              |
| CAD-05 | Roll back just before the cut, move forward again, then undo and redo a dimension change.                                                      | Rollback restores the uncut shape at that position. Returning to the end restores the hole. The feature list and IDs survive both operations.                                                            |
| CAD-06 | Break a required input reference in a fixture, reopen, repair it, then rebuild.                                                                | The bad feature names the missing input. Dependent features are marked blocked. Full-part export fails while broken. Repair restores the part without duplicating features.                              |
| CAD-07 | Run the same edit via GUI and CLI, save, and reopen each.                                                                                      | Feature IDs from the source, dimensions, body count, bounds and volume match. Camera position and last selection have no effect on geometry.                                                             |
| CAD-08 | Split the plate at X = 30 mm; move one half on the print bed. Repeat with a plane outside the plate.                                           | Two valid solids meet the split plane, with combined volume within 0.01 mm³ of the original. Bed movement leaves design coordinates unchanged. A missed split reports that the plane does not intersect. |

### Export contracts (M1, M2, M4)

Give every export command an explicit selection, coordinate space (design or build plate), units policy, overwrite policy and format-specific options. Advertise these in CLI help and in the GUI. Model-only is the default for geometry exchange. Users must opt into exporting supports or modifiers. A destination file becomes visible as a successful result only after validation. If an export fails or is cancelled, preserve any previous file.

| ID / deliverable              | Contents and options                                                                                                                                                                                                                                                      | Required proof                                                                                                                                                                                                                                                                                                                                     |
| ----------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| EXP-01 · Editable .jcad       | Versioned Protobuf feature graph, stable IDs, sketch planes, dimensions, body references and opaque plugin data. Add versioned print setups for instances, placement, profile references/overrides and support instructions. Treat generated toolpaths as derived output. | Save two placements of one body and a support blocker. Reopen in a fresh process, change thickness and rebuild. Both instances update, maintain their separate placements, and retain the blocker or flag an invalid attachment. Round-trip a missing provider's payload byte-for-byte.                                                            |
| EXP-02 · Binary STL           | Export a selected body, or a selected build instance with its transform baked in. Coordinates in mm. Expose chordal tolerance in mm and angular tolerance with a displayed unit. Offer separate files for individual bodies and support meshes.                           | Independent reader parses every facet. Validate finite coordinates, closed consistently oriented mesh after welding coincident vertices at a documented tolerance, and expected bounds. With chord tolerance 0.02 mm, sample the mid-height circular hole contour and verify radial deviation ≤0.021 mm. A translated instance moves exactly once. |
| EXP-03 · Portable 3MF         | Produce a Core package with units, model resources, build items, named objects and transforms. Reuse one resource for identical instances when appropriate. Include explicit solid supports only on request.                                                              | Inspect package relationships and validate with an independent 3MF reader. Export two instances rotated/translated differently. Both have correct world bounds and intended orientation. Verify the 4 mm hole and scale in a named slicer/version.                                                                                                 |
| EXP-04 · Slicer project       | Separate exporter ID for each supported vendor project dialect. Declare which profiles, placements, modifiers, painting and support settings it preserves. A portable model package and a reopenable slicer project are separate deliverables even when both end in .3mf. | Open in the pinned target slicer, inspect settings and slice. Close, reopen and slice again. Required instructions survive both operations. Reject an unsupported requested setting or require explicit acceptance of a listed loss.                                                                                                               |
| EXP-05 · Printer job          | Produce only the G-code dialect or packaged job declared by the printer profile and backend. Include the actual printer/nozzle, material, process, units, backend version and input revision in the job report.                                                           | An independent viewer for that dialect opens the output. The layers and extents match the selected setup. Wrong printer/profile pairing or a required unsupported output format fails before publishing a job.                                                                                                                                     |
| EXP-06 · Third-party exporter | Publish SPI version, format ID, extensions, accepted inputs, options and capabilities. Use a consumer-built fixture JAR with no GUI dependency.                                                                                                                           | Install the JAR and discover it from both frontends. Export with a nondefault option. A duplicate ID, incompatible provider or failed write produces a useful diagnostic and preserves an existing destination.                                                                                                                                    |

3MF Core defines separate support object types. Use solidsupport for a closed support volume and support only for the corresponding non-filled representation. A receiving application may replace or ignore these objects. Maintain an interoperability table keyed by exporter, receiver and tested version. Record geometry retained, support role retained, transforms retained, settings retained, or unsupported. See the [3MF Core specification, sections 4 and 4.1](https://github.com/3MFConsortium/spec_core/blob/master/3MF%20Core%20Specification.md).

### Print preparation, slicing and preview (M4)

Commit small redistributable fixtures and a fully resolved reference process profile. Pin the slicer version and record the printer, nozzle, filament, bed polygon, excluded zones, usable height, firmware dialect and output format. Give the CLI separate validate, slice and inspect-job operations. GUI and CLI must show the same validation results.

| ID                                 | Scenario                                                                                                                                   | Pass condition                                                                                                                                                                                                                              |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| PRT-01 · Bed preparation           | Rotate a body 90°, lay a chosen planar face on the bed, duplicate it and move the duplicate.                                               | The chosen face lies at bed Z within 0.001 mm. Both instances survive save/reopen. Footprints include rotation. Overlap, excluded-zone intrusion and an over-height instance identify the offending instance.                               |
| PRT-02 · Mesh preflight            | Supply a valid fixture, an open mesh and a mesh with a degenerate facet.                                                                   | Report boundary edges and degenerate triangles by object. Block a known invalid closed-solid export. If repair is offered, make it an explicit operation with before/after counts.                                                          |
| PRT-03 · Deterministic layer check | Slice the 8 mm plate flat, first and subsequent layers 0.20 mm, fixed height, with raft, adaptive layers, ironing, supports and Z-hop off. | Preview has 40 distinct model deposition layers. Check the hole stays clear on an interior layer by testing deposited bead footprints. Count model layers independently of travel moves, comments and startup G-code.                       |
| PRT-04 · Process controls          | On a sufficiently large cube change two perimeter walls to four, then change infill from 0% to 20%. Re-slice at each step.                 | On an interior layer away from top/bottom skins, the measured wall count changes from two to four. Internal infill paths appear only in the 20% result. The selected profile values appear in the job report.                               |
| PRT-05 · Preview                   | Scrub from first to last layer and toggle perimeter, infill, support, interface and travel visibility.                                     | The preview uses the actual generated job or backend path data tied to that job. On a labeled reference job, counts and footprints match an independent inspection. If roles are unavailable, show unknown and disable unsupported filters. |
| PRT-06 · Estimates                 | Slice a fixture for which the backend reports time and filament.                                                                           | Show units and identify estimates. Values match the backend report within display rounding. Unavailable values display as unknown.                                                                                                          |
| PRT-07 · Run lifecycle             | Cancel mid-slice, force backend failure, then edit the model or profile during a long slice.                                               | Job reaches a distinct cancelled, failed, or outdated state. Capture backend diagnostics. No partial job overwrites an existing file, and an older result cannot become the current preview.                                                |
| PRT-08 · Print aids                | Offer brim, raft and adaptive layers as separately discoverable capabilities. Switch each on and off on a fixture suited to it.            | A brim adds first-layer paths outside the footprint, a raft creates layers below the part, and adaptive mode yields varied deposition heights within configured bounds. Each change is independently visible in the actual job.             |

### Supports as settings, geometry and toolpaths (M4)

Deliver automatic supports and explicit support-mesh export in the first printing release. Implement custom support geometry in the application if the chosen backend exposes toolpaths only. A user-created pillar can satisfy the custom mesh requirement. Exporting automatically generated supports as geometry is a separate capability that must pass SUP-07.

Use a shelf fixture with a 20 mm projecting roof and two labeled, equal underside regions A and B. Also use a vertical cube and a recessed ledge above a solid lower shelf. Inspect a mid-height layer beneath the roof away from walls and bridges. Store region bounds in the fixture so tests can compare where support was actually deposited.

| ID                                      | User action                                                                                                                                            | Pass condition                                                                                                                                                                                                                                                                                                   |
| --------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| SUP-01 · Automatic                      | Slice the shelf with supports off, then on at a pinned threshold. Slice the upright cube with the same settings.                                       | Shelf changes from zero support extrusion to positive support extrusion directly below the roof. The cube receives none. Define the overhang angle convention in the UI and use it consistently in CLI and profiles.                                                                                             |
| SUP-02 · Placement                      | Switch the recessed ledge between build-plate-only and everywhere.                                                                                     | With a fixture that has no route from the bed to the recess, support starts on the lower shelf only in everywhere mode. Inspect the support paths at their first contact layer.                                                                                                                                  |
| SUP-03 · Enforce and block              | With auto support off, enforce region A using a backend-supported manual mode. In a separate auto-support run, block B. Save, reopen and re-slice.     | Paths appear under A in the manual run. No support paths cross the interior of B in the blocked run. Enforcers and blockers never appear as printed model bodies.                                                                                                                                                |
| SUP-04 · Contact and interface          | Set a known top contact gap and interface layer count. Slice twice with different values.                                                              | Measure separation from the final support bead to the model underside and count dense interface layers. Match the backend's documented layer quantization for the pinned profile.                                                                                                                                |
| SUP-05 · Custom mesh                    | Add a closed removable support pillar under A, sharing the plate coordinate frame. Export model-only, support-only and a model-plus-support package.   | Model-only has no pillar. Support-only has exactly the pillar at its intended location. Combined 3MF preserves separate resources and the support role. The editable CAD body's volume stays unchanged.                                                                                                          |
| SUP-06 · STL handoff                    | Export model.stl and supports.stl at a nonzero instance translation. Re-import both as one multipart assembly with automatic centering disabled.       | Their joint bounds and relative clearance match the print setup. Provide import instructions and explain that users must assign the support role in the receiver. Never silently fuse the pillar with the part.                                                                                                  |
| SUP-07 · Generated supports as geometry | Request mesh export of auto-generated supports from a backend or generator that exposes true geometry. Try the same request with a paths-only backend. | Geometry-capable backend returns nonempty support-only meshes aligned to the part and tagged by origin. Independently reopen and inspect them. A paths-only backend reports the unavailable capability and offers the supported sliced job. Never substitute the support envelope for actual generated geometry. |
| SUP-08 · Advanced supports              | Qualify tree/organic supports, painted regions and separate interface material individually for each supported backend.                                | A tree fixture shows branches in the generated result. Painted regions survive a project round trip. On a qualified multi-tool profile, interface paths select the assigned tool. A single-tool profile rejects the incompatible assignment.                                                                     |
| SUP-09 · Edits invalidate supports      | Change shelf height, edit its supporting sketch, rotate the instance, or change layer height.                                                          | Recompute affected support and slice results. Explicit attached modifiers follow valid references. Broken references are flagged for repair. Export cannot silently reuse stale generated supports.                                                                                                              |

### Release evidence

1. **Headless CAD gate:** Run CAD-01 through CAD-08 wherever the scenario has an engine/CLI entry point. Run the gesture cases through the GUI. Keep the saved input, command transcript and numerical checks.
2. **Exchange gate:** Run EXP-01 through EXP-03 and EXP-06 on Windows, Linux and macOS. Verify through independent readers. Record exact target-slicer versions for project and support-role compatibility.
3. **First printable release:** Qualify EXP-05, PRT-01 through PRT-07 and SUP-01 through SUP-06 plus SUP-09 with at least one real FDM backend, including one compatible mesh-support handoff. Record a viewer check separately from a physical print. Only label a printer/profile physically verified after printing the shelf, removing the support, and observing that the shelf and interface survived removal.
4. **Additional capabilities:** Ship EXP-04, PRT-08, SUP-07 and individual SUP-08 features as qualified capabilities. Track any unavailable pairing explicitly. Claim automatic support mesh export only after SUP-07 passes on at least one real generator. A backend with only toolpath export can pass slicing and preview independently.

## First instruction to execute

Implement M0 now. Run the real JNI-to-kernel integration test before adding persistence or UI. Return the exact command that reproduces it and the measured volume. Then use that working path for M1.
