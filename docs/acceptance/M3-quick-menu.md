# M3 follow-up: contextual middle-click menu

Validated on 2026-11-12. This is a GUI maintenance change, not a new milestone gate.

Middle-click opens radial commands and a Workspace submenu in Designer, Slicer,
the sketch editor, and the Printer background. Middle-drag continues to pan;
crossing the five-pixel threshold permanently cancels menu opening for that gesture.
Commands share existing handlers and enabled states. Sketch editing requires Finish
or Cancel before switching workspaces. Document and plugin formats are unchanged.

## Automated evidence

Windows, Java 21, JavaFX 21.0.13, and real OCCT 7.9.0:

```powershell
$env:GRADLE_USER_HOME = Join-Path (Get-Location) '.tools\gradle-home'
.\gradlew.bat :gui:test --tests dev.voxelbrew.gui.MiddleGestureTest :gui:e2eTest --tests dev.voxelbrew.gui.WorkbenchUiTest.middleClickMenuUsesWorkspaceAndSketchCommands --tests dev.voxelbrew.gui.WorkbenchUiTest.polishMiddleClickMenuKeepsPanningAndFitsBothThemes --tests dev.voxelbrew.gui.WorkbenchUiTest.viewCubeSnapsOrbitsAndFitsWithoutChangingTheDocument --tests dev.voxelbrew.gui.WorkbenchUiTest.slicerCameraMatchesDesignerGesturesWithoutEditingPlacement --tests dev.voxelbrew.gui.WorkbenchUiTest.numericFormulaFieldsSupportMiddleButtonDragging --no-daemon --console=plain
```

Passed: three gesture unit tests and five targeted Robot tests. Coverage includes
click jitter, returning after crossing the drag threshold, workspace commands,
disabled Printer/sketch workspace choices, sketch cancellation, 3D view selection,
keyboard submenu activation and dismissal, outside-click dismissal, unchanged
document state for navigation, camera regressions, and numeric-field middle dragging.
The Printer context is inspected without sending physical device commands.

Ubuntu WSL used the same source snapshot in `/tmp/voxelbrew-quick-menu.kJzILQ`,
with independent Linux build output:

```bash
./gradlew :gui:test --tests dev.voxelbrew.gui.MiddleGestureTest :gui:compileE2eTestJava --no-daemon --max-workers=2 --console=plain
```

Passed: three gesture unit tests, production compilation, and E2E source compilation.
No Linux E2E tests were executed.

Logs: `.tools/quick-menu-windows-final.log`, `.tools/quick-menu-linux.log`.
Reports: `gui/build/reports/tests/test` and `gui/build/reports/tests/e2eTest` in the
respective checkouts. Root `spotlessApply` passed before commit.

## Visual review and limits

Inspected the captured English menu and Polish light/dark menus at
`gui/build/ui-artifacts/quick-menu-designer.png`, `quick-menu-polish-1.png`, and
`quick-menu-polish-2.png`. Commands and workspace controls are readable and fit
the tested screen bounds. These are captured Robot-test renders, not an independent
manual session. Full E2E, full root milestone gates, macOS, arbitrary monitor/DPI
layouts, and physical printer dispatch are not qualified by this follow-up.
