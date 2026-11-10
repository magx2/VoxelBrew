# Slicer workspace follow-up

Implemented on 2026-11-10 as an M4 usability follow-up, not a new milestone gate.

- Direct mouse placement in 2D/isometric views, transient drag, Shift 1 mm steps,
  Escape cancellation, and one undo transaction per completed move.
- Persistent plates with active-plate selection, add/remove, and object/support
  transfer. Printer/material/process settings remain shared. Existing consumers
  slice/export only the active plate. Print-setup version 2 protects multi-plate
  files from older readers; version 1 single-plate files remain readable.
- Property explanations, compact numeric values, centered new objects, an explicit
  toolpath-preview toggle, preview-only layer controls, a collapsible color legend,
  and a separate status/action bar. Light and dark screenshots were inspected.

## Automated evidence

Windows, Java 21, real OCCT 7.9.0 and PrusaSlicer 2.7.2:

```powershell
.\gradlew.bat --no-daemon :engine:test --tests dev.voxelbrew.engine.printing.PrintPlatesTest :gui:test :util:test :gui:integrationTest --tests dev.voxelbrew.gui.PrintControllerTest :gui:e2eTest --tests dev.voxelbrew.gui.WorkbenchUiTest.slicerPlateDraggingAndMultiplePlatesPersist --console=plain
```

Passed. The real slicer ignored an invalid off-bed object on an inactive plate;
switching plates invalidated the result. The focused Robot test exercised dragging,
release/undo, Escape, two plates, persistence, real slicing (25 layers), preview
controls, and both palettes. After fixing status-bar button clipping, the focused
GUI test and `:util:test` passed again.

Ubuntu WSL, independent filesystem checkout `/tmp/voxelbrew-slicer-tab.y7e31x`:

```bash
./gradlew --no-daemon --max-workers=2 :engine:test --tests dev.voxelbrew.engine.printing.PrintPlatesTest :gui:test :util:test --console=plain
```

Passed. Unit coverage includes binary/JSON persistence, active-plate isolation,
support transfer, undo, invalid plate operations, projection inversion, snapping,
and triangle picking.

Root `spotlessApply` is run before committing. Logs are in
`.tools/slicer-redesign-*.log`. JUnit reports are under the corresponding
`engine/build/reports/tests`, `gui/build/reports/tests`, and
`util/build/reports/tests` directories. Screenshots are in
`gui/build/ui-artifacts/slicer-plates-{1,2}.png`,
`slicer-process-help.png`, and `slicer-preview-{1,2}.png`.

## Scope and remaining coverage

Only the focused Windows GUI test was run; the full E2E suite and cross-platform
E2E were intentionally omitted per repository instructions. No full root milestone
gate, macOS UI, installer, or physical printer qualification is claimed. Real
slicing was validated on Windows; Ubuntu coverage here is compilation/unit tests.
Per-plate process overrides and batch slicing remain outside this change.

## Camera follow-up

Slicer now shares Designer's orbit convention and wheel sensitivity. Right-drag
and Shift + middle-drag pan; middle-drag and left-drag on empty space orbit in 3D
and pan in 2D. Each view keeps separate transient pan/zoom/orientation state.
Fit view resets pan/zoom. Object placement inverts the current camera projection;
an edge-on bed does not produce unbounded placement coordinates.

Windows validation ran in `.tools/slicer-camera-validation` to avoid concurrent
build-output changes in the main checkout. The isolated copy used the existing
Windows OCCT runtime and an explicit `VOXELBREW_SLICER` executable path:

```powershell
.\gradlew.bat --no-daemon :gui:test --tests dev.voxelbrew.gui.PrintCameraTest --tests dev.voxelbrew.gui.PrintPlacementTest :gui:e2eTest --tests dev.voxelbrew.gui.WorkbenchUiTest.slicerCameraMatchesDesignerGesturesWithoutEditingPlacement --tests dev.voxelbrew.gui.WorkbenchUiTest.slicerPlateDraggingAndMultiplePlatesPersist -x :engine:buildNative --console=plain
```

Passed. The camera Robot test observes rendered changes for all mouse gestures,
verifies unchanged document state, then drags an object after navigation and
undoes that placement in both views. The existing plate/persistence/real-slice
test also passed. Resulting 2D and rotated 3D screenshots were inspected.

Ubuntu ran `:gui:test --tests dev.voxelbrew.gui.PrintCameraTest --tests
dev.voxelbrew.gui.PrintPlacementTest` on the same source snapshot: passed.
Logs: `.tools/slicer-camera-isolated-final.log` and
`.tools/slicer-camera-ubuntu.log`. Screenshots:
`.tools/slicer-camera-validation/gui/build/ui-artifacts/slicer-camera-print-view-{2d,3d}.png`.
No Linux E2E or full-suite run is claimed.

