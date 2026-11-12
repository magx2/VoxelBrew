# Releasing VoxelBrew

Release automation follows electrotechnika's branch/version convention and
Bigboy's separate native builds followed by one publication job. VoxelBrew uses
`jpackage` app images: each ZIP contains a native launcher, Java 21, the matching
JNI/OCCT libraries and notices. These are not GraalVM native-image executables.
Extract the entire ZIP; copying just the launcher does not work.

## Branches and versions

`projectVersion` in `gradle.properties` is the version source for every module,
published engine dependency, CLI `--version` and archive name.

| Operation           | Release branch                    | master           | Tag                            |
| ------------------- | --------------------------------- | ---------------- | ------------------------------ |
| Before preparation  | absent                            | `0.1.0-SNAPSHOT` | absent                         |
| Prepare from master | `release/0.1.x`: `0.1.0-SNAPSHOT` | `0.2.0-SNAPSHOT` | absent                         |
| Release 0.1.0       | `release/0.1.x`: `0.1.1-SNAPSHOT` | `0.2.0-SNAPSHOT` | `v0.1.0` on the `0.1.0` commit |
| Patch release 0.1.1 | `release/0.1.x`: `0.1.2-SNAPSHOT` | `0.2.0-SNAPSHOT` | `v0.1.1` on the `0.1.1` commit |

Development and feature branches keep using `master` and `codex/...`. Put fixes
for an existing release line on `release/A.B.x`. Release changes merge back into
master with master's version preserved; code conflicts fail visibly for manual
resolution. No release tag or published download is overwritten automatically.

## Maintainer procedure

1. Merge the intended changes into master and require **CAD check** to pass.
   Repository Actions must allow `GITHUB_TOKEN` to write contents and dispatch
   workflows. Branch rules must permit the release bot's fast-forward updates
   to master and `release/**`, and creation of `v*` tags. If rules reject an
   update, the job fails; it does not bypass protection.
2. Rehearse with **Release native applications** on master, leaving **dry-run**
   checked. This exercises release-version compilation, full Windows/Ubuntu
   checks, macOS CLI checks, extracted-archive tests and the complete download
   manifest. It makes no remote branch, tag or release changes.
3. Run **Release - prepare branch** on master. It atomically creates
   `release/A.B.x` from the selected revision and advances master to
   `A.(B+1).0-SNAPSHOT`. If master advanced since dispatch, retry from its new
   revision. This also explicitly dispatches CAD checks on both refs because
   ordinary pushes by `GITHUB_TOKEN` do not start another push workflow.
4. Once both checks are green, select `release/A.B.x` in **Release native
   applications**. Optionally rehearse the release branch first. To actually
   publish, uncheck **dry-run**. A non-dry run on master is refused.
5. Wait for **candidate**, all **native** jobs, **verify**, and **publish**.
   Download `SHA256SUMS` and an archive from the resulting GitHub Release;
   verify its checksum and run the CLI's `--version`. Confirm the tag targets
   `release.json`'s tested `release` SHA and merge-back's master check is green.
   Releases are not automatically marked Latest, so an older maintenance line
   cannot displace a newer release. Mark the intended newest version Latest
   in the GitHub UI or with `gh release edit vA.B.C --latest`.

Equivalent GitHub CLI commands (replace the example release line):

```text
gh workflow run release.yml --repo magx2/VoxelBrew --ref master -f dry-run=true
gh workflow run release-prepare.yml --repo magx2/VoxelBrew --ref master
gh workflow run release.yml --repo magx2/VoxelBrew --ref release/0.1.x -f dry-run=false
gh run list --repo magx2/VoxelBrew --workflow release.yml
gh run watch RUN_ID --repo magx2/VoxelBrew --exit-status
```

For later patches, start at step 4 on the existing release branch after merging
the fix and checking its CI. For the next minor line, repeat preparation on
master. For a major release, deliberately set master's next `A.0.0-SNAPSHOT`
through a reviewed change before preparation.

## What gets published

For version `A.B.C`, the release contains:

- `VoxelBrew-A.B.C-windows-x64.zip` and `VoxelBrewCLI-A.B.C-windows-x64.zip`.
- `VoxelBrew-A.B.C-linux-x64.zip` and `VoxelBrewCLI-A.B.C-linux-x64.zip`
  (Ubuntu 24.04 baseline).
