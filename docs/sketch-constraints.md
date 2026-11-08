# Constrained 2D sketches (M7)

Choose **New sketch…** in the Design workbench. To constrain an existing
rectangle or circle, select it and choose **Edit sketch / constraints…** in
Properties. Conversion preserves its feature, plane and profile identities,
dimensions and position. Existing extrusions and cuts keep their references.
Use **More sketch types…** for the expanded M8 geometry tools; see
[the type inventory and workflow](sketch-types.md).

## Drawing and selection

Single-letter shortcuts are always underlined in tool button labels. When the
translated label does not contain that letter, it is appended in parentheses:
for example, Polish `Koło (C)` underlines the `C`. The existing shortcuts and
typing safeguards are unchanged; modifier combinations remain in tooltips and
the keyboard-shortcuts reference.

Sketches use millimeters on XY or a parallel offset plane. New sketches have a
Plane Z field; edit a shared plane through the main property inspector.

- Line and rectangle: click two endpoints/opposite corners.
- Circle: click a center and radius point.
- Arc: click center, start and end, counterclockwise.
- Spline: click start, first control point, second control point and end. This
  is a cubic Bezier curve, with visible draggable control points.
- Polygon: enter a side count (3–32), then click center and a vertex. A regular
  polygon constraint maintains equal sides and equal turning angles.
- Point: click its position. Points and construction geometry do not form solids.

**Auto constraints** snaps drawing previews and clicks to nearby points, line
segments and curves within eight screen pixels. An amber ring marks the target.
Points take priority over curves, and geometry snaps take priority over
horizontal/vertical alignment. Supported point placements retain coincident
constraints. It also adds horizontal/vertical line constraints.
Rectangles include corner coincidences and horizontal/vertical
constraints. Use **Construction** before drawing, or toggle selected geometry
in the side panel. **Use selected as profile** and **Use all as profile** choose
the curves used for extrusion/cutting, excluding construction geometry.

Click a curve or endpoint to select it; Shift-click adds or removes a selection.
The reference dropdown selects overlapping endpoints and exact named references.
Middle-drag pans, the wheel zooms and **Fit** frames the sketch. Drag endpoints,
centers or control points to reshape while respecting constraints. Escape
cancels a drag or unfinished drawing; releasing a valid drag adds one local
undo entry. Fixed geometry cannot be dragged away from its fixed position.

## Constraints and dimensions

Use the **Constraints** menu or red symbol toolbar. Select geometry first, or
choose a constraint and then select its references. Regular polygon uses
**Apply active constraint** after all its ordered edges have been selected.

| Constraint               | Selection                                                                                             |
| ------------------------ | ----------------------------------------------------------------------------------------------------- |
| Horizontal / Vertical    | One line, or two points                                                                               |
| Coincident               | Two points, or a point and a line/circle/arc/spline                                                   |
| Tangent                  | Line and circle; two circles; or two curve endpoints                                                  |
| Equal                    | Two lines (length), or two circles/arcs (radius)                                                      |
| Parallel / Perpendicular | Two lines                                                                                             |
| Fix / Unfix              | A point or complete entity; select its Fix constraint and Remove / Unfix to release it                |
| Midpoint                 | Point first, then a line or arc                                                                       |
| Concentric               | Two circles/arcs                                                                                      |
| Collinear                | Two lines                                                                                             |
| Symmetry                 | Two points or two entities of the same kind, followed by a line defining the symmetry axis            |
| Curvature                | Two curve endpoints, including at least one spline; joins position, tangent and signed curvature (G2) |
| Regular polygon          | Connected line edges in boundary order                                                                |

The dimension selector supports length, distance, signed X/Y distance, radius,
diameter and angle (degrees). Select a line for length, circular curve for
radius/diameter, two points for distances, or two lines for angle. Signed X/Y
distance measures the second selected point minus the first. Select an existing
dimension in the constraint list to edit its value. The list displays values
and stable IDs; badges select their corresponding constraint.

The status reports remaining degrees of freedom. Conflicts identify constraints
and disable **Finish sketch**. Keep editing a dimension, remove a constraint,
or use local Undo to repair the draft. Missing references can be repaired by
removing the broken constraint and applying it to the intended geometry.

**Finish sketch** commits one document operation. **Cancel** discards the entire
draft, including local undo/redo. After finishing, normal document undo/redo
applies. Adding sketches requires returning the timeline to its end. Solves run
on a background worker; obsolete results cannot overwrite later edits.

## Solids and persistence

Extrude or through-cut closed regions, optionally with closed holes.
Disconnected regions create separate bodies; nested islands become separate
regions. Lines, circles, arcs, ellipses, conics and spline curves remain exact OCCT curves; screen samples
do not become polygonal solid boundaries. Signed extrusions are supported.
Open sketches can be saved and edited but cannot be extruded. Invalid or
intersecting boundaries require repair.

The `.jcad` schema is version 5, with entity/constraint IDs, indexed spline references,
parameters, text outlines, construction flags and selected boundary curves. Versions 1–4 remain
readable. Older applications reject unsupported data rather than rewriting it.
Body results and display geometry remain disposable.

The current limits are 64 entities and 256 constraints per sketch, XY-parallel
planes, and 32 points per fit/control spline. The local nonlinear solver may need a better
initial pose for difficult configurations. A conflicting solve never commits
invalid geometry. Residual acceptance is `1e-6` in each equation's units; kernel
reference-part tolerances remain 0.001 mm bounds and 0.01 mm³ volume. This is a
focused sketch workflow, not complete Fusion feature parity: trim, offset,
projection, patterns and generated-face attachment remain separate work.

## CLI example

Use the installed `cli` launcher (`cli.bat` on Windows). Every command reopens
the document; both frontends use the same engine solver and persistence.

```sh
cli new constrained.jcad
cli rectangle constrained.jcad --id plate --width 60 --height 40
cli sketch convert constrained.jcad --sketch plate
cli sketch entity constrained.jcad --sketch plate --id hole --kind CIRCLE --values 10,10,2
cli sketch constrain constrained.jcad --sketch plate --id fix-hole --type FIX --refs hole
cli sketch list constrained.jcad --sketch plate
cli extrude constrained.jcad --id thickness --sketch plate --distance 8
cli inspect constrained.jcad --point 10,10,4
```

This produces the reference plate with volume `19099.469035085127 mm³` and an
empty hole at the queried point. `sketch new`, `entity`, `polygon`, `constrain`,
`remove-constraint`, `remove-entity`, `profile`, `move` and `solve` expose general
editing. Entity references are names such as `edge.start`, `edge.end`,
`circle.center`, `spline.control1` or `origin.point`; a bare ID selects an entity.
Arc entity parameters use radians, while driving angle dimensions use degrees.
Run `cli sketch <command> --help` for parameter order and options. Edit a saved
driving dimension with `cli edit file.jcad --feature sketch-id --field
constraint:dimension-id --value 80`. Invalid constraints and impossible point
moves leave the prior document byte-for-byte unchanged.

Constraint terminology follows the
[Fusion sketch constraint reference](https://help.autodesk.com/cloudhelp/ENU/Fusion-Sketch/files/SKT-CONSTRAINTS.htm).
Automated coverage and reproduction commands are in [M7 acceptance](acceptance/M7.md).
