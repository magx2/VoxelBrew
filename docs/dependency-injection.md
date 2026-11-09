# Dependency injection and application composition

VoxelBrew uses **Google Dagger 2.60.1** for dependency injection and inversion of
control. Gradle continues to resolve libraries. The shared Gradle script
`gradle/di.gradle.kts` supplies the Dagger runtime and a separate annotation-processor
configuration to util, impl/exporter, CLI and GUI. Generated sources remain in `build/`.
Engine, impl/importer and all API projects declare no Dagger dependency or DI annotations; their CAD models,
geometry algorithms, document codecs and plugin contracts stay independently usable.

## Graphs and ownership

| Boundary        | Construction and lifetime                                                                                                                                                                                                                                                    |
| --------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| CLI             | `VoxelBrew.commandLine()` builds `CliComponent`. Picocli requests every application command through the injected `CommandFactory`. `CommandBindings` uses typed class keys and provider multibindings. Commands are unscoped so parsed flags never leak into a later parser. |
| GUI window      | `Workbench.create(stage, plugins)` builds a new `GuiComponent`. Document and print controllers, workbench, plugin registries and workers belong to that window. `@Singleton` means one instance **within that graph**, never one global document.                            |
| Update dialog   | An assisted factory combines the window's translations and lazy updater factory with the runtime save/discard callback. Each dialog gets its own worker and cancellation state and closes them when dismissed.                                                               |
| Shared services | `ApplicationModule` supplies configuration, an immutable translation catalog and `UpdateServiceFactory`. Creating a graph or requesting help performs no update lookup.                                                                                                      |
| File I/O        | Each frontend’s `FileIoModule` binds the API-module `DocumentSource`/`GcodeSource` and API-module `DocumentOutput` ports to existing bounded readers and atomic writers. Explicit replacements support tests without filesystem writes or native loading.                    |

Sketch sessions also use an assisted factory: geometry, parameters and feature
identity are combined with the injected solver, UI dispatcher and a fresh worker.
The editor closes its controller when accepted or cancelled.

Dagger validates bindings at compile time. Business classes receive their actual
dependencies through constructors, rather than receiving a component/container and
looking up dependencies. Runtime values (window, plugin path, configuration and
update factory) enter at composition roots. Dynamic plugin providers still use the
existing ServiceLoader SPI; they do not need Dagger or new constructors.

All exporters use `ExporterProvider`. Each frontend owns a local `FileIoModule`.
It discovers `ImportRuntime`, `ExportRuntime` and `SlicerRuntime` through ServiceLoader at composition
time, then binds their individual API services and catalog factories. Concrete
built-ins are composed inside the runtime implementations, which are absent from
the frontend compile classpaths. The window receives the registry through
`GuiModule`/`WindowResources`; the CLI receives the same factory. External providers
are discovered by ServiceLoader and construct their exporter with the host's worker.
Dagger does not attempt to generate bindings for classes from future plugin JARs.
File → Export refreshes the registry before listing choices, so newly installed JARs
are visible in an existing window. Older class-loader generations remain alive until
window shutdown, after captured writes have drained.

`WindowResources` owns the plugin class loaders and named executors for rebuilds,
file I/O, slicing, print validation and appearance. A failed graph/view construction
closes acquired resources. Closing a workbench cancels pending work, rejects stale
callbacks and shuts down its workers. A virtual cleanup thread waits for captured
file writes and native/slicer work to drain before closing the loaders, without
blocking JavaFX. Closing one window does not shut down another window's workers.
Native evaluation handles are still closed on their owning worker.
`Workbench.whenClosed()` reports completion after workers and loaders are closed.
Await it off the JavaFX thread before replacing plugin JARs or deleting a window's
temporary files; `close()` initiates shutdown without blocking the UI.

Pure CAD operations and immutable value objects are created normally. Existing
standalone controller constructors and static file APIs remain usable. Process
bootstrap logging and native loading stay at launcher boundaries. The headless
CLI does not initialize JavaFX, and creating a graph never loads the OCCT kernel.

## Adding services and commands

- Declare required service dependencies as final constructor parameters. Use
  `@Inject` for constructors owned by the application and `@Provides` for adapters
  or configurable services. Use `@Binds` for interface/command mappings.
- Add new CLI commands to `CommandBindings` with their class key; register classes
  with picocli rather than constructing command instances. Unbound application
  commands fail explicitly; picocli's own helpers retain its default factory.
- Choose a lifetime deliberately. Keep command arguments, document state, workers
  and native resources out of process-global singletons. Dagger does not close
  `AutoCloseable` objects: the owning window/dialog/operation must do so.
- Use constructor parameters, `ApplicationModule` and `FileIoModule` replacements
  in tests. Avoid mutable static test overrides or reflective field injection.

The compiler retains `-Werror` and other lint checks. Only javac's `processing`
lint warning is disabled because cooperating Lombok/Dagger processors do not
claim all annotations. Simple injected constructors may use Lombok's
`@RequiredArgsConstructor(onConstructor_ = @Inject)`; qualified parameters,
assisted inputs and initialization logic retain explicit constructors. See
[Lombok conventions](lombok.md). Application-image checks verify that generated components
and the Dagger runtime ship, while `dagger-compiler` and its SPI do not.

## Verification

`DependencyInjectionTest` checks nested CLI injection, independent configuration,
lazy updater creation and fresh argument state. `InjectedDocumentIoTest` checks
read failures and draining a captured save after the controller closes.
`WindowResourcesTest` checks that shutdown returns promptly while its completion
signal waits for a captured write to finish. Plugin replacement waits for this
signal before deleting JARs on Windows. The real
JavaFX `daggerGraphsIsolateWindowsPreferencesAndWorkerLifetimes` test creates a
second, Polish/dark window and checks document isolation and worker shutdown.
The existing reference, plugin, printing and update UI scenarios use the generated
graph through `Workbench.create`.

Run `./gradlew :cli:test :gui:test` for focused headless checks. Completion requires
full Windows `gradlew.bat check` and separate Ubuntu WSL `./gradlew check`, with
Xvfb/Mesa for the Linux GUI. Reports are under each module's `build/reports/tests`;
local qualification evidence is `.tools/di-qualification.json` and `.tools/di-*.log`.
macOS GUI qualification remains deferred; CI qualifies its headless CLI.

References: [Dagger Java guide](https://dagger.dev/dev-guide/basic-usage),
[Dagger assisted injection](https://dagger.dev/dev-guide/assisted-injection),
[Dagger release](https://github.com/google/dagger/releases/tag/dagger-2.60.1),
[picocli injection](https://picocli.info/#_dependency_injection).
