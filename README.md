# VoxelBrew CAD

Desktop parametric CAD for mechanical parts and FDM preparation. Project domain:
**voxelbrew.dev**, Java/Gradle namespace: **dev.voxelbrew**.

**M0 through M3** provide a real Java → JNI → Open CASCADE Technology (OCCT) kernel
and an editable headless CAD workflow. `engine` owns modeling, history and
the versioned Protobuf `.jcad` schema; `cli` exposes the same engine operations;
`gui` provides the JavaFX CAD workbench. M2 adds tessellation, binary STL,
portable 3MF and external exporter plugins; M3 adds interactive sketching,
extrusion, picking, editable history and export. The shared subproject
`util`, owns translations, user configuration, logging, application updates and
generic CPU workers. It has no CAD or frontend dependencies; engine and both
frontends depend on it. See [shared utilities and configuration](docs/shared-utilities.md).
The `import` and `export` projects own document/G-code reading, document writing,
STL/3MF exporters and the slicer file pipeline. The `import-api` and `export-api`
projects publish provider contracts so external JARs can add formats without depending
on implementation projects. Both APIs depend on engine; engine has no production
dependency on them. There are eight first-party projects. See
[file import/export boundaries and API migration](docs/file-io.md).
CLI commands and each desktop window are assembled by Dagger with constructor
injection, scoped services and explicit resource ownership. See
[dependency injection and application composition](docs/dependency-injection.md).
Lombok generates routine constructors/accessors and SLF4J loggers; see
[Lombok conventions and logging](docs/lombok.md).
M4 adds editable splitting, build placement, persisted
PLA/PETG process settings, supports and real PrusaSlicer jobs. See the
[printing workflow](docs/printing-workflow.md) and [qualification record](docs/acceptance/M4.md).
The complete backlog is in [the implementation plan](docs/implementation-plan.md).
M4 is complete for the first-printable scope; advanced support capabilities and
hardware validation remain tracked separately in its acceptance record.
M5 is complete for Windows/Ubuntu desktop and macOS Intel CLI, with bundled-JVM
app images and checks of both original images and extracted distribution ZIPs.
macOS GUI qualification remains explicitly deferred. See
[packaging and platform qualification](docs/packaging.md).
M6 is complete for persistent custom feature providers, with a consumer-built
spacer plugin, shared CLI/GUI parameter editing, provider-failure isolation and
lossless missing-plugin recovery. See [feature plugins](docs/feature-plugins.md)
and [M6 acceptance](docs/acceptance/M6.md).
M7 adds a 2D sketch constraint workspace: lines, circles, arcs, cubic splines,
regular polygons, driving dimensions and geometric constraints. See
[sketch constraints](docs/sketch-constraints.md).
M8 adds the remaining Fusion-style 2D geometry families and creation variants:
ellipses, conics, slots, fit/control splines, text and text on a path. See
[sketch geometry types and the comparison](docs/sketch-types.md).
M9 adds named parameters, unit-aware formulas, and parameter configurations in
one design. Create them from the workbench or a sketch, then use their names in
driving dimensions and extrusion distances. See [parameters and configurations](docs/parameters.md).
M10 uses JSR 385 quantities and units throughout parameter evaluation, CAD
measurement boundaries and print settings. See [units and typed APIs](docs/units.md).
M11 parallelizes independent body meshing, larger sketch Jacobians and print
preflight using a bounded CPU pool. See [computation and performance](docs/computation.md).
M12 adds release branches, versioned native application downloads, checksums and
release rehearsals. See [release steps and recovery](docs/releases.md).
M13 adds explicit CLI `update` / `update check` commands and a GUI **Updates…**
dialog, with verified app-image replacement and recovery backups. See
[updating VoxelBrew](docs/updating.md).

## Prerequisites and locked dependencies

- JDK **21**, with `JAVA_HOME` set to a full JDK (including JNI headers).
- Dagger **2.60.1**, pinned in `gradle/di.gradle.kts`; its compiler is build-only.
- Lombok **1.18.48** (compile-only) and SLF4J **2.0.20**, pinned in `gradle/lombok.gradle.kts`.
- Gson **2.15.0**, used for public update metadata; updates need no `gh` or GitHub login.
- Units of Measurement API **2.2** and Indriya **2.2.4**, resolved by Gradle.
- Gradle **9.1.0**, through the committed, SHA-256-verified wrapper.
- CMake **3.31.6** and OCCT **7.9.0**, downloaded and checksum-verified by the bootstrap.
- Windows x86-64: Visual Studio 2019 C++ tools **14.29.30133**, v142. The bootstrap
  supplies Windows SDK **10.0.22621.0** from Microsoft NuGet **10.0.22621.1** locally.
  With VS 2022 and the same v142 tools, pass `-PvsGenerator="Visual Studio 17 2022"`.
- Ubuntu 24.04 x86-64: **gcc-13, g++-13, make, curl, tar**, and JDK 21.
  Install with `sudo apt-get install openjdk-21-jdk gcc-13 g++-13 make curl` if absent.

