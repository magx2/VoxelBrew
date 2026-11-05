# Updating VoxelBrew

VoxelBrew follows Bigboy's explicit update workflow: check the GitHub release,
select the matching platform asset, download it through the authenticated `gh`
CLI, verify it, and replace the installed application after exit. The reference
is [`SelfUpdate.java` at Bigboy b8eff5c](https://github.com/magx2/bigboy/blob/b8eff5cf67fb2f02e54dbd2783eebde00a4fff5c/cli/src/main/java/pl/grzeslowski/commandcenter/cli/SelfUpdate.java).
There are no network checks at application startup.

Install [GitHub CLI](https://cli.github.com/) and run `gh auth login` with access
to `magx2/VoxelBrew`. `gh` must be on PATH; alternatively set `VOXELBREW_GH` to
its executable path. VoxelBrew uses the existing credentials and does not store
a separate token. Missing releases and authentication errors are reported without
changing the installation.

## CLI

From an extracted native app image:

```text
VoxelBrewCLI update check
VoxelBrewCLI update
VoxelBrewCLI update check --version 0.1.0
VoxelBrewCLI update --version 0.1.0
VoxelBrewCLI update --force
```

The command is `VoxelBrewCLI.exe` on Windows and `bin/VoxelBrewCLI` on Linux.
On macOS it is `VoxelBrewCLI.app/Contents/MacOS/VoxelBrewCLI`.
`update check` performs metadata lookup only. The default is the latest published
stable release; it never downgrades. An explicit `--version A.B.C` may downgrade.
`--force` allows reinstalling an equal version. Options belong after `check` when
using the check subcommand.

Successful `update` means the verified helper is ready to install after exit;
the command prints its status and log paths. Close other instances of the same
installed application, wait for `state=SUCCESS` in the status file, then reopen
the launcher. The CLI and GUI installations update independently.

## GUI

Choose **Updates…** in the workbench title bar, then **Check**. Leave the version
blank for latest stable, or enter a specific `A.B.C`. Use **Download** to prepare
the verified image, then **Install and quit**. Unsaved work uses the existing
Save / Discard / Cancel prompt. Cancelling either the update dialog or unsaved
prompt keeps the document open; cancelling the update removes its staging files.

Network lookup, downloads, verification, extraction and helper startup run away
from the JavaFX thread. Closing the dialog cancels pending work and ignores its
late results. Once the helper handoff begins, the dialog briefly prevents closing
until it either reports failure or quits for installation. Reopen VoxelBrew after
installation; the helper does not automatically launch a new GUI session.

## Application identity and recovery

Only extracted jpackage images with matching installation metadata can be replaced.
Gradle runs, `installDist`, arbitrary JARs and an externally supplied JVM can check
releases but cannot self-replace. Unlike Bigboy's single executable or JAR, a
VoxelBrew update replaces the complete app image, including Java, JavaFX where
applicable, OCCT, JNI, licenses and other dependencies. Do not rename the image's
`VoxelBrew` / `VoxelBrewCLI` directory (`.app` on macOS).

The installation's parent directory must be writable by the current user. The
updater does not elevate privileges. Windows/Linux x64 CLI and GUI and macOS x64
CLI use their separately qualified assets; macOS GUI remains deferred. Missing
platform assets are refused. Draft, prerelease, malformed and mismatched release
metadata is refused. Downloads require the release's `SHA256SUMS`; these checksums
protect integrity and rely on trust in the GitHub repository and its release
permissions, rather than a separate publisher signature.

Preparation uses a fresh sibling `.VoxelBrew-update-…` directory. Extraction rejects
links, path traversal, duplicate paths and oversized or damaged archives, and
preserves Unix executable permissions. A copy of the installed JVM runs the helper
so the active Windows runtime can be replaced safely. Installation and running
instance locks serialize updates. The helper waits up to two minutes for the parent
and other instances to close, then atomically renames the old image to
`.VoxelBrew-backup-…` (or `.VoxelBrewCLI-backup-…`) and moves the prepared image into
its place. If the second move fails, it restores the original directory.

The sibling staging directory retains `status.properties` and `helper.log`.
`FAILED` reports the error and leaves the original image or its named backup
available. After a crash between directory moves, close all instances and move
the intact backup back to the original image name. Do not merge old and new
directories. User documents, plugin directories and preferences are outside the
image and are untouched; store documents outside the installed application.

After verifying the new installation, you may remove its old backup and staging
directory. They are deliberately retained for recovery, including the copied JVM
and archive, and can consume several hundred MB. Do not remove them while a helper
is still running. Interrupted preparation may leave a staging directory that can
be removed after all application/helper processes have exited.

## Validation

`check` includes `:cli:updatePackageCheck` and `:gui:updatePackageCheck` on qualified
platforms. These copy the real app image to a path containing spaces, construct an
offline release fixture, and use the real bundled JVM and updater helper. They
verify metadata-only checks, checksum failure preserving the installation, waiting
for a second process, directory replacement, the retained backup and the new build
identity. CLI acceptance then reopens the real-kernel reference part. GUI Robot
tests exercise checking, both palettes, cancellation, late-result rejection and the
unsaved-document guard. The GUI image uses the same engine installer through a
fresh-process probe; this is distinct from a full GUI-driven production update.

Reports live in `*/build/package-artifacts/update-result.json`, its referenced
transcript directory, `engine/build/reports/tests/test/` and
`gui/build/reports/tests/e2eTest/`; screenshots live in `gui/build/ui-artifacts/`.
No live release is published by these tests. Downloading and installing a real
GitHub release remains a separate check once an updater-capable release exists.
