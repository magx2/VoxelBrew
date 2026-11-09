# M4 follow-up: slicer workspace review (2026-11-10)

This is a UI follow-up, not a new milestone completion gate.

The body selector uses Designer names; instance selectors show body names and
instance numbers. Empty instance/support selectors are disabled with explanatory
prompts. Support roles have readable English and Polish labels. The plate has
2D top and 3D isometric views, including cumulative sliced paths in 3D.
Double-clicking its dimension caption edits width, depth and usable height.
Custom rectangular Marlin dimensions persist in the printer-profile reference
and are resolved by the shared model API for preview, validation and export.

F1 opens Designer or Slicer help, with Escape closing the help overlay. Entering
Slicer saves an unsaved/modified project first; cancellation keeps Designer open.
The G-code destination follows the project directory and basename on open/save-as.

## Passed checks

Windows, from the repository root:

```powershell
$env:GRADLE_USER_HOME = "$PWD/.tools/gradle-home"
.\gradlew.bat spotlessApply :gui:test :api:model:test :util:test --console=plain
.\gradlew.bat :gui:e2eTest --tests '*WorkbenchUiTest.printingSplitPlacementPetgAndActualPreviewMatchCli' --console=plain
```

Ubuntu, separate filesystem checkout `/tmp/voxelbrew-slicer-review.ITf4VR`, with
the same implementation sources:

```bash
./gradlew :api:model:test :gui:test :util:test --console=plain
```

The Windows Robot test covers contextual F1 help, save cancellation, saving a
modified design before entering Slicer, disabled empty selectors, the rendered
body name, 2D/3D switching, double-click plate editing to 250 × 220 mm, placement,
PETG, save/reopen, the default G-code filename, and real PrusaSlicer output with
40 model layers and GUI/CLI path parity. Unit tests cover custom printer-profile
resolution, invalid dimensions and project-derived output paths. Both language
catalogs pass their existing consistency checks.

Early runs exposed a compiler warning in the test, initial desktop focus issues,
plate-caption focus, Escape handling, and a stale body selection whose valid ID
was not rendered. These were corrected before the passing final run.

Logs: `.tools/slicer-review-check.log`, `.tools/slicer-review-e2e.log`, and
`.tools/slicer-review-linux.log`. JUnit reports are under the affected modules'
`build/reports/tests` directories. Reviewed screenshots are
`gui/build/ui-artifacts/printing-3d-placement.png`, `printing-dark.png` and
`printing-light.png`. Generated artifacts remain untracked.

## Scope and pending coverage

Only the targeted Windows slicer E2E test ran. Full root checks, the full E2E
suite, Linux/macOS E2E, installers and hardware were not run. The 3D view is a
fixed isometric projection, not an orbit camera. This change does not qualify
additional printer hardware or slicer backends.
