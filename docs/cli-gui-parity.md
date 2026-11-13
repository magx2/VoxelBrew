# Workspace CLI

The public command tree follows **Designer**, **Slicer**, and **Printer**. File
operations use `document`; application preferences use `settings`. The previous
flat command syntax is removed. Commands show their complete argument list with
`--help`. Enum values accept either engine spelling or lowercase kebab-case.

```sh
cli document new part.vxb
cli --document part.vxb designer sketch rectangle --id plate --width 60 --height 40
cli --document part.vxb designer solid extrude --id thickness --sketch plate --distance "8 mm"
cli --document part.vxb designer inspect
cli --document part.vxb slicer placement add --instance first --body thickness.body
cli --document part.vxb slicer job slice
cli --document part.vxb slicer job inspect --from-layer 1 --to-layer 5 --role perimeter --paths
cli --document part.vxb slicer job export --format gcode --output part.gcode
```

Document commands also accept a positional file. Supplying conflicting positional
and `--document` paths is an error. Invalid invocations return 2; operation errors
return 1. Output replacement requires explicit overwrite where supported by the
underlying writer. Modeling commands retain editable failed definitions and return
diagnostics when geometry replay fails.

`--output-format json` wraps command output in an object with `command`, `exitCode`,
and `output` (an array of result lines). Diagnostics remain on stderr. This envelope
does not yet provide typed domain objects for every query.

## Designer inventory

| GUI control                                    | Public CLI mapping                                                                                                                              |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| New/Open/Import/Save/Save As/Export            | `document new/import/export`; session `document open/save/save-as`                                                                              |
| Import/export provider discovery               | `document importers/exporters`; provider options via repeatable `--option`                                                                      |
| Tree, measurements, point classification       | `designer inspect [--point X,Y,Z]`                                                                                                              |
| Body labels and material/color                 | `designer body rename`; `designer body style set --body ID --material LABEL --color #RRGGBB`; `--reset`                                         |
| New sketch                                     | `designer sketch new`; primitive `rectangle/circle` convenience commands                                                                        |
| Sketch plane                                   | `--plane xy/xz/yz`, `--offset-z`, `--plane-id`, `--profile-id`; `--construction-plane ID`; `--face-body ID --face INDEX --revision FINGERPRINT` |
| Point/line/rectangle/circle/arc/Bezier/polygon | `designer sketch entity/draw/polygon`; numeric coordinates and entity references                                                                |
| More sketch types                              | `designer sketch draw --tool TYPE --points ... --refs ...`                                                                                      |
| Creation settings                              | `--sides`, `--degree`, `--closed`, `--construction`, `--radius`, `--rho`, `--text`, `--font`, `--style`, `--height`, `--angle`, `--spacing`     |
| Text/path editing                              | `designer sketch text-edit --text ... [--font ...] [--path ...]`; empty path detaches                                                           |
| Entity inspection/deletion/replacement         | `designer sketch list/remove-entity/entity --replace`                                                                                           |
| Point dragging                                 | `designer sketch move --point REF --x X --y Y`                                                                                                  |
| Geometric constraints                          | `designer sketch constrain --type TYPE --refs ...`; optional tangency branch                                                                    |
| Driving dimensions                             | `designer sketch constrain --value ...`; `designer feature set --field constraint:ID`; parameter bindings                                       |
| Remove constraint/unfix                        | `designer sketch remove-constraint`                                                                                                             |
| Construction curves/profile boundaries         | entity construction flag and `designer sketch profile --entities ...`                                                                           |
| Solver status/conflicts                        | `designer sketch solve/list`                                                                                                                    |
| Sketch Finish/Cancel/local undo                | Session sketch draft commands below                                                                                                             |
| Extrude/region choice                          | `designer solid extrude --sketch ID --distance EXPRESSION [--region PROFILE_ID]`; `designer sketch regions` lists selections                    |
| Through cut/split                              | `designer solid cut/split`; source sketch/body/output, split axis and offset                                                                    |
| Fillet/chamfer                                 | `designer solid fillet/chamfer --size MM --body-feature ID [--body-output OUTPUT] --edges INDICES` or `--faces INDICES`                         |
| Shell                                          | `designer solid shell --size MM --body-feature ID --face INDEX`                                                                                 |
| Construction tools                             | `designer construction list/add/set/preview`; tool, ordered repeatable inputs, value and alternate-midplane flag                                |
| Feature edits/repair/plugins                   | `designer feature set/repair/add/types`; provider parameters remain provider-owned                                                              |
| Timeline/delete                                | `designer history list/cursor`; `designer feature remove --from INDEX --to INDEX`                                                               |
| Parameters/configurations                      | `designer parameter list/set/rename/remove/bind`; `designer configuration list/add/set/rename/remove/activate`                                  |

All 25 advanced `SketchTools.Tool` methods are available: three rectangles, five
circles, three arcs, three polygons, ellipse, five slots, fit/control splines,
conic, text and text-on-path. Point/line/Bezier primitives remain available through
the entity API. All 26 `SketchGeometry.Type` constraint and dimension types use the
shared solver. Coordinate/radius dimensions use mm, angles use degrees in drawing
commands; raw entity curve parameters retain the units documented by their help.

All 20 construction methods use the shared engine evaluator. An input is
`selection:source:output:index`, or `position:x:y:z` for an explicit point. Origin
references leave source/output empty, for example `origin_plane:::0`. Tool order
and reference roles follow `ConstructionTools.Tool.inputs()`. Preview evaluates
without saving. `designer body topology` lists planar faces, face/edge incidence,
edge samples and a revision fingerprint. Faces are zero-based in topology output;
shell's removed-face argument and edge selections are one-based, matching the
engine feature definitions. Supply `--revision` to reject a changed source file.

