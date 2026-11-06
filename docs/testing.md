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
desktop; Ubuntu CI uses Xvfb/Mesa:

```bash
./gradlew test
env -u DISPLAY -u WAYLAND_DISPLAY ./gradlew integrationTest
xvfb-run -a -s '-screen 0 1600x1000x24' env LIBGL_ALWAYS_SOFTWARE=1 ./gradlew check
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

GitHub Actions splits the complete gate into independent jobs:

- Unit tests, formatting and basic SpotBugs correctness checks run once on Ubuntu,
  without native bootstrapping. See the [code quality policy](code-quality.md).
- Integration tests run once on Ubuntu, headlessly with real OCCT/PrusaSlicer.
- Full platform checks run on Windows and Ubuntu, including packaged apps.
- macOS runs `headlessPlatformCheck` (CLI workflows and packaged CLI) and builds
  both app images. It does not run GUI interactions or claim GUI qualification.
- The final **Full check** requires all jobs and every platform to succeed;
  failures, cancellations and skipped prerequisites cannot produce a green gate.

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
