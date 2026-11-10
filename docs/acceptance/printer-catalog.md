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
