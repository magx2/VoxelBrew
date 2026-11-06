# Sketch plane selection

Click **New sketch** (K), then select a colored origin plane in the main viewport
or a flat face on an existing body. XY, XZ and YZ are also available as buttons
in Properties. Hovering over a flat face highlights its complete surface. Orbit,
pan and zoom remain available while selecting. Escape cancels without changing
the document or undo history. Curved faces cannot support a planar sketch.

After selection, the sketch editor uses the plane's local coordinates. An
optional signed offset moves the sketch along its normal. Finish sketch commits
one feature; cancelling the editor commits none. Extrusion distances and handles
follow the same normal. XY uses +Z, XZ uses −Y, and YZ uses +X for positive
extrusion. Through-all cuts operate along that normal across the target body.

Use **Change plane…** in a sketch's Properties to reattach it while preserving
its feature/profile identity, geometry, offset and parameter bindings. This is
one undoable edit. A supporting body must occur before the sketch in history.
Changing the support orientation preserves local sketch coordinates, so the
geometry rotates with its new plane.

## Saved placements and face support

Schema 8 stores a rigid local-to-model frame and optional support information.
Earlier files without a frame retain their XY placement. Units remain mm; no
camera or selection state is saved. A face frame uses deterministic right-handed
axes and the model origin projected onto its plane.

The engine queries exact analytic OCCT planes, not individual mesh triangles.
Face indices are detached alongside meshes and used only for the current
document revision. They are never saved as persistent references.

A support references an earlier body output and its oriented plane group.
Coplanar faces share one plane; parallel planes are sorted by signed offset.
The selected rank and group count are saved. On rebuild, the sketch follows that
plane's new offset, including thickness changes. Missing outputs, changed plane
counts or changed normals produce a repair diagnostic and block dependent
features. Upstream rotations require choosing the face again. This conservative
plane association is not general topology naming: changes that replace a face
with a different face at the same normal and rank cannot be distinguished.

Native transforms copy their input solids. The evaluator closes all temporary
local-coordinate solids. JavaFX receives detached meshes and plane descriptions;
native handles stay on the evaluation worker. Plane selection is cancelled when
the document revision changes, a rebuild starts or a file operation begins.

## Checks

`SketchPlaneTest` exercises all origin planes, both extrusion signs, through
holes, save/reopen, attached-plane movement, broken references, repair and
immutable rigid copies against OCCT. `FaceSupportTest` checks changed plane
groups and revision-local face indices. `ViewOrientationTest` checks conversion
between CAD and JavaFX axes.

JavaFX Robot tests click all three rendered origin planes, cancel selection,
create and reopen a face-attached sketch, edit its supporting thickness and
change its plane with undo/redo. They preserve light/dark picker screenshots in
`gui/build/ui-artifacts`. Run the complete root `check` on Windows and Ubuntu;
Linux GUI checks use `scripts/with-virtual-desktop.sh` for Xvfb/Mesa.
