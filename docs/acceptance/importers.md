# Import providers and editable JSON

Date: 2026-11-09. Follow-up to the accepted M1/M3 document workflow; this does
not declare a new milestone complete.

Importer API version 2 mirrors the export interaction model: provider construction,
typed settings dialogs, a frontend host and asynchronous completion. Built-ins read
VXB (including legacy JCAD), strict ProtoJSON and G-code. File > Import selects a
document provider, then its source/settings. Successful document imports are unsaved;
cancellation and errors preserve the current design and source file. Obsolete stream
SPI classes and service registrations were removed; independent providers were migrated.

## Automated checks

Windows, repository root, Java 21 and the committed Gradle wrapper:

```powershell
$env:PATH = 'C:\Users\marti\AppData\Local\Programs\Python\Python311;C:\Program Files\nodejs;' + $env:PATH
$env:GRADLE_USER_HOME = "$pwd/.tools/gradle-home"
.\gradlew.bat spotlessApply --offline --console=plain
.\gradlew.bat :api:import:test :import:test :cli:test :gui:test :import:integrationTest :gui:integrationTest --offline --console=plain
.\gradlew.bat :cli:e2eTest --tests dev.voxelbrew.cli.WorkflowTest.externalImporterListsProvidersAndPublishesEditableDocumentsAcrossProcesses --offline --console=plain
.\gradlew.bat :gui:e2eTest --tests dev.voxelbrew.gui.WorkbenchUiTest.externalImportDialogUsesOptionsAndProtectsSourceInBothPalettes --tests dev.voxelbrew.gui.WorkbenchUiTest.nativeAndJsonImportsOpenUnsavedEditableDesignsAndCancelWithoutChanges --offline --no-daemon --console=plain
```

Results: 87 unit/integration tests and three targeted E2E tests pass. The CLI E2E
was initially selected alongside the GUI tests in one invocation and passed there.
The first GUI run did not deliver native clicks to the File/View menus. A fresh
user-session process (`--no-daemon`) passed both tests.
Earlier compilation issues were fixed; an intermediate GUI unit failure during
concurrent plugin-test changes disappeared after that separate change was integrated.

Coverage includes complete binary/JSON definition round trips, unchanged save metadata,
malformed/future-schema/unknown-field JSON rejection, bounded reads, cancellation,
worker rejection, source/overwrite protection, API-only plugin consumption and failure
isolation. Real OCCT checks the external plate at 76800 mm^3. Windows Robot imports
both native and JSON versions of the editable 5 mm plate, checks 12000 mm^3, cancels
source dialogs and saves independent copies. The plugin dialog is tested in both palettes.

The same 87 unit/integration tests pass in the separate Ubuntu filesystem snapshot
`/tmp/voxelbrew-import.OezvMI`:

```bash
./gradlew :api:import:test :import:test :cli:test :gui:test :import:integrationTest :gui:integrationTest -x :engine:buildNative --offline --console=plain
```

The snapshot includes the tested integrated sources. Linux native source files were
compared byte-for-byte with `/tmp/voxelbrew-export-api.nl9lmN/engine/native` before
copying that checkout's real Linux OCCT runtime into the new checkout. No Windows
native outputs were shared. Native rebuilding was excluded because native code did
not change; tests still exercised the actual Linux native library.

Logs: `.tools/import-format.log`, `.tools/import-tests.log`,
`.tools/import-linux-tests.log`, `.tools/import-e2e.log`, `.tools/import-gui-e2e.log`.
API dependency checks and focused SpotBugs checks also pass:

```powershell
.\gradlew.bat verifyApiDependencies :api:import:spotbugsMain :import:spotbugsMain :cli:spotbugsMain :gui:spotbugsMain --offline --console=plain
```

Quality log: `.tools/import-quality.log`.
JUnit XML/HTML: each selected module's `build/test-results/<suite>` and
`build/reports/tests/<suite>`; equivalent paths exist in the Ubuntu snapshot.

Full root checks and full E2E suites were not run for this focused follow-up. No
Linux/macOS E2E, installer, manual usability or printer-hardware qualification is claimed.
