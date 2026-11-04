# VoxelBrew CAD

Desktop parametric CAD for mechanical parts and FDM preparation. Project domain:
**voxelbrew.dev**, Java/Gradle namespace: **dev.voxelbrew**.

The first increment, **M0**, proves a real Java → JNI → Open CASCADE Technology
(OCCT) geometry path. `engine` owns the kernel; `cli` runs the headless reference
demo; `gui` is reserved for the later JavaFX milestone. There are exactly these
three first-party subprojects. No persistence, GUI, export or slicing is claimed
by M0. The complete backlog is in [the implementation plan](docs/implementation-plan.md).

## Prerequisites and locked dependencies

* JDK **21**, with `JAVA_HOME` set to a full JDK (including JNI headers).
* Gradle **9.1.0**, through the committed, SHA-256-verified wrapper.
* CMake **3.31.6** and OCCT **7.9.0**, downloaded and checksum-verified by the bootstrap.
* Windows x86-64: Visual Studio 2019 C++ tools **14.29.30133**, v142. The bootstrap
  supplies Windows SDK **10.0.22621.0** from Microsoft NuGet **10.0.22621.1** locally.
  With VS 2022 and the same v142 tools, pass `-PvsGenerator="Visual Studio 17 2022"`.
* Ubuntu 24.04 x86-64: **gcc-13, g++-13, make, curl, tar**, and JDK 21.
  Install with `sudo apt-get install openjdk-21-jdk gcc-13 g++-13 make curl` if absent.

All native archive hashes and compiler versions are in
[`scripts/native-versions.properties`](scripts/native-versions.properties).
Bootstrapping writes only to the ignored `.tools` directory; it does not modify
Visual Studio or the system SDK. The first kernel build takes several minutes.
Native compilation defaults to four workers; use `-PnativeJobs=8` if appropriate.
Gradle and Maven dependency downloads require network access on the first run.

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
exclusions and includes the same tests through `engine:test`.

## Reference part and numerical contract

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

See [M0 acceptance evidence](docs/acceptance/M0.md) for actual commands, toolchain
versions, observed values, failures investigated and platform results. Test XML
and HTML are generated under `engine/build/test-results` and
`engine/build/reports/tests`. CI exercises native builds on Ubuntu and Windows;
CI configuration is not itself proof of a successful hosted run.

The subsequent milestones cover editable documents/CLI, mesh export/plugins,
JavaFX interaction, split bodies/slicing/supports, packaging, then persistent
feature plugins. macOS, packaged applications, renderer/picking, external
slicers, and physical printer checks remain unqualified until their own gates.

## Licenses

VoxelBrew retains the repository's **AGPL-3.0** license. OCCT is dynamically linked
under LGPL-2.1 with the OCCT exception. See [third-party notices](THIRD_PARTY_NOTICES.md).
