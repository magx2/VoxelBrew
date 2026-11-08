# Test suites and CI

Each module uses the following source roots. Java packages remain unchanged;
resources for a suite belong alongside its `java` directory under `resources`.

| Task              | Java source root                | Scope                                                                                                  |
| ----------------- | ------------------------------- | ------------------------------------------------------------------------------------------------------ |
| `test`            | `src/test/test/java`            | Isolated validation, document transactions and G-code parsing; no OCCT, slicer or display prerequisite |
| `integrationTest` | `src/test/integrationTest/java` | Real OCCT, persistence, exporters, external plugin loading, real slicer and UI-neutral controllers     |
| `e2eTest`         | `src/test/e2eTest/java`         | CLI workflows across fresh JVMs and JavaFX Robot interactions                                          |

The root tasks aggregate all eight modules, including `import-api` and `export-api`. Suites have separate compilation,
runtime classpaths, XML results and HTML reports. They depend on production code,
not another suite's output. Currently engine owns the unit tests; CLI workflows
are end-to-end tests and GUI controllers are integration tests. An empty suite
in a module is reported by Gradle, not counted as test coverage.

```powershell
.\gradlew.bat test
.\gradlew.bat integrationTest
.\gradlew.bat e2eTest
.\gradlew.bat platformCheck
.\gradlew.bat check
```

Use `./gradlew` on Ubuntu. Java unit tests need Java 21 (and Gradle dependencies).
Root `test` and `check` also run `workflowTest`: release automation tests under
`.github/scripts`, requiring Git and Node.js (CI uses Node 24). These tests use
temporary local repositories and never publish a GitHub release.
Integration tests additionally need the native and slicer bootstraps from the
README, but no display. End-to-end and platform checks require a 3D-capable
desktop; Ubuntu CI uses Xvfb/Mesa and Openbox so native minimize/restore is exercised:

```bash
./gradlew test
env -u DISPLAY -u WAYLAND_DISPLAY ./gradlew integrationTest
bash scripts/with-virtual-desktop.sh ./gradlew check
```

`platformCheck` runs `e2eTest`, packaged-launcher and updater checks, and
`releaseArchiveCheck` for the actual distributable ZIPs. Archive checks repeat
the packaged workflow from paths containing spaces and record the tested ZIP
checksum, runtime identity, native dependency hashes and notices. `e2eTest`
alone does not build the bundled JVM images. GUI Robot and packaged GUI checks
run sequentially to avoid competing for desktop input. `check` remains the full
local gate: formatting, unit, integration, end-to-end and packaged-app checks,
with no task exclusions. `:engine:nativeIntegrationTest` still runs the focused
real-kernel subset from the integration source set.

Reports are under each module's `build/test-results/{test,integrationTest,e2eTest}`
and `build/reports/tests/{test,integrationTest,e2eTest}`. Screenshots remain in
`gui/build/ui-artifacts`, slicer evidence in `export/build/printing-artifacts`,
and packaged-app evidence in each frontend's `build/package-artifacts`.

## Parallel execution

The committed Gradle configuration enables parallel project execution with at most four
workers. Ordinary commands such as `gradlew.bat test` and `./gradlew check` use this
configuration automatically. Override it with `--max-workers=2` for a smaller machine,
or `--no-parallel` to diagnose scheduling problems. This limit covers Gradle workers;
native compilation, slicers and application CPU pools have their own thread budgets.

| Work                                                  | Parallel execution policy                                                                                                                                                         |
| ----------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Tests in different modules                            | Allowed after their build dependencies finish; homes, temporary directories and reports are isolated by module and suite.                                                         |
| Unit, integration and end-to-end suites in one module | Ordered: unit tests before integration, then end-to-end when selected together. Gradle executes one task at a time per project.                                                   |
| Test classes and methods within a suite               | Keep one test JVM and JUnit's sequential default. Suite homes, preferences, JavaFX state and acceptance evidence may be shared.                                                   |
| GUI Robot and packaged GUI checks                     | Sequential: `e2eTest`, `packageCheck`, `updatePackageCheck`, then `releaseArchiveCheck` when selected together. They share desktop input and evidence.                            |
| CLI and GUI packaging                                 | Different frontend projects may overlap. Within each frontend, `packageZip` waits for `packageCheck` to finish using the image; updater and archive checks retain their ordering. |
| Native build and exporter fixture                     | Shared producer tasks run once before their consumers. Consumers may read the completed native runtime and fixture JAR concurrently.                                              |
| Release workflow and cleanup-script tests             | May overlap module tests; they use separate temporary workspaces.                                                                                                                 |
| Separate Gradle invocations in one checkout           | Never run concurrently. Gradle project scheduling does not isolate outputs between builds. Use separate Windows and Ubuntu filesystem checkouts.                                  |

