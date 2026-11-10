# Printer catalog follow-up

M4 follow-up, 2026-11-10; no new milestone gate or physical-printer qualification.

Implemented 62 bundled hardware presets with original schematic images, custom
JSON discovery under `~/.voxelbrew/printers`, a searchable Slicer selector, and
portable printer snapshots in print-setup version 4. Versions 1?3 remain readable.
All plates share the selected printer. Vendor presets are layout/mesh-export only;
no vendor-specific G-code compatibility is claimed. See [inventory](../printer-catalog.md)
and [custom preset instructions](../printing-workflow.md#printer-presets).

## Windows automated evidence

```powershell
.\gradlew.bat spotlessApply :engine:test --tests '*PrinterCatalogTest' --tests '*PrintPlatesTest' :cli:test :util:test :gui:e2eTest --tests '*WorkbenchUiTest.printerSelectionPersistsAndUndoRestoresBuildVolume' --console=plain
.\gradlew.bat :gui:e2eTest --tests '*WorkbenchUiTest.printerSelectionPersistsAndUndoRestoresBuildVolume' :impl:slicer:integrationTest --tests '*PrinterPresetSlicingTest' :gui:test --console=plain
```

Passed with Java 21, real OCCT 7.9.0 and PrusaSlicer 2.7.2. Tests cover catalog
loading and image resources, malformed/conflicting custom files, snapshot retention
through plate operations and binary/JSON persistence, legacy setup handling,
CLI listing/selection, GUI selection/undo/redo/reopen, real slicing with changed
build dimensions, and preservation of an existing job when vendor slicing is rejected.
The installed CLI `cli.bat printers` also successfully loaded the bundled catalog.

An initial GUI run exposed missing tooltip translations. Visual inspection then
exposed an obsolete generic heading and a blank combo-box caption after reopen.
Both were fixed; the final focused Robot run passed and its Slicer screenshot was
visually inspected. Screenshot: `gui/build/ui-artifacts/printer-selector.png`.
Logs: `.tools/printer-final-windows.log`, `.tools/printer-ui.log`,
`.tools/printer-ui-final.log`, `.tools/printer-final-checks.log`,
`.tools/printer-installed-list.log`.
JUnit reports are in the affected modules' `build/reports/tests` directories.

## Ubuntu evidence

The same source snapshot was unpacked into a separate Linux filesystem checkout;
no Windows native binaries or build output were copied. The focused checks passed:

```bash
./gradlew --no-daemon --max-workers=2 :engine:test --tests '*PrinterCatalogTest' --tests '*PrintPlatesTest' :gui:test :cli:test :util:test :impl:slicer:compileIntegrationTestJava --console=plain
```

Checkout/log details are recorded in `.tools/printer-ubuntu-checkout.txt` and
`.tools/printer-ubuntu.log`. Linux coverage is unit tests and compilation, not E2E
or real-slicer execution.

## Coverage boundaries

Only the focused Windows GUI E2E test was run. The full E2E suite, full root
milestone check, Linux/macOS E2E, installers and physical printing were not run.
Custom-file reload and image fallback have automated service coverage; the
Open folder gesture and custom-file editing were not separately Robot-tested.

## Custom image naming follow-up

Custom image discovery now matches the JSON filename stem and ignores its `image`
field. Format precedence is PNG, JPG, JPEG, GIF, BMP, with case-insensitive
extensions. Bundled shared images are unchanged. Focused catalog tests cover
precedence on reload, multi-dot filenames, uppercase extensions, printer-ID and
legacy-field independence, unsupported formats and oversized candidates.

Validation commands: Windows `gradlew.bat spotlessApply :engine:test --tests
'*PrinterCatalogTest' :util:test`; Ubuntu `./gradlew --no-daemon --max-workers=2
:engine:test --tests '*PrinterCatalogTest' :util:test` in the separate Linux checkout.
Logs: `.tools/printer-image-windows.log` and `.tools/printer-image-ubuntu.log`.
No E2E run is needed for this filesystem-discovery change; renderer code is unchanged.
