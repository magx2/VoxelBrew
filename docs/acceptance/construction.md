# M14 — Construction geometry qualification

The twenty commands in [the workflow](../construction.md) are implemented with
editable history, headless evaluation, persistence, reference selection and
transient preview. Schema 10 intentionally rejects older files.

## Qualified source and commands

The isolated qualification tree is based on `aceaf4def49b4d2488a96d3b2905d2f3697d7faa`
plus the construction commit containing this report. Concurrent work in the shared
checkout is outside this qualification record.

Windows checkout: `D:\repos\VoxelBrew\.tools\construction-windows`.
Java: Amazon Corretto 21.0.6.7.1; real Python 3.11 precedes WindowsApps on PATH.
Gradle cache: `D:\repos\VoxelBrew\.tools\gradle-home`.

```powershell
.\gradlew.bat spotlessApply --offline --no-daemon
.\gradlew.bat check --offline --no-daemon --no-parallel --max-workers=1
```

Ubuntu WSL checkout: `/tmp/voxelbrew-construction`, independently built with its
Linux native toolchain. Source is copied without build output or caches.

```sh
bash scripts/with-virtual-desktop.sh ./gradlew check --offline --no-daemon --no-parallel --max-workers=1 -PnativeJobs=6
```

Both full root checks passed without exclusions on 2026-11-09: Windows in
26m 15s (151 tasks), Ubuntu in 22m 51s (151 tasks). Each produced **303 JUnit
tests, zero failures and zero skipped tests**, including 40 real JavaFX Robot
scenarios. The checks also ran native OCCT, independent format readers, real
slicing, package smoke checks and release archive checks.

## Construction evidence

- Three mathematical/frame tests cover all twenty operations, degeneracy
  rejection and coordinate-system orientation.
- Five real OCCT integration tests cover persistence, supporting sketches,
  dependent solid rebuild, undo/rollback, cylinder/tangent/path references,
  fillet torus and chamfer cone references, missing-reference blocking, and
  outward offsets from reversed bottom faces.
- The construction Robot scenario checks all twenty menu entries and exercises
  creation, preview without history changes, apply, edit/cancel, Escape,
  undo/redo and repair of a broken construction reference. It does not constitute
  twenty separately tested GUI workflows.
- Windows and Ubuntu screenshots of the construction result were visually
  reviewed: translucent datum plane, history entry, inspector and no solid body.
  These are automated gesture runs and screenshot review, not manual CAD sessions.
- Localized help regression coverage protects construction feature naming and
  the existing finishing controls needed to launch the workbench.

## Artifacts

Windows logs: `.tools/construction-windows/construction-check-serial.log`.
Ubuntu log: `/tmp/voxelbrew-construction-check.log`.
Reports in either checkout: `gui/build/reports/tests/e2eTest/index.html` and
`engine/build/reports/tests/integrationTest/index.html`.
Screenshot: `gui/build/ui-artifacts/constructionMenuCreatesEditablePlaneAndCancelsDraftWithoutHistory-last.png`.

Windows application: `gui/build/app-image/VoxelBrew/VoxelBrew.exe`.
Archives: `gui/build/distributions/VoxelBrew-0.1.0-SNAPSHOT-windows-x64.zip`
and `gui/build/distributions/VoxelBrew-0.1.0-SNAPSHOT-linux-x64.zip`.
Generated evidence and binaries remain outside source control.

## Earlier failures and limits

Earlier runs exposed a Windows Store Python alias, concurrent generated-file
changes, missing help keys, obsolete toolbar/rename expectations and native popup
input sensitivity. Qualification moved to isolated trees; the tests now use real
keyboard navigation with observed popup focus and serial execution. The final
full checks above passed after the fixes; earlier failed logs are not acceptance.

Analytic surface support, straight-edge intersection contracts, single-edge paths
and topology enumeration limits are documented in the workflow. Arbitrary spline
surface tangency and general persistent topology naming are not claimed.
macOS, installer interaction and physical printing are unqualified.
