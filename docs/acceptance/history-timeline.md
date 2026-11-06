# M3 follow-up — compact feature timeline

Date: 2026-11-06. Scope: replace the history heading, text chips and separate slider
with a compact feature strip, transport controls and an integrated rollback marker.
See [the interaction guide](../history-timeline.md).

`WorkbenchUiTest.compactHistorySupportsPickingDraggingPlaybackAndIndependentUndo`
creates the reference part with real controls. It checks icon selection, the compact
height, both palettes, boundary/step navigation, native mouse dragging, Home/End,
undo/redo independence, pause, ordered playback and final volume. Screenshots are
`gui/build/ui-artifacts/timeline-light.png` and `timeline-dark.png`.

`WorkbenchUiTest.historyScrollsLongDesignsAndStopsAtFailedOperations` opens a long
editable history, checks horizontal scrolling, failed-feature decoration, playback
stopping on rebuild failure and cancellation when creating a new document. Existing
Robot workflows continue to exercise feature inspection, repair, files and rollback.
The catalog checks cover English and Polish messages and action descriptions.

The initial full runs exposed Button keyboard traversal stealing marker focus and
a small horizontal-scroll remainder at the first operation. Keyboard events now
reach the marker before default button traversal, and boundary navigation aligns
the strip exactly. A Windows grid-orbit regression also exposed subpixel sketch
strokes after the viewport became taller. Constrained curves now have a four-pixel
screen-space picking tolerance, behind normal body/handle/plane hit priority;
Robot checks both exact and near-edge drags. CAD dimensions and rendered stroke
geometry are unchanged. Initial logs and JUnit failures remain under
`.tools/timeline-initial-*`.

Full root qualification commands, without task exclusions:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\amazon-corretto-21.0.6.7.1-windows-x64-jdk'
$env:GRADLE_USER_HOME='D:\repos\VoxelBrew\.tools\gradle-home'
.\gradlew.bat check -PnativeJobs=8 --continue --no-daemon
```

```bash
cd /tmp/voxelbrew-m0
export JAVA_HOME=/home/martin/.sdkman/candidates/java/21-tem
export PATH="$JAVA_HOME/bin:$PATH"
bash scripts/with-virtual-desktop.sh ./gradlew check --continue
```

The Windows and separate Ubuntu filesystem checkouts use the same frozen source
patch. Local results, exact source hash, commands, platform details, cleanup audits,
reports and screenshots are retained in `.tools/timeline-qualification.json`,
`.tools/timeline-root-{windows,linux}.log` and
`.tools/timeline-{windows,linux}-evidence.zip`. The qualification JSON records the
final commit and actual test totals after both gates pass. Test-created workspaces
are cleaned; diagnostic reports and screenshots remain available.

Windows uses actual JavaFX/Direct3D rendering and native Robot input. Ubuntu uses
Xvfb/Mesa with its own OCCT build. This change does not qualify macOS graphics;
GitHub checks and publishing are deferred until requested.
