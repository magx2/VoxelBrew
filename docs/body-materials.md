# CAD body materials and colors

Each stable CAD body can have its own opaque sRGB color and material label, for
example red PETG and blue PLA in the same document. Select the body in the model
tree or viewport, edit **Material & color** in Properties, then **Apply**. PLA,
PETG, ABS, ASA and TPU are suggestions; custom names up to 64 characters are
supported. A blank material means unassigned. Color and material are a single
undoable edit; drafts do not alter the document. Reset removes the override.
The controls require a successful current rebuild at the end of the timeline.

Selection adds a highlight to the object's color. Clearing selection restores
the exact chosen color in either light or dark mode. New bodies use the default
blue-gray color until assigned. Cuts retain the stable body ID and its style.
Split children inherit their source's style dynamically until explicitly
assigned; resetting a child restores inheritance. Rollback preserves inactive
assignments, and changing dimensions or parameter configurations preserves them.

`.vxb` schema 7 stores body-ID/style pairs and includes them in the document
fingerprint. Older supported documents load with no overrides. Immutable
snapshots and undo/redo carry the assignments with the CAD document. Broken
feature references remain editable; style inheritance handles cycles safely.

```bash
cli body-style part.vxb
cli body-style part.vxb --body thickness.body --material PETG --color '#FF0000'
cli body-style part.vxb --body split.positive --material PLA --color '#0000FF'
cli body-style part.vxb --body split.positive --reset
```

Listing prints body ID, material and uppercase RGB hex separated by tabs.
Assignments require an explicit active body; invalid values and selections do
not overwrite the document. Omitting either material or color keeps its current
value, while reset removes both overrides.

These assignments are CAD metadata, saved in `.vxb`. They do not change the
single-material print profile, slicer temperatures, tool selection or geometry.
STL and the current 3MF exporter do not transfer these assignments. Choose actual
slicing settings in the Print workspace.

Validation covers style validation, immutable snapshots, undo/redo, deterministic
Protobuf round trips, inherited split colors, real-kernel volume invariance,
fresh-process CLI edits, and JavaFX Robot color picking and save/reopen in both
palettes. Full Windows and Ubuntu checks include these cases.
