# Shared application utilities

`util` is a Java 21 library with no dependency on engine, JavaFX, picocli, Protobuf
or native libraries. Engine, export, CLI and GUI depend on it. Engine keeps CAD units,
sketches, documents, geometry, plugin contracts and manufacturing models; import/export
own file readers, writers and format-specific pipelines. See [file I/O](file-io.md).

| Package under `dev.voxelbrew.util` | Responsibility                                                       |
| ---------------------------------- | -------------------------------------------------------------------- |
| `config`                           | Application paths and atomic, UTF-8 user-property storage            |
| `i18n`                             | Locale selection, English fallback and translated message formatting |
| `logging`                          | Application-scoped console and rotating-file logging                 |
| `update`                           | Release lookup, verification, staging, replacement and recovery      |
| `concurrent`                       | Bounded CPU workers, cancellation and ordered result collection      |

Updater UI/commands and system appearance detection remain in the frontends.
The engine's version resource remains as the CAD artifact identity, independently
checked alongside the utility identity in packaged installations. No `.jcad`
schema or public CAD plugin contracts change.

## User configuration

Both frontends use `~/.voxelbrew/config.properties`. For example:

```properties
appearance=DARK
language=pl
logging.level=INFO
logging.console.level=WARNING
logging.file.limit=1048576
logging.file.count=3
```

Appearance accepts `SYSTEM`, `LIGHT`, `DARK`; the GUI writes changes through
`UserConfiguration` and migrates an existing Java Preferences value only when
the new key is absent. Unknown keys survive writes. Independent CLI/GUI processes
merge edits under a file lock and publish via a temporary sibling and atomic move.
Malformed properties fail without overwriting the previous file. A missing file
uses defaults and is not created by reads. Do not put document parameters here.

Use `-Dvoxelbrew.home=/path/to/user-data` to relocate configuration, plugins and
logs, or `-Dvoxelbrew.config=/path/config.properties` to override only the settings
file. Tests use temporary/isolated locations. Explicit `--plugin-dir` still wins.

## Translations

UTF-8 catalogs `messages_en.properties` and `messages_pl.properties` are in
`util/src/main/resources/dev/voxelbrew/util/i18n/`. The GUI's **Language… / Język…**
dialog selects System, English or Polski and saves through `UserConfiguration`.
Restart the application to apply the choice; an open window keeps its language
and unsaved edits. This preference never enters a `.jcad` file.

Dagger supplies an immutable `Translations` instance to each window.
`Translations.current()` is also available to process-level callers. Both read
`language` when created: `en` (default),
`pl`, a BCP 47 language tag, or `system` for the desktop locale. Restart after
changing it. Unsupported languages fall back to English, independently of the
host locale; missing keys raise a diagnostic rather than silently displaying a key.
Bundled runtimes include `jdk.localedata`, so locale-specific number/date formatting
works consistently with the development JDK.
Create `new Translations(locale)` for an explicit locale and use `text(key, args)`
for locale-aware `MessageFormat` substitution.

The English/Polish catalogs cover the workbench, view cube, property captions,
sketch tools and constraint hints, parameter/configuration editor, file/export
dialogs, printing controls, updater controls, and formula help. Standard dialog
buttons follow the selected application language. Native OS file pickers follow
the OS language. Provider descriptions and raw kernel, parser, slicer and update
service diagnostics retain their original text. Stable CLI command names,
numeric input/output (decimal point), formulas, parameter names, document values
and plugin identifiers do not depend on the display language.

Use `text(key, args)` with complete messages instead of concatenating translated
fragments. Add matching keys and argument placeholders to both catalogs. GUI
choice converters translate enum captions without changing underlying values.
Catalog and enum coverage tests run headlessly; a Polish Robot workflow checks
editing, dialogs, both palettes, save/reopen and preference persistence alongside
the existing English workflow on Windows and Ubuntu.

## Logging and concurrency

The launchers initialize `Logging` for `cli` or `gui`. Files rotate under
`~/.voxelbrew/logs/`; defaults are INFO, three files of 1 MiB per process.
Console diagnostics default to WARNING on stderr, preserving CLI stdout.
Configuration changes apply on restart. Logging affects only the `dev.voxelbrew`
hierarchy and does not reset host or third-party loggers. Log setup failure leaves
console diagnostics available. Update-helper status/log files remain in their
existing recovery directory.

`CpuWork` moved unchanged from engine. Its bounded parallelism and
`-Dvoxelbrew.compute.threads=1..32` override are unchanged; CAD computations still
control which operations are safe to run concurrently.

## Verification

Run `./gradlew :util:test :util:integrationTest :gui:test` for the service tests,
including four independent JVMs writing the same configuration. Run the full
`./gradlew check` (Windows: `.\gradlew.bat check`) for CAD, GUI, independent plugin,
packaging and real updater-helper regression coverage. Packaged-runtime checks
verify UTF-8 configuration, Polish translations/number
formatting and actual file logging with the bundled JVM. The standalone exporter
fixture resolves published engine/util artifacts from the fixture Maven repository.
CI includes util automatically in the root suites and retains artifacts for three days.

Local completion evidence is recorded under `.tools/util-*.log` and
`.tools/util-qualification.json`; test reports live in each module's
`build/reports/tests/`. Windows and a separate Ubuntu WSL checkout must both pass
before handoff. macOS GUI qualification remains deferred.
