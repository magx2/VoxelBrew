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
Original Ubuntu log: `/tmp/voxelbrew-construction-qualified-check.log`.
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

## Integration with concurrent commits

The qualified feature commit is `5ef84b6`. The integration combines it with
`87b0bf2` (including the new prepared GUI documents, workspace labels, built-in
parameters and interactive sketch dimensions). Conflict resolution preserves
both sets of features. The additional integration fixes are:

- Expect the new save counter increment in the existing measurement fixture test.
- Use Save As when a Robot helper requests an explicit destination for a document
  that may already have a filename.
- Select point references through the real reference controls when dimension
  annotations overlap the origin in the existing constrained-sketch test.
- Install localized help on the newly introduced built-in-parameter checkbox.

The two previously failing GUI cases and the help regression test passed on both
Windows (2m 3s) and Ubuntu (1m 47s):

```text
:gui:e2eTest --tests '*constrainedSketchDimensionsConflictsHistoryAndFilesUseRealControls' --tests '*polishWorkbenchDialogsSketchPrintingAndLanguagePreferenceUseRealControls' :gui:test --tests '*UiHelpTest' --offline --no-daemon --no-parallel --max-workers=1
```

Use the Windows environment above and `gradlew.bat`; on Ubuntu use the virtual
desktop wrapper and add `-PnativeJobs=6`.
Logs: `.tools/construction-windows/construction-merge-targeted.log` and
`/tmp/voxelbrew-construction-merge-targeted.log`.

The final integrated **full Ubuntu check passed** in 23m 59s, with 152 tasks and
**321 JUnit tests, zero failures and zero skipped tests**. The command is the full
Ubuntu command above; log: `/tmp/voxelbrew-construction-check.log`.

The final integrated Windows full check was interrupted after consecutive Robot
mouse-delivery failures. A native foreground-window query found another desktop
application active, with no stuck modifier keys. Earlier integrated runs exposed
the test/help issues listed above; the subsequent focused runs passed. The
construction workflow and real geometry tests passed in the integrated Windows
runs. **A successful full Windows rerun of the final integrated tree is still
pending an uninterrupted desktop**; the original 303-test full Windows pass
qualifies `5ef84b6`, not the concurrent changes. Log of the interrupted run:
`.tools/construction-windows/construction-merged-check.log`.

The source commits and package artifacts are retained locally. Unrelated working
tree edits are not part of this qualification or the construction commit.

## Completed full clean-check follow-up

Both Windows and Ubuntu full `clean check` gates passed on 2026-11-09 against
`0704539`, including the integrated construction implementation and subsequent
JCAD/header and sketch changes: 326 tests per platform, zero failures or skips.
This supersedes the pending final Windows rerun above. Exact commands and artifact
paths are in [the full qualification record](full-clean-check.md).
