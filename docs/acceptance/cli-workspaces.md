# Workspace CLI maintenance verification

This work replaces flat CLI commands with workspace groups and adds headless
operations, editing sessions and printer controls. The complete inventory and
remaining work are in [the command map](../cli-gui-parity.md). This is maintenance
evidence, not completion of a new CAD milestone or the entire proposed parity gate.

## Windows

Java 21, OCCT 7.9.0 and the real PrusaSlicer backend were used. Python 3.11 was put
ahead of the Windows Store alias on PATH; Gradle used `.tools/gradle-home`.

```powershell
.\gradlew.bat spotlessApply --offline --console=plain
.\gradlew.bat :cli:test :gui:test --tests '*PrintPlacementTest' --tests '*PrintRotationGizmoTest' :cli:spotbugsMain :engine:spotbugsMain :gui:spotbugsMain :cli:installDist :cli:e2eTest --tests '*WorkflowTest.workspaceSessionCommitsSketchDraftAndPersistsFormulaBodyNameAndPlateUndo' --offline --console=plain
```

The automated suites cover 13 CLI unit cases, eight shared placement/rotation
cases, command/help discovery, old-syntax rejection, document-path handling,
plate protection, explicit session publication, undo, parser isolation and JSON
envelopes. The targeted fresh-process session scenario covers sketch finish,
formula extrusion, body rename, plate undo, real background slicing, outdated
result rejection, cancellation, and multi-plate slice selection preservation.
SpotBugs checks target the three changed application modules.

The earlier targeted real-kernel/real-slicer checks passed:

```powershell
.\gradlew.bat :cli:test :cli:installDist :cli:e2eTest --tests '*WorkflowTest.referenceWorkflowAcrossFreshProcesses' --tests '*WorkflowTest.splitPlacementSupportAndSlicingAcrossFreshProcesses' --offline --console=plain
```

The reference volume was **19099.469035085127 mm³**, bounds were
**(0,0,0)–(60,40,8) mm**, and the point in the hole classified outside. The workflow
also checks reopen/edit/rollback. The slicing case checks actual 40-layer G-code,
stored-job export, placement/support edits and overwrite protection.

Logs are `.tools/cli-workspaces-format.log`,
`.tools/cli-workspaces-native.log`, and
`.tools/cli-workspaces-final-windows.log`. Reports are under
`cli/build/reports/tests`, `gui/build/reports/tests`, and each changed module's
`build/reports/spotbugs`. An initial background-cancellation run found a Windows
redirected-log handle still open during cleanup. Parent-owned stream draining and
joining fixed it; the completion/outdated/cancellation scenario subsequently passed.
Compilation checks also caught and corrected explicit resource-close warnings in
the shutdown hook. Earlier failures are not acceptance results.

## Ubuntu WSL

The source is copied without `.git`, `.tools`, Gradle caches or build output into a
fresh Ubuntu filesystem directory created with
`mktemp -d /tmp/voxelbrew-cli-parity.XXXXXX`. The latest path is recorded locally in
`.tools/cli-workspaces-linux-path.txt`. Ubuntu uses its own Java 21 and Gradle cache.

```sh
./gradlew :cli:test :gui:test --tests '*PrintPlacementTest' --tests '*PrintRotationGizmoTest' --offline --console=plain
```

The log is `.tools/cli-workspaces-final-linux.log`; reports are in the isolated
checkout's module `build/reports/tests` directories. No Windows native output is
copied into that checkout.

## Limits

Only targeted Windows E2E scenarios run. Full E2E suites, Linux/macOS E2E,
full root milestone gates, GUI gesture qualification, installers and physical
hardware checks are omitted. Ubuntu checks here are unit checks, not Linux native
or real-slicer qualification. New physical-device commands reuse the existing
printer runtime and compatibility rules but have no new hardware qualification.
No milestone commit is made while the parity inventory still has remaining work.

## Test-pyramid follow-up

Added **24 unit tests and eight integration tests**, with no new E2E tests or
production changes. Unit tests run first; integration tests run after they pass.

- `DeviceCommandsTest`: nine port-based cases cover credential redaction, stable
  identity on rename, secret input, readiness and compatibility guards, exact
  upload/start/control ordering, no uncertain-start retry, connection shutdown,
  temporary artifact cleanup and target/profile selection.
- `JobInspectionTest`: four cases cover actual layer/role selection, estimates,
  embedded plate selection, invalid ranges and conflicting inputs.
- Five additional `WorkspaceCommandsTest` cases cover one-step sketch commits,
  draft cancel/local undo/redo, draft operation guards, independent rollback,
  monotonic IDs, stale-job invalidation, redo branching and attached support moves.
- `PrintOrientationTest`: six engine unit cases use analytic meshes to check
  world/local axes, identity/center/clearance preservation, input immutability,
  invalid rotations, broad-face orientation and unsupported mesh rejection.
- `WorkspaceIntegrationTest`: four in-process cases use real OCCT and atomic
  documents for finishing/formula persistence, transient construction previews,
  stale-revision protection and placement/job invalidation.
- `PreferenceIntegrationTest`: four cases use actual shared configuration and
  catalogs for preference round trips, visibility operations, backend option
  persistence/rejection and independently reloaded exported profiles.

Windows commands, in order (same Python/PATH and Gradle-home setup as above):

```powershell
.\gradlew.bat :cli:test :engine:test --tests '*PrintOrientationTest' --offline --console=plain
.\gradlew.bat :cli:integrationTest --tests '*WorkspaceIntegrationTest' --tests '*PreferenceIntegrationTest' --offline --console=plain
.\gradlew.bat spotlessApply --offline --console=plain
```

**Passed:** 31 CLI unit tests plus six selected engine unit tests; eight selected
CLI integration tests. Zero failures or skips. Logs:
`.tools/cli-test-pyramid-unit.log`, `.tools/cli-test-pyramid-integration.log`, and
`.tools/cli-test-pyramid-format.log`. Reports are under `cli/build/reports/tests`
and `engine/build/reports/tests`.

A fresh Ubuntu filesystem snapshot, recorded in
`.tools/cli-test-pyramid-linux-path.txt`, also passed:

```sh
./gradlew :cli:test :engine:test --tests '*PrintOrientationTest' --offline --console=plain
```

**37 unit tests passed**, zero failures/skips. Log:
`.tools/cli-test-pyramid-linux.log`. Ubuntu native integration, hardware and full
root gates were not run for this test-only follow-up. The existing targeted
Windows process/slicer E2E evidence above remains separate; it was not rerun or
expanded because this follow-up changes tests only.
