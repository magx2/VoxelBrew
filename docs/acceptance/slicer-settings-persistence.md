# Slicer settings persistence maintenance — 2026-11-10

Maintenance of M4 persistence/export behavior; this does not reopen or complete a
new milestone gate.

VXB and JSON retain applied process/support/placement/plate/printer definitions and
slicer tab drafts, selections, job destination, overwrite and view preferences.
Apply buttons retain their existing meaning. Portable 3MF prioritizes a prepared
slicer plate and includes Protobuf JSON setup metadata; OBJ keeps its existing
selection semantics. Native vendor settings still use the Prusa project exporter.

## Automated verification

Windows, Java 21, real OCCT 7.9.0:

```powershell
$env:GRADLE_USER_HOME = "$PWD/.tools/gradle-home"
.\gradlew.bat spotlessApply :engine:test :gui:test :impl:exporter:test :impl:exporter:integrationTest --tests dev.voxelbrew.exporter.UnifiedExportTest --tests dev.voxelbrew.exporter.ExportTest :gui:e2eTest --tests dev.voxelbrew.gui.WorkbenchUiTest.slicerTabsAndJobPreferencesSurviveSaveAndReopen --console=plain
```

Passed: binary/JSON round trips, immutable settings through plate/profile edits,
pending-field capture by save and JSON export, independent XML inspection of 3MF
transforms/metadata, unchanged OBJ design bounds, and an actual JavaFX save/reopen
across Placement, Process, Supports and Job. The desktop test uses controller file
APIs and Robot interaction with slicer controls. An initial test attempt used an
obsolete shared `open` control ID; the focused test now uses the current controller
API. No application failure was hidden by skipping the test.

Ubuntu WSL, separate source copy `/tmp/voxelbrew-slicer-persistence.TvhbIW`:

```bash
./gradlew :engine:test :gui:test :impl:exporter:test --console=plain
```

Passed. No shared Windows build output. Full root checks, Linux native integration,
external slicer receiver checks, hardware and manual visual inspection were not run
for this maintenance change. Only the targeted Windows E2E test ran; the full E2E
suite and cross-platform E2E runs were intentionally omitted.

Local logs: `.tools/slicer-persistence-final-windows.log`,
`.tools/slicer-persistence-final-linux.log`. Reports are under each tested module's
`build/reports/tests/` and `build/test-results/`; GUI artifacts are in
`gui/build/ui-artifacts/`. Generated files are not committed.
