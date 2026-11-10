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

## Bundled product images follow-up

Added 35 unmodified upstream product images covering 38 of the 62 presets,
including all 15 Bambu Lab presets. The remaining 24 presets retain the schematic
fallback; no unsupported model is relabeled with a different model's photo.
MINI+, MK3S+ and Artisan Premium use their base-model image as documented in the
inventory. Sources, SHA-256 checksums and attribution accompany the resources in
`photo-sources.json`, together with `PHOTO-LICENSE.txt` (AGPL-3.0).

Automated Windows image decoding with System.Drawing succeeded for all 35 files.
Windows `gradlew.bat spotlessApply :engine:test --tests '*PrinterCatalogTest'
:util:test` and Ubuntu `./gradlew --no-daemon --max-workers=2 :engine:test --tests
'*PrinterCatalogTest' :util:test` both passed. Ubuntu used the separate checkout
`/tmp/voxelbrew-printers.gNbIsB`. Logs: `.tools/printer-photo-windows.log` and
`.tools/printer-photo-ubuntu.log`. A1 and MK4S image files were visually inspected.
No GUI gesture or E2E tests were run for this resource-only change.

## WebP custom images follow-up

Custom images now include WebP after PNG/JPG/JPEG and before GIF/BMP. The GUI
uses TwelveMonkeys ImageIO 3.15.0 (pure Java, BSD-3-Clause) for WebP and transfers
ARGB pixels to JavaFX without a native codec. The first frame is displayed;
corrupt, missing or larger-than-16-million-pixel WebP images use the illustration.
The existing 5 MiB discovery limit still applies.

Windows `gradlew.bat spotlessApply :engine:test --tests '*PrinterCatalogTest'
:gui:test --tests '*PrinterImagesTest' :util:test` passed. Ubuntu
`./gradlew --no-daemon --max-workers=2 :engine:test --tests '*PrinterCatalogTest'
:util:test :gui:test --tests '*PrinterImagesTest'` passed in the separate checkout.
Tests decode actual lossy and lossless WebP fixtures, preserve transparency,
handle uppercase extensions and invalid/missing files, and verify discovery
precedence on reload. Logs: `.tools/printer-webp-windows.log` and
`.tools/printer-webp-ubuntu.log`. No GUI gesture/E2E or packaged-app tests were run.

## Selected-printer slicing follow-up

Removed the layout-only gate from the GUI, SliceService and Prusa project
configuration. Selected hardware now drives generic single-tool slicing; vendor
firmware maps to Marlin 2 in Prusa and is accepted by Cura's generic adapter.
Prusa extrusion widths scale with the selected nozzle. The persisted
`genericGcode` field is retained for compatibility but no longer blocks slicing.
Manufacturer startup/calibration/toolchanging and hardware qualification remain
outside this generic process.

Windows passed `gradlew.bat spotlessApply :impl:slicer:integrationTest --tests
'*PrinterPresetSlicingTest' :impl:slicer:test --tests '*CuraSlicerBackendTest'
:gui:e2eTest --tests '*WorkbenchUiTest.printerSelectionPersistsAndUndoRestoresBuildVolume'`.
Real PrusaSlicer produced independently inspected toolpaths for modified generic
hardware, Bambu A1 mini and Prusa XL 5-tool presets. The focused GUI test verifies
Slice remains enabled after selecting A1 mini and adding a body.
Log: `.tools/printer-slicing-windows.log`.

Ubuntu real-slicer integration was attempted but blocked at `:engine:configureOcct`
by missing native prerequisites in the separate checkout. Ubuntu passed
`./gradlew --no-daemon --max-workers=2 :impl:slicer:compileIntegrationTestJava
:impl:slicer:test --tests '*CuraSlicerBackendTest' --console=plain`. Logs:
`.tools/printer-slicing-ubuntu.log` and `.tools/printer-slicing-ubuntu-compile.log`.
No Ubuntu E2E, full milestone gate, real Cura run or hardware test was performed.

## Printer menu and editable default export