The ordering rules do not add unrequested suites: `test` still runs unit tests only,
and `check` remains the complete gate. Keep formatting that writes sources
(`spotlessApply`) separate from test and acceptance runs.

Temporary files are deleted after successful **and failed** tests. Shared test-only
fixtures in `util/src/test/fixtures` configure JUnit's `@TempDir` factory on the
test classpath (including IDE runs). The factory logs deletion failures at ERROR
without changing the test outcome. Gradle also cleans each suite's isolated
`build/test-tmp/<suite>` and `build/test-home/<suite>` after the worker exits.
Temporary directories stay on the checkout's disk rather than filling the OS
temporary directory. Keep manually created files beneath `@TempDir`.

Packaged launcher, updater and release-archive checks clean their copied runtimes,
downloads and extracted images on exit, including setup/assertion failures. Small
diagnostics and reference files are copied to `package-artifacts/launcher-evidence`
or `package-artifacts/update-evidence` first. Node release tests also use best-effort
cleanup. Failures to delete files are logged with their paths; assertions, launches,
resource shutdown and other actual test failures still fail the check. Reports,
screenshots, intentional acceptance evidence and built distributions are retained.

GitHub Actions splits the complete gate into independent jobs:

- Unit tests, formatting and basic SpotBugs correctness checks run once on Ubuntu,
  without native bootstrapping. See the [code quality policy](code-quality.md).
- Integration tests run once on Ubuntu, headlessly with real OCCT/PrusaSlicer.
- Full platform checks run on Windows and Ubuntu, including packaged apps.
- macOS runs `headlessPlatformCheck` (CLI workflows and packaged CLI) and builds
  both app images. It does not run GUI interactions or claim GUI qualification.
- The final **Full check** requires all jobs and every platform to succeed;
  failures, cancellations and skipped prerequisites cannot produce a green gate.

Routine Windows and Ubuntu platform jobs each use four isolated runners. The
`WorkbenchUiTest` Robot methods are partitioned by a stable method-name hash;
new methods automatically join exactly one shard. Each desktop still runs one
test at a time. Shard one runs the remaining platform checks and uploads app
images; shards two through four run only `:gui:e2eTest`. The CLI development
launcher is built before lengthy CLI qualification tasks when both are requested,
so GUI tests can overlap CLI checks. Evidence artifacts include the shard number. Methods
assigned to another shard appear as skipped in that shard's report; inspect all
four reports for complete coverage. `UiTestShardTest` checks that the partition
covers every current Robot method exactly once with no empty shard.

This trades additional runner minutes for shorter elapsed time. In hosted run
`42313887269`, Windows took 25m04s, with roughly 16m36s spent in GUI end-to-end
tests; the unit and integration jobs took about two minutes each. Sharding targets
that bottleneck rather than removing checks or reducing interaction waits.
The actual speedup must be measured on the next hosted run. Superseded routine
runs on the same ref are cancelled; release workflows retain their own policy.

Local commands, manual full qualification and releases default to the complete
unsharded suite. To reproduce one CI partition explicitly, use
`./gradlew platformCheck -PuiTestShard=1 -PuiTestShardCount=4` (Windows:
`gradlew.bat`). A single partition does not qualify a milestone: run plain
`check` for that gate. Invalid shard numbers fail instead of silently running
an empty selection.

## UI test placement review

