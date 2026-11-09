# Lombok and logging

All eight projects use Lombok **1.18.48** as `compileOnly` and `annotationProcessor`
in main, unit, integration and end-to-end source sets. The shared configuration is
`gradle/lombok.gradle.kts`. Root `lombok.config` stops inherited machine-specific
settings and is a declared compiler input, so changing it invalidates compilation.
Lombok is not a runtime dependency or a requirement for plugin consumers.

## Conventions

- Use `@Slf4j` for application diagnostics, with parameterized messages and the
  exception as the last argument. CLI command results and user-facing errors
  retain their existing stdout/stderr behavior.
- Use field-level `@Getter` for plain accessors. `@Accessors(fluent = true)` keeps
  existing `path()`, `state()` and similar APIs. Keep checks, defensive copies,
  derived values and synchronized methods explicit.
- Use `@Setter` only for intentionally mutable state. Controller callback setters
  use `@Accessors(fluent = true, chain = false)` to retain `void onChanged(...)`.
  Document mutations still go through validated, undo-aware editing operations.
- Use `@RequiredArgsConstructor` for constructors that only assign dependencies.
  Preserve constructor visibility and parameter order (generated arguments follow
  field declaration order). Keep constructors that initialize mutable nullable
  fields explicit when they cannot be represented by required arguments.
  Use `@NonNull` on parameters or record components instead of
  `Objects.requireNonNull(parameter)`, and on required fields for generated constructors.
  Keep explicit null checks for returned values and resource lookups.
  `CommandFactory` uses `onConstructor_ = @Inject` to preserve Dagger injection;
  its generated constructor is exercised by the normal CLI tests. Keep
  constructors with qualifiers, assisted parameters or initialization logic explicit.
- `@Accessors` and Lombok's `onConstructor` mechanism are experimental Lombok
  features; use them narrowly and keep the dependency pinned.
- Existing Java records remain records. Add `@Builder`, `@With`, `@ToString` or
  `@EqualsAndHashCode` only when a type needs those semantics. Avoid blanket
  `@Data` on controllers, mutable models or native-resource owners; it can expose
  mutation and generate inappropriate equality or string representations.
  Keep checked failures explicit instead of hiding them with `@SneakyThrows`.

## Log routing

SLF4J **2.0.20** is the logging API. CLI and GUI select `slf4j-jdk14` at runtime,
which routes messages to the existing JUL hierarchy. Published libraries expose
the API dependency without imposing a provider on their host. Test suites select
the same JUL provider as the applications.

`util.logging.Logging` still owns the `dev.voxelbrew` handlers, UTF-8 rotating
files and console levels. It leaves the host application's root handlers alone.
Only backend configuration uses JUL directly; application messages use `@Slf4j`.
There is no reverse JUL-to-SLF4J bridge.

The active file is `~/.voxelbrew/log/app.log`, with startup and 20 MB rollover.
See [logging configuration, retention and event levels](logging.md).

Existing `~/.voxelbrew/config.properties` options and JUL level names work as
before. SLF4J names are also accepted: `TRACE` → `FINEST`, `DEBUG` → `FINE`,
`WARN` → `WARNING`, and `ERROR` → `SEVERE`. Defaults remain `INFO` for files and
`WARNING` for the console. For example:

```properties
logging.level=DEBUG
logging.console.level=WARN
```

## Verification

Logging tests exercise the generated logger, formatting, level filtering, exception
details, rotation, UTF-8, no duplicated messages, unchanged host handlers and empty
stdout. Existing controller/GUI tests exercise generated accessors and callbacks;
Dagger tests exercise the generated injected constructor. Package checks require
the API and JUL provider, reject Lombok and a reverse logging bridge at runtime,
and reject SLF4J provider diagnostics from launched applications.

Run full Windows `gradlew.bat check` and Ubuntu `./gradlew check` with Xvfb/Mesa
for GUI checks. Local reports are in `*/build/reports/tests/`; qualification
commands and results are recorded in `.tools/lombok-qualification.json`.

References: [Lombok Gradle setup](https://projectlombok.org/setup/gradle),
[constructors](https://projectlombok.org/features/constructor),
[accessors](https://projectlombok.org/features/GetterSetter),
[logging](https://projectlombok.org/features/log), and
[SLF4J manual](https://www.slf4j.org/manual.html).
