# GUI installer acceptance

The installer increment extends the M5 jpackage GUI image. Windows and macOS
installers retain its native launcher, bundled Java 21 runtime, OCCT libraries,
application JARs, icons, resources and notices. Release publication now requires
installer artifacts and platform provenance; macOS requires an accelerated Intel
runner labeled `voxelbrew-macos-intel-gui` for installed-app qualification.

## Local Windows evidence

- `:gui:tasks --all --offline` lists `packageInstaller`.
- `:gui:packageInstaller --offline` built `:gui:appImage`, staged
  `VoxelBrew.exe`, `app/` and `runtime/`, and generated the Inno specification.
  It then failed with the expected setup message because this machine has no
  Inno Setup 6 `ISCC.exe`. No Setup.exe was produced or installed locally.
- `spotlessApply --offline` passed. `actionlint` passed for the changed release
  workflows. All 13 release JavaScript tests passed.
- The full root `check --offline` reached native and GUI E2E tests. The sandbox
  desktop failed repeated Robot mouse-press assertions in `WorkbenchUiTest` at
  line 3930; the run was stopped after the failure pattern was established. It
  is not a passing root gate.

## Pending qualification

- Run the full Windows and Ubuntu root checks on suitable hosts and record the
  exact results. Only native-host E2E coverage is applicable.
- On Windows with Inno Setup 6, build from a clean GUI output, install Setup.exe,
  launch from the Start menu, pass the resource-backed render-and-pick smoke
  test, and uninstall. Repeat after an installer input changes.
- On the qualified macOS Intel runner, build the DMG from a clean GUI output,
  install and launch the app through Launch Services, pass render-and-pick, and
  repeat after an installer input changes. Verify the app runs without a
  user-installed JDK. No macOS installer or GUI launch was tested locally.

No installer release or milestone completion is claimed until those checks pass.
