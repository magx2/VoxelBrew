# Shared application utilities

`util` is a Java 21 library with no dependency on engine, JavaFX, picocli, Protobuf
or native libraries. Engine, export, CLI and GUI depend on it. Engine keeps CAD units,
sketches, documents, geometry, feature contracts and manufacturing models; api/import and api/export
own file-I/O contracts, while import/export
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
checked alongside the utility identity in packaged installations. No `.vxb`
schema or public CAD plugin contracts change.

## Application version

`dev.voxelbrew.util.Version.version()` returns the Gradle project version embedded
in the utility JAR's `Implementation-Version` manifest attribute (for example,
`1.2.3`). It preserves snapshot suffixes. IDE/class-directory runs return `0.0.0`,
including when processed Gradle resources are present. The updater's strict
artifact identity checks remain separate.

## User configuration

Both frontends use `~/.voxelbrew/config.properties`. For example:

```properties
appearance=DARK
language=pl
logging.level=INFO
logging.console.level=WARNING
logging.file.limit=20971520
logging.file.count=10
```

The `save.folder` configuration parameter selects the initial folder for saving new documents. Missing or blank values use `~/VoxelBrew`. A leading `~/` expands to the user home directory. Existing documents retain their current path. Set this property in `~/.voxelbrew/config.properties`; changes apply immediately.

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
`util/src/main/resources/dev/voxelbrew/util/i18n/`. The GUI's **Settings → Language… / Ustawienia → Język…**
dialog lists System and the available catalog languages, and saves through `UserConfiguration`.
Additional UTF-8 catalogs can be placed in `~/.voxelbrew/i18n/` as
`messages_<tag>.properties` (for example, `messages_de-DE.properties`). Each
catalog must define `language.name` for its menu label and `language.i18n` with
the matching language tag. Missing message keys use the bundled English text.
Restart the application to apply the choice; an open window keeps its language
and unsaved edits. This preference never enters a `.vxb` file.

Dagger supplies an immutable `Translations` instance to each window.
`Translations.current()` is also available to process-level callers. Both read
`language` when created: `system` (default), `en`, `pl`, or a BCP 47 language tag.
On first launch, or with a missing/blank preference, the GUI uses the system's
display language rather than its number/date formatting locale. An unset or
unsupported system language falls back to English. A saved language overrides
the system; invalid or unsupported saved languages also fall back to English.
Detection does not write a preference, so System remains selected until changed
explicitly. Restart after changing it. INFO logs record the selected language,
its source, and any fallback reason. Missing keys raise a diagnostic rather than
silently displaying a key.
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
Action descriptions use `help.*` keys keyed by stable control IDs. `UiHelp` in
`gui` attaches these as hover tooltips and accessible help, including native
menu popup nodes and standard dialog buttons. Add a description in both catalogs
when adding a button; keep required selections and the effect of committing or
cancelling explicit. The source audit and Robot tests cover action factories,
dynamic constraint/geometry actions, visible help popups and modal shortcut isolation.
Catalog and enum coverage tests run headlessly; a Polish Robot workflow checks
editing, dialogs, both palettes, save/reopen and preference persistence alongside
the existing English workflow on Windows and Ubuntu.

## Logging and concurrency

The launchers initialize `Logging` for `cli` or `gui`. Both write UTF-8 to
`~/.voxelbrew/log/app.log`. Each application start archives the previous nonempty
file; size rollover defaults to 20 MB (20 × 1024 × 1024 bytes). Ten timestamped
archives are retained by default. See [logging behavior and levels](logging.md).
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
