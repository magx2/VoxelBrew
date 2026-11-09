# Full clean-check qualification — 2026-11-09

Qualified implementation: `07045392d3dc0aba878ccf0b2a47061c5df20b40`.
This includes construction tools, the new VXB magic bytes, shared-edge profile
boundaries and directional sketch selection. The subsequent report-only commit
does not change implementation or tests.

## Results

| Platform | Full root gate | Duration | JUnit tests | Failures/errors | Skipped |
| --- | --- | --- | --- | --- | --- |
| Windows | PASS | 40m 39s | 326 | 0 | 0 |
| Ubuntu WSL | PASS | 41m 44s | 326 | 0 | 0 |

Both runs executed `clean check` without exclusions, rebuilt their own real OCCT
libraries and used Java 21. Each includes 47 real JavaFX Robot scenarios and all
three shared-edge profile integration cases. CLI workflows, independent export
readers, real slicing, application launchers, updater packages and release archive
checks also passed. No source was edited during either gate.

These successful runs supersede the interrupted desktop qualification described
in [construction qualification](construction.md). The native GUI tests and package
smoke checks are automated evidence; they do not qualify macOS GUI, manual
installer interaction or physical printing.

## Exact commands and environment

Windows checkout: `D:\repos\VoxelBrew`.

```powershell
$env:JAVA_HOME='C:\Program Files\Java\amazon-corretto-21.0.6.7.1-windows-x64-jdk'
$env:GRADLE_USER_HOME='D:\repos\VoxelBrew\.tools\gradle-home'
$env:PATH='C:\Users\marti\AppData\Local\Programs\Python\Python311;'+$env:PATH
.\gradlew.bat spotlessApply --offline --no-daemon
.\gradlew.bat clean check --offline --no-daemon --no-parallel --max-workers=1
```

Ubuntu checkout: `/tmp/voxelbrew-full-check-0704539`, cloned from the same commit.
Only the Linux toolchain download directory is reused; native build output is
independent of Windows and was rebuilt by `clean check`.

```sh
bash scripts/with-virtual-desktop.sh ./gradlew clean check --offline --no-daemon --no-parallel --max-workers=1 -PnativeJobs=6
```

## Local evidence

- Windows log: `.tools/full-clean-check-windows.log`.
- Ubuntu log: `/tmp/voxelbrew-full-clean-check.log`.
- In each checkout: `gui/build/reports/tests/e2eTest/index.html`,
  `engine/build/reports/tests/integrationTest/index.html`, and
  `gui/build/ui-artifacts/`.
- Package evidence: `cli/build/package-artifacts/` and
  `gui/build/package-artifacts/`.
- Release archives: `gui/build/distributions/VoxelBrew-0.1.0-SNAPSHOT-windows-x64.zip`
  and `gui/build/distributions/VoxelBrew-0.1.0-SNAPSHOT-linux-x64.zip` in their
  respective checkouts; CLI archives are in `cli/build/distributions/`.

Generated logs, binaries and local caches remain outside source control. Hosted
CI is checked separately against the pushed commit and reported with its run URL.

## Hosted CI cancellation-test follow-up

Hosted run [42543982619](https://github.com/magx2/VoxelBrew/actions/runs/42543982619)
at `6cc9124506dc41291160be649a1e683903d0b249` exposed a timeout in the Windows
GUI shard 2 test `aboutCheckFailureRetryAndCancellation`: the local metadata
server had not received the first HTTP request within its five-second polling
deadline. Other platform jobs, including macOS headless checks, passed.

The fixture now signals receipt with a latch, and the test waits up to 30 seconds
before cancelling the in-flight lookup. It still requires a real request and
checks cancellation, retry and update states; production code is unchanged.
The isolated Windows scenario passed, followed by full root checks on both
platforms with the same two test-file changes applied to the independent Ubuntu
checkout. No source changed during these gates.

```powershell
.\gradlew.bat :gui:e2eTest --tests '*aboutCheckFailureRetryAndCancellation' --offline --no-daemon --no-parallel --max-workers=1
.\gradlew.bat check --offline --no-daemon --no-parallel --max-workers=1
```

```sh
bash scripts/with-virtual-desktop.sh ./gradlew check --offline --no-daemon --no-parallel --max-workers=1 -PnativeJobs=6
```

Windows passed in 22m 11s; Ubuntu passed in 20m 8s. Each report contains 326 tests,
zero failures/errors and zero skips, including 47 Robot scenarios. These follow-up
checks reused the native outputs from the successful clean builds above.
Logs: `.tools/ci-fix-targeted-windows.log`,
`.tools/ci-fix-full-check-windows.log`, and
`/tmp/voxelbrew-ci-fix-full-check.log`.
