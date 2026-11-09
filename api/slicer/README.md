# Slicer API

`:api:slicer` publishes the MIT `dev.voxelbrew:slicer-api` artifact, including a
source JAR and `META-INF/LICENSE`. It owns the headless contracts in
`dev.voxelbrew.slicer.api`:

- `SlicerBackend`: version-2 backend SPI, input format, capabilities, probing and execution requests.
- `SlicerCatalog`: scoped discovery results and catalog factory.
- `Slicing`: validated job execution, publication guard, result and outdated-job exception.
- `SlicerRuntime`: runtime service factory used by CLI/GUI composition roots.

The module depends on `api:model` for CAD/manufacturing values and `api:export`
for the exporter catalog used to prepare slicer input. It has no dependency on
engine, concrete file I/O, JavaFX, picocli or Dagger. Process execution, built-in
backends, discovery and atomic job publication remain in the `export` runtime.

## External backends

Compile against `dev.voxelbrew:slicer-api:<version>` with `compileOnly` (Maven
`provided`). Do not bundle host API classes in the provider JAR. Implement
`SlicerBackend` with a public no-argument constructor and register its class in:

```text
META-INF/services/dev.voxelbrew.slicer.api.SlicerBackend
```

Copy the JAR into the trusted plugin directory. `SlicerBackend.inputFormat()`
selects the exporter that prepares `Request.input()`; the backend writes only
its private supplied output. Honor cancellation and report actual probe capabilities.
The host validates and publishes the resulting job. The independent
`fixtures/exporter-plugin` build includes `consumer.ProbeSlicer` to verify that
providers compile against published APIs without application-module dependencies.
This fixture does not qualify a real slicer; real slicing tests use PrusaSlicer.

## Migration

Replace `dev.voxelbrew.exporter.printing.SlicerBackend` and
`dev.voxelbrew.exporter.api.SlicerCatalog` / `Slicing` imports with
`dev.voxelbrew.slicer.api` imports. Update the service-resource filename and
recompile providers. The SPI version remains 2 because its methods and semantics
are unchanged. `ExportRuntime` no longer provides slicing services; use
`SlicerRuntime` at the composition root and inject the individual services.
CLI/GUI include `slicer-api` at compile time and in their distributions while
keeping the concrete `export` project as a runtime dependency.

```bash
./gradlew :api:slicer:publishToMavenLocal
./gradlew :engine:buildExporterFixture :export:test --tests '*SlicerRuntimeTest' verifyApiDependencies verifyFrontendIoDependencies
```
