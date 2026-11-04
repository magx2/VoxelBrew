# Bundled Java 21 app images

`./gradlew :cli:appImage :gui:appImage` (Windows: `gradlew.bat`) builds two
independent host-platform app images with native launchers, Java 21, matching
OCCT libraries and notices. A full JDK with `jmods` is required to build them;
Ubuntu's `openjdk-21-jdk-headless` supplies it. Users need no Java installation.
Development `installDist` tasks remain available and require external Java.

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
Unix variants and architectures require separate qualification. These are app
images, without installer integration or signing/notarization.
JDK 21's Mac bundler requires a positive first version component, so macOS app
metadata uses bundle build number `1.0.0`. The product and archive version remains
`0.1.0-SNAPSHOT`; this does not designate a stable 1.0 product release.

Examples after extracting the appropriate archive:

```text
VoxelBrewCLI inspect reference.jcad --point 10,10,4
VoxelBrewCLI edit reference.jcad --feature thickness --field distance --value 8
VoxelBrewCLI export reference.jcad --format 3mf --output reference.3mf
VoxelBrew --open reference.jcad --plugin-dir "trusted plugins"
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

`check` includes `:cli:packageCheck` and `:gui:packageCheck`. Their subprocesses
have no `JAVA_HOME` or Java on `PATH`; the CLI also has no display variables.
They exercise fresh-process edit/reopen, independent STL/3MF inspection and an
external exporter from a path containing spaces. The packaged GUI's explicit
`--smoke-test --smoke-output screenshot.png` command builds an unsaved reference
part, renders it, performs a Robot mouse pick, writes a screenshot/runtime record
and exits. It cannot be combined with `--open`.

Implementation references: [JDK 21 jpackage](https://docs.oracle.com/en/java/javase/21/jpackage/packaging-overview.html)
and [hosted macOS images](https://github.com/actions/runner-images).
