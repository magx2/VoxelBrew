# M3/M7 follow-up — viewport sketch planes

Date: 2026-11-06. Scope: New sketch starts with main-viewport selection of XY,
XZ, YZ or an analytic flat body face. Local plane frames drive rendering,
extrusion, through-all cuts and schema-8 persistence. Existing sketch identity
and bindings survive explicit plane repair. See [workflow and limits](../sketch-planes.md).

## Automated qualification

- `SketchPlaneTest`: real OCCT solids on all origin planes and a translated,
  rotated, offset plane; positive/negative extrusions and through holes; exact
  volume and transformed bounds; save/reopen; attachment movement after an
  upstream thickness edit; broken-reference repair and undo/redo; detached
  analytic face-to-mesh mapping; cylinder-side exclusion; immutable transforms.
- `FaceSupportTest`: coplanar grouping, changed face indices, changed plane
  counts, absent supports and explicit rejection instead of silent fallback.
- `ViewOrientationTest`: CAD-to-JavaFX placement conversion and rigid inverse.
- `WorkbenchUiTest`: native Robot clicks on all three rendered origin planes,
  cancellation without history, signed extrusion, flat-face selection, curved
  rejection, stale-pick cancellation, saved attachment updates, plane repair and
  undo/redo. Existing New sketch flows now explicitly choose XY.
- English and Polish catalogs and action descriptions are covered by `UiTextTest`.

Full root gates use the same source patch in separate native build directories:

```powershell
# Windows; close VoxelBrew before relinking the native bridge.
$env:JAVA_HOME='C:\Program Files\Java\amazon-corretto-21.0.6.7.1-windows-x64-jdk'
$env:GRADLE_USER_HOME='D:\repos\VoxelBrew\.tools\gradle-home'
Set-Location D:\repos\VoxelBrew
.\gradlew.bat check -PnativeJobs=8 --no-daemon
```

```bash
cd /tmp/voxelbrew-m0
export JAVA_HOME=/home/martin/.sdkman/candidates/java/21-tem
export PATH="$JAVA_HOME/bin:$PATH"
bash scripts/with-virtual-desktop.sh ./gradlew check
```

Local evidence is retained under `.tools/plane-*`: source patch and SHA-256,
root logs, per-suite totals and compressed reports/screenshots. The run summary
is `.tools/plane-qualification.json`. Generated test workspaces are cleaned by
the shared test fixtures and script cleanup; reports remain available.

The first Windows native check was blocked by an open VoxelBrew window holding
the DLL. An isolated worktree was prepared while preserving the unsaved document;
the user subsequently authorized force-closing the remaining process, allowing
qualification in the main checkout.
Linux uses its own OCCT build and Xvfb/Mesa with actual JavaFX 3D. This follow-up
does not qualify macOS graphics or general persistent topology naming.