- `VoxelBrew-A.B.C-windows-x64-Setup.exe` (per-user GUI installer).
- `VoxelBrewCLI-A.B.C-macos-x64.zip` and `VoxelBrew-A.B.C-macos-x64.dmg`
  (macOS Intel; the DMG contains the installed GUI app).
- `release.json`, three `platform-*.json` provenance/checksum records and
  `SHA256SUMS` covering all archives and metadata.

Windows and Ubuntu run root `check` without exclusions on the same staged
release commit. macOS runs `headlessPlatformCheck`; its GUI remains deferred
and is excluded from release downloads. Every published ZIP is extracted to
a path containing spaces and tested with no Java installation on the child
process's PATH. Linux/Mac launcher executable bits are verified. CLI checks
exercise real OCCT editing, independent STL/3MF inspection and external plugins;
Windows/Linux GUI checks render and pick geometry with the bundled runtime.
The macOS DMG release requires a `voxelbrew-macos-intel-gui` runner with accelerated
graphics to install and launch the app and pass its render-and-pick smoke test.
Local Windows and macOS installers are unsigned; the DMG is not notarized.

The candidate job creates release and next-patch commits locally and transfers
them as a Git bundle. Workers verify their parent chain and that only the
version property differs from the dispatched source. Publication checks that
the branch has not moved during compilation, uploads the complete asset set to
a draft, atomically pushes the release tag and next-patch branch, then makes
the draft public. A checksum mismatch or missing platform stops publication.
Merge-back is explicitly dispatched after ref publication, including if the
last publication step failed.

All Actions artifacts expire after **3 days**, including rehearsal downloads,
reports and screenshots. Published GitHub Release assets are durable downloads,
not Actions artifacts. Download qualification evidence within that window when
longer retention is needed. Release runs do not publish Maven packages or raw
native-library-only archives; the plugin API is bundled in the application JARs.

PrusaSlicer 2.7.2 remains external. No ARM, installers, signing/notarization,
macOS GUI or physical-printer qualification is implied. See [packaging](packaging.md).

## Failure and recovery

- Before publication, failed builds/rehearsals change no remote refs. Fix the
  source and rerun. Artifacts from different runs must never be mixed.
- A failed upload can leave a draft. If **neither** the tag nor the branch bump
  was published, inspect and delete that incomplete draft, then rerun the
  whole release. Existing drafts/tags otherwise block a new candidate.
- If the branch moved during builds, the ref transaction fails and leaves the
  draft unpublished. Remove the incomplete draft only after verifying no tag
  was created, then rebuild from the new branch revision.
- If refs were published but making the draft public failed, retain the tag
  and branch. Download and verify every draft asset against `SHA256SUMS`, check
  tag SHA/provenance and the successful platform jobs, then finish that draft
  using `gh release edit vA.B.C --draft=false --latest=false`. Do not rerun a new
  release or replace assets at that version. If assets cannot be recovered,
  leave the version unpublished and issue the next patch from the advanced
  branch after fixing the problem.
- If merge-back conflicts or races another master update, rerun **Release -
  merge back to master** on master with the release branch input. For real
  conflicts, create a `codex/merge-release-A.B` branch from master, merge the
  release branch, resolve the code and preserve master's `projectVersion`,
  then submit the resolved merge through the normal checked review process.
  A conflict never causes an automatic overwrite of master.
- If a dispatch fails after refs changed, use **CAD check**'s manual dispatch
  on the changed branches and the merge-back workflow's manual branch input.

Automation tests run as `./gradlew workflowTest` and in root `test`/`check`.
They use temporary local Git repositories, including actual atomic push and
bundle consumption. GitHub publication is simulated in these tests; a green
rehearsal validates native builds without claiming a real release was published.

References inspected: [electrotechnika release automation](https://github.com/Napad-Inc/electrotechnika/tree/c3738f3f922f16086b8dbbcb8a0cffe460a319e7/.github/workflows)
and [Bigboy CLI release](https://github.com/magx2/bigboy/blob/b8eff5cf67fb2f02e54dbd2783eebde00a4fff5c/.github/workflows/cli-release.yml).
Unlike Bigboy's integer `bb-N` versions, VoxelBrew uses electrotechnika's semantic
versions. Unlike electrotechnika's container publication, it publishes native
application ZIPs. Merge conflicts stop for manual resolution rather than
automatically opening a repair issue/PR.
