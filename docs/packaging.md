# Bundled Java 21 app images

`./gradlew :cli:appImage :gui:appImage` (Windows: `gradlew.bat`) builds two
independent host-platform app images with native launchers, Java 21, matching
OCCT libraries and notices. A full JDK with `jmods` is required to build them;
Ubuntu's `openjdk-21-jdk-headless` supplies it. Users need no Java installation.
Development `installDist` tasks remain available and require external Java.

GUI windows and native launchers share the [VoxelBrew application icon](app-icon.md).
The host package uses ICO on Windows, PNG on Linux and ICNS on macOS.

Build distributable ZIPs with `:cli:packageZip :gui:packageZip`. Extract the whole
image; keep its adjacent runtime, app libraries, `native` and notices directories.
Unix executable permissions are retained. Archives are in each frontend's
`build/distributions`. CI uploads both archives and their acceptance evidence.

| Platform / CPU      | CLI launcher under `cli/build/app-image`       | GUI launcher under `gui/build/app-image` |
| ------------------- | ---------------------------------------------- | ---------------------------------------- |
| Windows x86-64      | `VoxelBrewCLI/VoxelBrewCLI.exe`                | `VoxelBrew/VoxelBrew.exe`                |
| Ubuntu 24.04 x86-64 | `VoxelBrewCLI/bin/VoxelBrewCLI`                | `VoxelBrew/bin/VoxelBrew`                |
| macOS 15 x86-64     | `VoxelBrewCLI.app/Contents/MacOS/VoxelBrewCLI` | `VoxelBrew.app/Contents/MacOS/VoxelBrew` |

The tested-platform status is in [M5 evidence](acceptance/M5.md). macOS arm64
dependency selection exists but is not qualified. Other Linux distributions,
Unix variants and architectures require separate qualification. These app images
also feed the GUI installers below. Local packaging is unsigned and not notarized.

## GUI installers

After the native bootstrap, run `.\gradlew.bat :gui:packageInstaller` on Windows
or `./gradlew :gui:packageInstaller` on macOS. The task builds its own app-image
prerequisite. Windows needs Inno Setup 6 (`ISCC.exe` at its normal installation
path or `INNO_SETUP_ISCC` set to the executable). macOS needs `hdiutil`.

Windows produces `gui/build/distributions/VoxelBrew-<version>-windows-x64-Setup.exe`.
It installs per user, adds a Start menu entry and an uninstaller. macOS Intel
produces `gui/build/distributions/VoxelBrew-<version>-macos-x64.dmg` with the
existing `.app` and an Applications shortcut. Both preserve the complete bundled
runtime and native libraries. Users need no installed Java. The filename uses
the project version; Windows Setup version metadata drops `-SNAPSHOT`
(for example `0.1.0-SNAPSHOT` maps to `0.1.0`).

Local installers are unsigned; the DMG is not notarized. macOS GUI release
publication requires an accelerated Intel Mac runner to install the DMG, launch
the installed app through Launch Services, and pass the JavaFX render-and-pick
smoke test. That runner must carry the `voxelbrew-macos-intel-gui` label.
JDK 21's Mac bundler requires a positive first version component, so macOS app
metadata uses `(major + 1).minor.patch` (for example product `0.1.0` uses
bundle number `1.1.0`). Product versions, CLI output and archive names use
`projectVersion` unchanged. Snapshot suffixes are omitted from native metadata.
Archives use stable `windows-x64`, `linux-x64` and `macos-x64` platform names.
The [release procedure](releases.md) publishes qualified GUI/CLI downloads and
installers with checksums. macOS GUI publication waits for its installed-app gate.

Examples after extracting the appropriate archive:

```text
VoxelBrewCLI inspect reference.vxb --point 10,10,4
VoxelBrewCLI edit reference.vxb --feature thickness --field distance --value 8
VoxelBrewCLI export reference.vxb --format 3mf --output reference.3mf
VoxelBrew --open reference.vxb --plugin-dir "trusted plugins"
```

Both frontends default to `~/.voxelbrew/plugins`; `--plugin-dir` overrides it.
Plugins execute trusted code in-process. The CLI stays headless, including mesh
preflight: it does not load JavaFX and requires no display. The GUI needs a 3D
graphics driver; Ubuntu needs GTK 3, WSLg or an X11 display. Linux CI exercises
Xvfb/Mesa. If 3D is unavailable the launcher reports the graphics prerequisites.

PrusaSlicer remains an external installation. Use `scripts/bootstrap-slicer.ps1`,
`bootstrap-slicer.sh` (Ubuntu) or `bootstrap-slicer-macos.sh`. Set
`VOXELBREW_SLICER` to its executable or choose the executable in the Print tab.
The macOS path is `.tools/PrusaSlicer-2.7.2/PrusaSlicer.app/Contents/MacOS/PrusaSlicer`.
The supported version remains 2.7.2; the backend probes it before slicing.

`check` includes `:cli:packageCheck`, `:gui:packageCheck` and both frontends'
`releaseArchiveCheck` tasks. The latter extract the actual ZIPs into paths with
spaces and repeat the launcher checks, including Unix executable permissions.
`platformCheck` runs the same archive gates; `headlessPlatformCheck` qualifies
only the CLI archive on macOS. The release workflow reuses these checks.

Their subprocesses have no `JAVA_HOME`, Java on `PATH`, or external native-library
search overrides; the CLI also has no display variables. They verify the bundled
Java 21 version/architecture, installation metadata, matching native-library
hashes, and project/third-party/runtime notices. Archive evidence records the ZIP
SHA-256 and size in `build/package-artifacts/archive/archive-result.json`.
They exercise fresh-process edit/reopen, independent STL/3MF inspection and an
external exporter from a path containing spaces. The packaged GUI's explicit
`--smoke-test --smoke-output screenshot.png` command builds an unsaved reference
part, renders it, performs a Robot mouse pick, writes a screenshot/runtime record
and exits. It cannot be combined with `--open`.

Implementation references: [JDK 21 jpackage](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html)
and [hosted macOS images](https://github.com/actions/runner-images).