## Plate-name follow-up

Double-click a plate caption to edit its display name inline. Enter saves one
undoable change; Escape or losing focus cancels. Empty/control-character names and
names longer than 128 characters are rejected. Unchanged captions do not edit the
design. Names are independent of stable plate IDs and output filenames, persist in
binary/JSON documents, and survive placement/profile/support edits and transfers.
Named setups use print-setup version 3; older readers reject this version rather
than silently dropping names. Versions 1 and 2 remain readable.

Focused Windows validation:

```powershell
.\gradlew.bat --no-daemon :engine:test --tests dev.voxelbrew.engine.printing.PrintPlatesTest :gui:e2eTest --tests dev.voxelbrew.gui.WorkbenchUiTest.plateNamesEditInlineCancelUndoAndPersist --console=plain
```

Passed: inline editing of active/inactive plates, Enter, Escape, focus-loss
cancellation, invalid input, undo, unchanged captions, and save/reopen. Ubuntu
validation runs the same `PrintPlatesTest` and GUI compilation in a separate Linux
checkout. Logs: `.tools/plate-names-*.log`. Only the focused native-platform GUI
test is included; no full E2E or new milestone gate is claimed.

## Rotation-gizmo follow-up

Selected objects show colored X/Y/Z rings and letter handles in 2D and 3D.
Dragging previews world-axis rotation about the object center while preserving
bed clearance. Shift snaps to 15 degrees; release commits one undoable placement;
Escape cancels. Edge-on rings use projected tangent motion. Camera gestures and
object translation remain available.

Windows validation passed:

```powershell
.\gradlew.bat --no-daemon spotlessApply :gui:test --tests '*PrintRotationGizmoTest' --tests '*PrintCameraTest' --tests '*PrintPlacementTest' :gui:e2eTest --tests '*WorkbenchUiTest.slicerRotationGizmosPreviewCommitUndoAndCancel' --tests '*WorkbenchUiTest.slicerCameraMatchesDesignerGesturesWithoutEditingPlacement' --console=plain
```

Unit tests cover all three axes, pivot/clearance preservation, picking, angle
wrapping, snapping and edge-on dragging. Windows Robot tests cover Z-ring dragging
in both views, transient preview, undo, cancellation, saved-document roundtrip,
and camera/translation regression. Screenshots in
`gui/build/ui-artifacts/slicer-gizmos-print-view-{2d,3d}.png` were visually inspected.
Log: `.tools/slicer-gizmo-tests.log`.

Ubuntu validation passed on the same source snapshot in a separate Linux checkout:

```bash
./gradlew --no-daemon --max-workers=2 :gui:test --tests '*PrintRotationGizmoTest' --tests '*PrintCameraTest' --tests '*PrintPlacementTest'
```

Log: `.tools/slicer-gizmo-ubuntu.log`. No Linux E2E, full E2E suite, new milestone
gate, or physical-printer qualification is claimed. X/Y mouse gestures and dark
palette rendering were not separately exercised by Robot tests.

## Slice-all follow-up

Slice all plates processes non-empty plates sequentially without changing the
selected plate or undo history. Each output uses the project name, padded plate
number and escaped plate name in the destination folder. The overwrite checkbox
applies to every output. All plate geometry and existing-file conflicts are
checked before slicing starts. Failure, cancellation or editing stops remaining
work; files already completed remain available. Preview shows the originally
selected plate only (no preview if that plate was empty).

Windows checks passed with real PrusaSlicer:

```powershell
.\gradlew.bat --no-daemon spotlessApply :gui:integrationTest --tests '*PrintControllerTest' :gui:e2eTest --tests '*WorkbenchUiTest.sliceAllPlatesWritesSeparateJobsWithoutChangingSelection' --console=plain
```

Coverage includes two real outputs, empty-plate skipping, active-preview identity,
preflight rejection of an invalid later plate before any publication, cancellation
after the first output, and existing stale-result/single-plate lifecycle tests.
The focused Windows Robot test clicks Slice all plates and verifies both named
files and unchanged plate selection. Log: `.tools/slice-all-tests.log`.

Ubuntu passed compilation, destination-name unit tests and integration-test
compilation in the separate Linux checkout:

```bash
./gradlew --no-daemon --max-workers=2 :gui:test --tests '*PrintingPaneTest' :gui:compileIntegrationTestJava
```

Log: `.tools/slice-all-ubuntu.log`. Real slicing was tested on Windows only;
no Linux E2E, full E2E suite or new milestone gate was run.