Robot scenarios that only need an existing profile use committed `.jcad` files
under `gui/src/test/e2eTest/resources/dev/voxelbrew/gui/documents`. They contain
60 × 40 mm constrained rectangles at Z=0 and Z=12, and a 5 mm plate.
The files were generated manually with `ModelSession`, `Sketches.rectangle`
and `DocumentWriter.save`; test setup never generates them. Regenerate and review
these resources explicitly when the document schema changes.
`PreparedDocuments.copy` loads a classpath resource into each test's `@TempDir`,
then the Robot opens that private copy through the file dialog. Saves cannot
modify the committed inputs or contaminate another scenario. The fixture check
reopens the actual resources and verifies editable profiles and exact plate geometry.
History, extrusion, repair, face attachment, navigation and printing scenarios use
these inputs. Sketch creation, the full reference workflow and initially unsaved
document scenarios still exercise creation through real controls.

The 41 Robot scenarios were reviewed after introducing CI shards. The suite now
contains 39 scenarios. Importance alone is not a reason to remove a regression
check: move it down only when the lower layer can observe the same behavior.

| Coverage                                                                                                             | Decision and replacement                                                                                                                                                                                                                                                                |
| -------------------------------------------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Viewport coordinate basis, units and handedness                                                                      | Moved unchanged from `WorkbenchUiTest` to the headless `ViewOrientationTest` unit suite. No window, Robot or native kernel is needed.                                                                                                                                                   |
| Older-file upgrade, future-file rejection, path/dirty state and byte preservation                                    | Moved the compatibility matrix to `DocumentControllerTest` integration tests with real document I/O. Added both clean and dirty current documents. Kept a short Robot smoke test for file-dialog errors reaching the visible status label without replacing the current design.         |
| Polish About messages and bundled license/notices                                                                    | Message formatting and resource contents are covered by `UiTextTest` unit tests. Removed the separate Polish About scenario and kept translated control wiring in the existing Polish workbench tour. English About tests still exercise legal links, read-only text and both palettes. |
| Window move/resize/minimize, close/save/discard/cancel, first-launch locale and window isolation                     | Retained: native window behavior, per-window Dagger composition and displayed preferences cannot be established by controller tests. `WindowResourcesTest` already checks worker shutdown independently.                                                                                |
| History scrolling/playback, selection, plane picking, curved-face rejection, stale picks and attached-sketch repair  | Retained: these exercise actual rendered targets, selection state and cancellation after asynchronous changes.                                                                                                                                                                          |
| Body rename/material/color, context menus, keyboard focus, modal boundaries, autocomplete and unsaved-file shortcuts | Retained: synthetic controller calls would miss event routing, focus, wrong-target edits and accidental commits.                                                                                                                                                                        |
| Sketch geometry families, constraints, formulas, named parameters/configurations and repair                          | Retained: engine/unit tests cover their math; these scenarios prove that their different tools and editors create and edit the intended entities through real controls.                                                                                                                 |
| Technical rendering, grid drag, view cube, signed extrusion drag and cancellation                                    | Retained: projection, occlusion, picking and gestures need the actual JavaFX scene and input pipeline.                                                                                                                                                                                  |
| Reference-part/export parity, plugin/import dialogs and missing-provider recovery, printing/slicing preview          | Retained: these are the integrated CAD, exchange and printing acceptance paths, including independent CLI comparison.                                                                                                                                                                   |
| About retry/cancellation and updater unsaved-document protection                                                     | Retained: background results, disabled controls, closing/reopening dialogs and modal cancellation are UI behavior, even though transport and installation also have lower-level tests.                                                                                                  |

This is a modest reduction in desktop work, not another large CI speedup. The
macOS packaging job can still determine total run time. No interaction wait or
CAD/export/printing acceptance gate was removed, and local `check` remains the
complete suite.

Native setup and artifact collection live in the reusable `native-check.yml`
workflow. The standard hosted Intel Mac cannot initialize JavaFX 3D. The user
explicitly deferred macOS GUI qualification; the narrower headless task keeps
that scope visible without weakening the full `check` or `platformCheck` tasks.
Run `./gradlew check` on a graphics-capable Mac for eventual GUI qualification.
See [M5 acceptance](acceptance/M5.md) for the observed graphics limitation.

For an explicit repeat of the entire root gate on one hosted platform, dispatch
**Full platform qualification** (`full-check.yml`) and select Windows or Ubuntu.
This manual workflow runs `check` without exclusions; routine push/PR runs retain
the suite split above.
