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

## Slicer configuration menu follow-up

The top-level **Slicer → Configure slicers…** menu renders installed providers'
configuration options in separate tabs. Prusa executable/profile and Cura
executable/definition/settings fields have translated labels and Browse buttons.
Save publishes all settings atomically through user configuration, preserves
unrelated preferences and applies to the next job without restarting. Existing
jobs keep their captured backend configuration. Close/Escape discards unsaved
edits; provider diagnostics remain visible in the dialog.

Passed on Windows:

```powershell
$env:GRADLE_USER_HOME = "$PWD/.tools/gradle-home"
.\gradlew.bat spotlessApply :gui:test :util:test --console=plain
.\gradlew.bat :gui:e2eTest --tests '*WorkbenchUiTest.slicerMenuPersistsSeparateProviderSettingsAndDiscardsUnsavedEdits' --tests '*WorkbenchUiTest.printingSplitPlacementPetgAndActualPreviewMatchCli' --console=plain
```

After adding the Prusa profile Browse button, unit checks and the configuration
dialog E2E test passed again. Ubuntu unit checks passed on the same sources in
`/tmp/voxelbrew-slicer-menu.Ik6R2b` with `./gradlew :gui:test :util:test --console=plain`.
The settings unit test covers per-provider isolation, persistence, invalid-option
rejection without writes, preserving unrelated preferences and immutable captured
backend settings. Robot coverage verifies the menu, editing both providers,
saving/reopening, discarding edits and preserving the CAD document's clean state.
The real-Prusa workflow still produces 40 model layers with GUI/CLI path parity.

Logs: `.tools/slicer-menu-unit.log`, `.tools/slicer-menu-e2e.log`,
`.tools/slicer-menu-dialog-e2e.log`, `.tools/slicer-menu-linux.log`.
The reviewed dialog screenshot is `gui/build/ui-artifacts/slicer-settings.png`.
Native Browse picker gestures, full root checks, full E2E suites and Linux E2E
were not run. Cura's real-engine qualification remains pending.

## Full Windows clean-check follow-up (2026-11-10)

The user-requested full local gate passed:

```powershell
$env:GRADLE_USER_HOME = "$PWD/.tools/gradle-home"
.\gradlew.bat clean check -PnativeJobs=8 --console=plain
```

Gradle reported `BUILD SUCCESSFUL in 36m`, with 205 actionable tasks
(203 executed, two up-to-date), including native compilation, unit/integration
checks, the full Windows GUI suite, real PrusaSlicer workflows, CLI/GUI packaging,
updates and release-archive checks. No check tasks were excluded.

The initial full run found missing help descriptions for the new 2D/3D controls.
Both controls now have English/Polish help, covered by the help-catalog test.
The shortcut test helper was also updated for contextual F1 and the Settings
menu location of the complete shortcut reference. All five affected desktop
regressions passed separately and again in the final full run.

The final source changes also passed Ubuntu `:gui:test :util:test
:gui:compileE2eTestJava` in the separate filesystem checkout
`/tmp/voxelbrew-slicer-menu.Ik6R2b`. Linux E2E was intentionally not run locally.
The earlier full-check limitations above are superseded for Windows by this run;
Cura real-engine and hardware qualification remain pending.

Logs: `.tools/full-clean-check-final.log`,
`.tools/full-clean-check-first-failures.log`,
`.tools/clean-check-help-regressions.log`, `.tools/clean-check-fixes-linux.log`.

## Hosted CI timing and window synchronization follow-up

Run `42734593074` on `389d9bb` passed all Windows and macOS jobs, but the Ubuntu
header test sampled the restored width immediately after maximize. Its failure
screenshot already shows the correct 1600-pixel maximized window. The bounds
assertion now waits up to five seconds for asynchronous native window-manager
updates, retaining all four original coordinate/size assertions and tolerances.
The affected Windows Robot test passed in 28 seconds; Ubuntu E2E compilation
passed in 14 seconds. No Linux E2E was run locally.

The same hosted run exposed slow downloads from `azure.archive.ubuntu.com`:
Ubuntu shard 3 spent 7m downloading slicer dependencies and 2m23s downloading
virtual-desktop dependencies. CI now uses Ubuntu's main HTTPS archive mirror
and 20-second stalled-request timeouts with three retries. Package versions,
test selection and platform coverage are unchanged. The next hosted run
qualifies this workflow change and the Ubuntu gesture synchronization.

Logs: `.tools/ci-failed.log`, `.tools/ci-slow-ubuntu.log`,
`.tools/ci-window-regression.log`, `.tools/ci-window-linux-compile.log`.

The next run's completed Ubuntu log revealed that current hosted images select
the mirror through `/etc/apt/apt-mirrors.txt`. The workflow now updates that list
as well as the deb822 source file; modifying only the latter did not change the
selected mirror. Both locations are checked for existence for image compatibility.
