# M4 follow-up: slicer implementation module (2026-11-09)

This is a plugin-backbone increment, not a new completed manufacturing milestone.
`impl/slicer` owns Prusa/Cura adapters, discovery and job publication. The export
module retains Prusa project serialization. Both application distributions include
`slicer-impl`. Provider configuration is isolated by backend ID; CLI invocation
options merge without mutating saved/provider state. Blank GUI executable input
uses the selected backend's configured default.

## Automated checks

Passed on Windows:

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools/gradle-home'
.\gradlew.bat :impl:slicer:test :impl:slicer:integrationTest :impl:importer:test :impl:exporter:test :cli:test :gui:test :cli:installDist :gui:installDist verifyApiDependencies verifyFrontendIoDependencies spotlessCheck --console=plain
.\gradlew.bat :cli:e2eTest --tests '*WorkflowTest.splitPlacementSupportAndSlicingAcrossFreshProcesses' --console=plain
.\gradlew.bat spotlessApply --console=plain
```

Passed in separate Ubuntu checkout `/tmp/voxelbrew-slicer.4vProp` with the same
implementation sources:

```bash
./gradlew :impl:slicer:test :impl:slicer:integrationTest :impl:importer:test :impl:exporter:test :cli:test :gui:test verifyApiDependencies verifyFrontendIoDependencies -x :engine:buildNative --console=plain
```

Ubuntu reused an existing Linux OCCT/JNI runtime after `diff -qr` confirmed the
native source tree matched the prior Linux checkout. The first invocation was
cancelled because relocating the checkout unnecessarily rebuilt OCCT. No native
source changed, and no Windows native artifacts were used on Ubuntu.

The relocated real-Prusa tests passed on both platforms, including 40 deposition
layers, placements/hole, supports, process controls, cancellation and publication
protection. The independent API-only plugin still loads. New tests cover separate
provider settings, immutable invocation overrides, invalid-option isolation,
Cura process-setting precedence and Cura G-code role/layer interpretation.

Logs: `.tools/slicer-final-windows.log`, `.tools/slicer-cli-e2e.log`,
`.tools/slicer-linux-final.log`, `.tools/slicer-format.log`. Reports are under
`impl/slicer/build/reports/tests`, `impl/importer/build/reports/tests` and the
frontend module report directories. Prusa artifacts now live under
`impl/slicer/build/printing-artifacts`. Development launchers are in
`cli/build/install/cli/bin` and `gui/build/install/gui/bin`.

## Pending coverage

CuraEngine is not installed in the validation environments. The Cura adapter and
parser are unit-tested; real-engine, independent-viewer and physical-printer
qualification are pending. Cura requires matching resolved machine definitions;
advanced supports, brim and raft are explicitly rejected in this increment.
Prusa's prior hardware limitations remain unchanged.

No GUI gesture, packaged-image, macOS or Linux E2E checks were run. Only the
relevant Windows CLI E2E test ran; the full E2E suite and full root `check` were
not run for this follow-up increment.