## Slicer and Printer inventory

| GUI control                        | Public CLI mapping                                                                                                                                                        |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------ |
| Plate selection/add/name/remove    | `slicer plate list/select/add/rename/remove`; populated removal requires `--force`; last plate is protected                                                               |
| Move object to plate               | `slicer placement move-to-plate --instance ID --plate ID`                                                                                                                 |
| Add/update instance                | `slicer placement add`; instance/body IDs, XYZ translation, XYZ rotation                                                                                                  |
| Duplicate/remove/lay on face       | `slicer placement duplicate/remove/lay-on-face`                                                                                                                           |
| Automatic arrangement/orientation  | `slicer placement arrange [--gap MM]`; `orient --instance ID` or `--all`; shared GUI/engine algorithms                                                                    |
| Bed width/depth/height             | `slicer bed show/set`; device-owned bed editing is rejected                                                                                                               |
| Physical device/profile target     | `slicer target show/set/clear`; choose `--device NAME` or `--profile ID`                                                                                                  |
| Installed nozzle                   | `slicer process nozzle --diameter MM`                                                                                                                                     |
| Process tab                        | `slicer process set`: backend, printer profile, material, layer height, walls, infill, automatic supports, bed-only, threshold, contact gap, interface layers, brim, raft |
| Support master switch              | `slicer supports enable/disable`; definitions are retained                                                                                                                |
| Support boxes/pillars              | `slicer supports set/remove`: ID, instance, blocker/enforcer/solid-support role, body-space XYZ and width/depth/height                                                    |
| Validation                         | `slicer validate`; shared object-specific mesh/placement diagnostics                                                                                                      |
| Slice selected/all                 | `slicer job slice [--plate ID                                                                                                                                             | --all]`; optional output file/directory; no output path uses temporary publication and stores jobs in the document |
| Job summary/preview                | `slicer job list/inspect`; stored document job or independent G-code file, layer range, role filters, paths and backend estimates/provenance                              |
| Clean slicing                      | `slicer job clear`                                                                                                                                                        |
| Job/package export                 | `slicer job export --format gcode` or `gcode.3mf`; `--plate`, `--output`, explicit overwrite; original package bytes are retained                                         |
| Saved tab draft fields/preferences | `slicer workspace show/set/reset`; raw saved field keys, separate from applied manufacturing values                                                                       |
| Backend settings                   | `slicer backend list/describe/configure`; every provider configuration option and executable path                                                                         |
| Printer catalog                    | `slicer profile list/show/reload/path/export/visibility`; exported copies/images/notices, hide/unhide/select-all/deselect-all/invert                                      |
| Connection configuration/test      | `printer device list/show/add/set/remove/test`; name/kind/profile/address/serial/model and `--secret-file`                                                                |
| Connection and monitoring          | `printer status --device NAME [--watch]`                                                                                                                                  |
| Upload/Print                       | `printer upload/print --device NAME [--plate ID]`; same compatibility gates as GUI; no automatic start retry                                                              |
| Pause/resume/cancel                | `printer pause/resume/cancel --device NAME`                                                                                                                               |

Settings are exposed by `settings show/set/reset`, including language, save folder,
appearance and rendering style. `settings update` and `settings update check` use
the shared updater. Geometry export retains body/instance selection, design/build
space, model/support/all contents, tolerances and provider-specific options.

## Sessions

`cli --document FILE session --script commands.json` consumes a JSON array of
argument arrays. Without `--script`, stdin accepts one JSON argument array per
line; `quit` exits. Every operation uses a fresh parser. Edits remain in memory
until `document save`; `document save-as PATH` creates a new destination without
overwrite. Batch execution stops at the first failure and drops unsaved changes.
Already published files and physical printer actions are external side effects.

`["designer","sketch","begin"]` opens a sketch-edit transaction. Issue sketch and
parameter operations, then `finish` to commit one document undo step or `cancel`
to discard. `undo`/`redo` operate locally inside that draft and on the document
outside it. `document open PATH` discards unsaved state and clears undo history.
Timeline cursor changes do not create undo entries. `slicer job slice --background` runs in a private child process. Session
`slicer job status/wait/cancel` controls it. No background output destination is
accepted: wait for a current result, then export it explicitly. Edits or target
changes prevent an older result from becoming current. Session exit cancels the
process tree and removes its private snapshot and diagnostics. Foreground slicing is
cancelled by process interruption; closing a CLI never stops a physical print.

## Remaining parity work

This increment does **not** claim the full proposed parity gate. Query JSON currently uses result-line envelopes rather than typed domain records.
Automatic sketch constraint inference and direct formula input for every newly
added numeric creation field remain to be completed. Feature expressions can be
edited separately with `designer feature set --expression`. Session
snapshot undo also needs qualification against every engine history invariant.

View cube/orbit/pan/zoom/fit, window chrome, focus, hover, keyboard shortcuts,
dimension-label placement, timeline playback and transient gizmos remain GUI-only.
Stored visual preferences can be edited through settings/workspace fields. No
headless image renderer is introduced. Unsupported backend features remain
capability restrictions, including generated support meshes, organic supports,
painting and multi-material operation where unavailable. Hardware qualification
remains separate from protocol and slicer tests.
