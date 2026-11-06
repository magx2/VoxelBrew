# Application logging

CLI and GUI write UTF-8 diagnostics to **`~/.voxelbrew/log/app.log`**. A new
application process archives the previous nonempty file before writing its startup
message. Files also roll over before a complete record would take the file past
**20 MB (20 × 1024 × 1024 bytes)**. An individual oversized record is kept intact
and rolls on the following write; stack traces are never cut in half.

Archives use `app-<UTC timestamp>-<unique ID>.log`. By default the newest ten
archives are retained alongside `app.log`. Only archives matching this naming
scheme are pruned; files in the previous `~/.voxelbrew/logs/` directory are untouched.
The directory is created automatically. `-Dvoxelbrew.home=...` relocates the
application home, including logs, for portable runs and tests.

Concurrent CLI and GUI processes coordinate startup, append, rotation and pruning
with a short file lock. Each record opens and closes the active file under that
lock, so every live process follows `app.log` after another process rolls it.
Records include UTC time, severity, component, process/thread IDs and logger name.
This also allows rotation on Windows while other app instances are running.

## Levels and configuration

Defaults in `~/.voxelbrew/config.properties`:

```properties
logging.level=INFO
logging.console.level=WARN
logging.file.limit=20971520
logging.file.count=10
```

`logging.file.count` is the number of retained archives (1–20); the active file is
additional. `logging.file.limit` is a byte count (at least 1024). Existing explicit
overrides remain effective. Configuration is read on application startup. Set
`logging.level=DEBUG` for rebuild, revision, plugin and worker diagnostics. File
and console thresholds are independent; console output always goes to stderr.
CLI command results on stdout retain their scripting format. JUL level names and
the SLF4J aliases `DEBUG`, `INFO`, `WARN`, `ERROR`, `TRACE` and `OFF` are accepted.

| Level | Events                                                                                                                                                                                                                  |
| ----- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| DEBUG | CAD replay timings and feature failures, undo/redo and revision changes, stale worker results, native search paths, provider discovery, rejected import/export option types, slicer processes and cancellation details. |
| INFO  | Application/window lifecycle, CLI command identity and exit status, successful open/save/import/export/slicing, explicit update checks and staging.                                                                     |
| WARN  | Incompatible/duplicate/broken plugins, unavailable geometry after a committed rebuild, rejected command input, preference and cleanup failures.                                                                         |
| ERROR | Failed file, print or update operations, native loading and unexpected application/worker failures, with exception details.                                                                                             |

All eight projects use Lombok `@Slf4j` and parameterized messages. The API projects
only log rejected typed options; provider execution is logged by import/export.
Libraries impose no runtime provider on external consumers. The frontends choose
SLF4J's JUL provider, and `util.logging.Logging` configures only `dev.voxelbrew`.
Application initialization also captures uncaught thread exceptions and forwards
them to any previously installed handler. It does not reset host/root logging.
Failed file setup falls back to WARN/ERROR console diagnostics. Runtime file-write
failures use JUL's error reporting and do not abort the CAD operation.

Messages avoid dumping entire documents, configuration maps, command argument
lists or download credentials. Paths, IDs and exception details can include
user-supplied values. The
independent update helper retains its existing recovery `helper.log` because it
must run from a minimal copied runtime while replacing application JARs.

## Verification

`./gradlew :util:test :util:integrationTest` checks UTF-8, severity routing, exact
active filename, restart archival, the actual default 20 MB boundary, retention,
uncaught exceptions, console fallback, and two independently running JVMs writing
through startup and repeated size rotation. Test homes are temporary directories.
Packaged-runtime checks require the marker in `log/app.log` with the bundled JVM.
Full Windows and Ubuntu `check` runs cover CLI, GUI, plugins, native operations,
slicing and updater regressions. Reports are in `util/build/reports/tests/` and
`*/build/package-artifacts/`; local qualification is `.tools/logging-qualification.json`.

Implementation references: [SLF4J manual](https://www.slf4j.org/manual.html),
[JDK logging handlers](https://docs.oracle.com/en/java/javase/21/docs/api/java.logging/java/util/logging/Handler.html)
and [JDK cross-process file locks](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/nio/channels/FileLock.html).