Added a top-level Printers menu with export, folder-open and reload actions.
Export creates `custom-` preset copies and same-stem PNGs under the configured
application home's `printers/` directory, preserves existing user files, and
copies photo attribution and license texts. Exported copies reload without
duplicate-ID diagnostics and sort before built-ins.

Windows passed `gradlew.bat spotlessApply :engine:test --tests '*PrinterCatalogTest'
:util:test :gui:e2eTest --tests '*WorkbenchUiTest.printerSelectionPersistsAndUndoRestoresBuildVolume'`.
The catalog test exports all 62 presets, reloads all 124 entries, checks matching
image paths and verifies a second export preserves edited files. The focused GUI
test checks all three top-level menu actions and invokes reload. Folder launching
and the export completion dialog were not separately exercised through gestures.
Ubuntu passed `./gradlew --no-daemon --max-workers=2 :engine:test --tests
'*PrinterCatalogTest' :util:test :gui:compileJava --console=plain` in the separate
checkout. Logs: `.tools/printer-export-windows.log`, `.tools/printer-export-ubuntu.log`.

## Selective printer export

Export now requires an explicit selection from a searchable checkbox list;
nothing is preselected, Export is disabled for an empty selection, and Cancel
writes nothing. Filtering preserves checked choices. The catalog API accepts
selected preset IDs and exports only those JSON/image pairs.

Windows passed `gradlew.bat spotlessApply :engine:test --tests '*PrinterCatalogTest'
:util:test :gui:e2eTest --tests '*WorkbenchUiTest.printerSelectionPersistsAndUndoRestoresBuildVolume'`.
The GUI check covers empty selection, checking a printer, search persistence and
cancellation. The catalog check exports two chosen presets, verifies unselected
JSON/image files remain absent, checks empty-selection behavior, and preserves
existing edited files. Ubuntu passed `./gradlew --no-daemon --max-workers=2
:engine:test --tests '*PrinterCatalogTest' :util:test :gui:compileJava --console=plain`.
Logs: `.tools/printer-export-selection-windows.log` and
`.tools/printer-export-selection-ubuntu.log`. No full E2E or milestone gate run.

## Built-in printer visibility

Added Printers > Show/hide default printers with a searchable visibility checklist.
Preferences persist under `printers.hiddenDefaults` in user configuration. Custom
printers remain visible; hidden presets stay available to export and saved project
snapshots. A project's current printer remains displayed until another is chosen.
The dialog permits hiding every default and reopening it to restore any subset.

Windows passed `gradlew.bat spotlessApply :gui:test --tests '*PrinterVisibilityTest'
:util:test :gui:e2eTest --tests '*WorkbenchUiTest.printerSelectionPersistsAndUndoRestoresBuildVolume'`.
Ubuntu passed `./gradlew --no-daemon --max-workers=2 :gui:test --tests
'*PrinterVisibilityTest' :util:test --console=plain` in the separate checkout.
Unit coverage includes persistence, restoration, preservation of unrelated
preferences and exclusion of custom presets from hidden defaults. The focused
Windows GUI check covers the updated menu and existing export/selection flow;
visibility Save gestures were not separately automated. Logs:
`.tools/printer-visibility-windows.log`, `.tools/printer-visibility-ubuntu.log`.

## Visibility bulk actions

Added Select all, Deselect all and Invert selection to the visibility dialog.
They affect all bundled printers regardless of the search filter; Save commits
the resulting preferences and Cancel discards changes.

Windows passed `gradlew.bat spotlessApply :gui:test --tests '*PrinterVisibilityTest'
:util:test :gui:e2eTest --tests '*WorkbenchUiTest.printerSelectionPersistsAndUndoRestoresBuildVolume'`.
The focused GUI test exercises all three buttons with an active search filter,
checks inversion of a mixed selection, and verifies that hiding all remains
saveable. Ubuntu passed `./gradlew --no-daemon --max-workers=2 :gui:test --tests
'*PrinterVisibilityTest' :util:test --console=plain`. Logs:
`.tools/printer-visibility-bulk-windows.log`, `.tools/printer-visibility-bulk-ubuntu.log`.