All native archive hashes and compiler versions are in
[`scripts/native-versions.properties`](scripts/native-versions.properties).
Bootstrapping writes only to the ignored `.tools` directory; it does not modify
Visual Studio or the system SDK. The first kernel build takes several minutes.
Native compilation defaults to four workers; use `-PnativeJobs=8` if appropriate.
Gradle and Maven dependency downloads require network access on the first run.

## Formatting

[Spotless](https://github.com/diffplug/spotless/tree/main/plugin-gradle) runs from
the root build and is included in `check` and CI. Format the whole project with
`./gradlew spotlessApply`, or verify it with `./gradlew spotlessCheck` (Windows:
`.\gradlew.bat spotlessApply` / `.\gradlew.bat spotlessCheck`). Formatting does
not require the native bootstrap or a display.

The pinned formatters cover Java (including the independent exporter fixture),
Gradle Kotlin scripts, native C++, Protobuf, CSS, Markdown, workflow YAML and release JavaScript. Other owned
text files and scripts receive consistent whitespace and
LF line endings. Java uses Google Java Format's four-space AOSP style; Kotlin
uses ktfmt's Kotlin style. Prettier requires Node.js 18 or later with npm on
`PATH`; CI supplies Node.js 24. Python 3 with `venv` is also required (`python`
on Windows, `python3` on Ubuntu; install `python3-venv` on Ubuntu if absent).
Gradle downloads the pinned clang-format into a project-local `.tools/formatters`
virtual environment and resolves the other formatters on first use. Generated output,
Gradle wrapper files, acceptance artifacts, third-party
licenses and local agent configuration are excluded.

`./gradlew basicChecks` also checks high-confidence correctness bugs with SpotBugs.
CI uploads Sonar analysis and blocks on new critical/blocker bugs or vulnerabilities;
coverage, duplication and style findings remain advisory. See
[code quality policy and Sonar setup](docs/code-quality.md) for the required repository
variables/secret, scope and reports. Local `check` does not require a Sonar account.

## Windows: build, integration test, demo

From PowerShell at the repository root:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\bootstrap-native.ps1
.\gradlew.bat :engine:nativeIntegrationTest
.\gradlew.bat check
.\gradlew.bat :cli:run --args="native-demo"
```

## Ubuntu / Ubuntu WSL: build, integration test, demo

Use an Ubuntu filesystem checkout for fast compilation, e.g. `~/src/VoxelBrew`.
Do not run Windows and Linux builds simultaneously in the same checkout: native
binaries and Gradle intermediate files are platform specific.

```bash
bash scripts/bootstrap-native.sh
./gradlew :engine:nativeIntegrationTest
./gradlew check
./gradlew :cli:run --args='native-demo'
```

From Windows, an existing Ubuntu checkout can be tested with:

```powershell
wsl -d Ubuntu --exec bash -lc 'cd ~/src/VoxelBrew && ./gradlew check'
```

The dedicated native test **requires** OCCT; it never substitutes a mock or skips
when the library is unavailable. `check` runs every subproject's checks without
exclusions and includes the same tests through `engine:integrationTest`.

Tests are separated into `src/test/test`, `src/test/integrationTest` and
`src/test/e2eTest` in each module. `test` runs unit tests without native tools;
`integrationTest` runs real-kernel/service tests; `e2eTest` runs CLI and GUI
workflows. CI runs unit/integration suites once on Ubuntu and `platformCheck`
(end-to-end plus packaged apps) on Windows and Ubuntu. macOS runs
`headlessPlatformCheck` for the CLI and builds both app images; its GUI
qualification is deferred. The final CI gate requires every configured suite
and platform. See [test commands and layout](docs/testing.md).

## Reference part and numerical contract

To create, save, reopen, edit and roll back the reference part through public CLI
commands, build the local CLI distribution and run the script (choose a new file
path each time; the script deliberately refuses to overwrite an existing file):

```powershell
.\gradlew.bat :cli:installDist
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\reference-part.ps1 -Destination build\demo\my-part.jcad
.\cli\build\install\cli\bin\cli.bat inspect build\demo\my-part.jcad --point 10,10,4
```

```bash
./gradlew :cli:installDist
bash scripts/reference-part.sh build/demo/my-part.jcad
cli/build/install/cli/bin/cli inspect build/demo/my-part.jcad --point 10,10,4
```

This development distribution includes the matching native libraries and requires
an installed Java 21 runtime. It is not an M5 installer or bundled-JVM release.
See [the editable workflow](docs/editable-workflow.md) for all commands, history
semantics, failure recovery and the persistence contract.

The native experiment builds a dimensioned 60 × 40 mm rectangular wire at (0, 0)
on XY, creates a planar face, extrudes it along Z, and subtracts an analytic
cylinder of diameter 4 mm at (10, 10). It tests both 5 mm and 8 mm thickness.
The cut tool spans the current body's bounds with 1 mm overrun at each end.

For 8 mm thickness, the analytic volume is **19099.469035085127 mm³**,
`(60 × 40 − 4π) × 8`. The required bounds are `(0, 0, 0)` to `(60, 40, 8)` mm.
Exact-solid volume tolerance is **0.01 mm³** and bounds tolerance is **0.001 mm**.
Point classification uses OCCT's **1e-7 mm** tolerance. The tests sample the hole
and material at three heights; measurements use B-rep integration, not a mesh.
Mesh approximation tolerance belongs to M2 and is not used for this gate.

## Native ownership and loading

Every returned `Solid` is an independent, immutable owning handle and implements
`AutoCloseable`. Always use try-with-resources. Closing the source does not
invalidate a cut result. Close is idempotent; use after close fails. The JNI
registry stores opaque monotonic IDs backed by shared ownership, never raw
addresses in the public Java API. Registry access is locked and Java operations
serialize against close. Tests verify live handles return to the initial count.

The bridge catches OCCT and C++ failures and returns an operation-specific
`GeometryException`. Java and native validation reject invalid/nonfinite input.
Every produced result is validated as exactly one valid solid before publication.

`engine:buildNative` builds/installs the minimal OCCT dependency closure and
places the JNI library and shared OCCT libraries in `engine/build/native/runtime`.
Gradle test/demo tasks supply `-Dvoxelbrew.native.path` automatically. For direct
Java use, set that property to the **absolute** runtime directory. On Linux the
bridge uses `$ORIGIN` for adjacent shared dependencies; on Windows the loader
loads OCCT dependencies in order from that exact directory. No system OCCT
installation, display server or GPU is required for the headless tests.

## Evidence and remaining gates

See [M0 acceptance evidence](docs/acceptance/M0.md) and
[M1 acceptance evidence](docs/acceptance/M1.md) for actual commands, toolchain
versions, observed values, failures investigated and platform results. Test XML
and HTML are generated under `engine/build/test-results` and
`engine/build/reports/tests`. CI exercises native builds on Ubuntu and Windows;
CI configuration is not itself proof of a successful hosted run.

See also [M2 export evidence](docs/acceptance/M2.md) and
[M3 desktop evidence](docs/acceptance/M3.md) and the
[M4 manufacturing qualification](docs/acceptance/M4.md). Subsequent milestones cover
persistently named generated-face references. General sketch constraints are
implemented in M7. Feature plugin
qualification is tracked in [M6 evidence](docs/acceptance/M6.md). Packaged platform results are tracked in
[M5 acceptance](docs/acceptance/M5.md); physical printer checks remain unqualified.

## Desktop CAD workbench

Use Java 21 and the matching native bootstrap above. JavaFX **21.0.13** is
resolved for the build platform automatically.

```powershell
.\gradlew.bat :gui:run
.\gradlew.bat :gui:installDist
.\gui\build\install\gui\bin\gui.bat --open fixtures\reference-8mm.jcad
```

```bash
./gradlew :gui:run
./gradlew :gui:installDist
gui/build/install/gui/bin/gui --open fixtures/reference-8mm.jcad
```

Ubuntu needs GTK 3 and a 3D-capable display (WSLg locally). Linux CI runs the
actual Robot interaction tests using Xvfb/Mesa:

```bash
sudo apt-get install xvfb xauth libgtk-3-0t64 libgl1-mesa-dri
xvfb-run -a -s '-screen 0 1600x1000x24' env LIBGL_ALWAYS_SOFTWARE=1 ./gradlew check
```

The installed development distribution includes JavaFX and matching OCCT
libraries and requires Java 21. It is platform specific, without an installer
or bundled JVM. Both launchers accept `--plugin-dir`. See the
[desktop workflow and shortcuts](docs/gui-workflow.md) for the reference demo,
graphics diagnostics and interaction test commands. GUI Robot tests temporarily
bring their own windows to the foreground; avoid interacting with the desktop
during those checks.

The IDE-style menu bar keeps **New / Open / Save / Save As / Export** under
**File**, appearance under **View**, and language/updates under **Settings**.
The modeling toolbar stays focused on sketch and solid tools. Hover over buttons
and menu actions for English or Polish descriptions and selection requirements.

## Codex agents

Project agents cover the CAD engine, CLI, JavaFX desktop, and Windows/Ubuntu
validation. See [agent roles and usage](docs/codex-agents.md) and
[contributor instructions](AGENTS.md) for task ownership and milestone checks.

## Export and plugins

After `:cli:installDist`, run `cli exporters` and
`cli export fixtures/reference-8mm.jcad --format 3mf --output plate.3mf`
using the installed launcher above. See [the export API and commands](docs/export-workflow.md)
and [M2 acceptance evidence](docs/acceptance/M2.md). Exporters require a complete,
successfully rebuilt document; the output is published only after success.

## Licenses

VoxelBrew retains the repository's **AGPL-3.0** license. OCCT is dynamically linked
under LGPL-2.1 with the OCCT exception. See [third-party notices](THIRD_PARTY_NOTICES.md).
