# M3 enhancement — view cube navigation

The Design viewport has a beveled navigation cube with six face, twelve edge,
and eight corner targets. It follows the main camera rotation in Z-up model
coordinates. Face labels, axis colors, hover/focus highlights, and both palettes
are rendered with JavaFX vector nodes. The cube's primary drag orbits the model;
Home restores the existing isometric orientation and fits current geometry.
The cube menu and view selector expose all six standard views. Camera changes
remain outside the document, dirty state, and undo/redo history.

## Automated coverage

- `ViewOrientationTest` checks all 26 view directions against independent JavaFX
  rotation matrices, verifies the CAD coordinate basis, standard view names,
  angle wrapping, and invalid angle handling. These unit tests need no window,
  native CAD kernel, or graphics device.
- `WorkbenchUiTest.viewCubeSnapsOrbitsAndFitsWithoutChangingTheDocument` creates
  and saves a plate through actual controls. Robot picks every face via visible
  edge targets, picks a corner, drags both the viewport and cube, pans/zooms,
  restores the fitted Home projection, and selects a view from the cube menu.
  It checks view-selector synchronization, unchanged session revision/history
  and clean state, sketch-input isolation, and body picking around the overlay.
  Both light and dark screenshots are saved.
- The focused interaction run passed under WSLg and under Xvfb/Mesa on Ubuntu
  24.04 / WSL2 with Java 21 and JavaFX 21.0.13. The screenshot review prompted
  a separate axis triad so the cube cannot obscure the Z label.

## Reproduction and artifacts

Windows uses `gradlew.bat check`; the separate Ubuntu checkout uses:

```sh
xvfb-run -a -s "-screen 0 1600x1000x24" env LIBGL_ALWAYS_SOFTWARE=1 ./gradlew check
```

The focused WSLg command is:

```sh
LIBGL_ALWAYS_SOFTWARE=1 ./gradlew :gui:e2eTest \
  --tests '*viewCubeSnapsOrbitsAndFitsWithoutChangingTheDocument'
```

Reports are in `gui/build/reports/tests/{test,e2eTest}`. Screenshots are
`gui/build/ui-artifacts/view-cube-light.png` and `view-cube-dark.png`.
Local run logs are preserved under `.tools/view-cube-*.log`. Hosted qualification
uses `full-check.yml` with `os=windows-2022`; routine CI also runs Linux and
Windows E2E/package checks. Artifacts have the requested three-day retention.
Exact full-gate outcomes and hosted run links are reported with the commit at
handoff. macOS GUI qualification remains deferred.

Launch with `gradlew.bat :gui:run` or `./gradlew :gui:run`. Navigation gestures
and keyboard controls are documented in [the desktop workflow](../gui-workflow.md).
