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
Per-plate process overrides, batch slicing, and free-orbit 3D navigation remain
outside this change.
