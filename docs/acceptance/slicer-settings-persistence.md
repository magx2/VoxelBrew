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

## Importer/exporter follow-up audit — 2026-11-10

Reviewed all seven built-in exporters (VXB, JSON, STL, OBJ, STEP, portable 3MF and
Prusa project) and all three importers (VXB, JSON and G-code), including registry
and direct-writer entry points. Geometry-only exporters may omit slicer settings;
G-code import returns toolpaths rather than an editable manufacturing setup.
There are no built-in STL, OBJ, STEP or 3MF importers.

Found and fixed one additional gap: direct `MeshExporter.writeFile` calls discarded
manufacturing metadata before invoking the 3MF writer. The helper now forwards the
snapshot's print setup. Regression tests exercise that path, both VXB/JSON provider
round trips (including custom printer dimensions and drafts), and native Prusa
project placement/process precedence over a requested Designer export.

Windows: **46 tests passed, zero failures/skips**, including real OCCT and independent
STL/OBJ/3MF/STEP checks, importer/plugin behavior, cancellation and failed-write
protection. Command:

```powershell
.\gradlew.bat spotlessApply :api:import:test :api:export:test :impl:importer:test :impl:importer:integrationTest :impl:exporter:test :impl:exporter:integrationTest --console=plain
```

Ubuntu: API and unit suites passed in a fresh `/tmp/voxelbrew-io-audit.*` source copy:

```bash
./gradlew :api:import:test :api:export:test :impl:importer:test :impl:exporter:test --console=plain
```

The initial Ubuntu invocation also requested importer integration tests and stopped
at `:engine:configureOcct`: this checkout has no native prerequisites. Ubuntu native
integration remains unverified for this follow-up. No E2E tests, external receiver
checks or full root gate runs were requested or performed for this audit.
Logs: `.tools/io-audit-windows.log`, `.tools/io-audit-linux.log` (prerequisite failure),
and `.tools/io-audit-linux-unit.log`. Reports remain in the respective modules'
`build/test-results` and `build/reports/tests` directories.
