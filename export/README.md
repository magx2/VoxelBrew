# Export module

The `:export` Gradle project implements VoxelBrew's document and geometry exporters,
runtime exporter discovery, and external-slicer file pipeline. Its Java package is
`dev.voxelbrew.exporter`. Both the JavaFX desktop and headless CLI use this module.

Public plugin contracts live in [`:api:export`](../api/export), not here. The engine
owns document codecs, evaluation, native geometry and tessellation; this module owns
format implementations and file publication. Production dependencies point from
export to engine, never in the opposite direction.

## Built-in formats

| Format ID | Output | Implementation |
| --- | --- | --- |
| `vxb` | Editable VoxelBrew document, including feature history | `JcadExporter` / `JcadExporterProvider` |
| `json` | Complete document schema as UTF-8 ProtoJSON | `JsonExporter` / `JsonExporterProvider` |
| `step` | Exact design solids as STEP AP242, in millimeters | `StepExporter` / `StepExporterProvider` |
| `stl` | Binary STL mesh, in millimeters | `BinaryStlExporter` |
| `3mf` | Portable 3MF with units and instance placements | `ThreeMfExporter` |
| `obj` | Wavefront OBJ mesh, with instance transforms applied | `ObjExporter` |
| `prusa-project` | PrusaSlicer project from a complete manufacturing view | `printing.PrusaProjectExporter` |

The native document extension is `.vxb`; the `Jcad` implementation class names are
retained from the earlier extension. Existing `.jcad` documents remain readable.
**File > Save** uses the native document writer; **File > Export** lists discovered
document and model exporters. Exporting JSON or STEP does not change the active
project path or save counter. STEP exports complete design solids, without print-bed
placements, supports or editable history. See [file I/O](../docs/file-io.md) and the
[printing workflow](../docs/printing-workflow.md) for more detail.

## Choose an extension point

| Contract | Use it when | Host responsibilities | Provider responsibilities |
| --- | --- | --- | --- |
| `ExporterProvider` creates an `Exporter<R>` | You need a `CadDocument`, custom interactions, or a non-file destination | Discovery, parent-window abstraction, supplied executor | Dialog requests, export work, publication, result/failure future |
| `ModelExporter` | You serialize evaluated geometry to a file | Geometry selection, mesh/options UI, borrowed output stream, atomic publication | Descriptor, format serialization, instance/units handling |

Both appear in the runtime Export catalog. Use a unique, stable ID across both
contracts: the CLI selects by ID and checks document providers first. Use the
contract's `API_VERSION` constant rather than inventing a version number.

## Build an external plugin

Use a separate Java 21 project. Compile against `export-api` and let the host provide
its API classes at runtime. Shared document and mesh values arrive transitively from
`model-api`; their `dev.voxelbrew.engine.*` package names do not imply an engine
implementation dependency.

For example, in `build.gradle.kts`:

```kotlin
plugins { `java-library` }

repositories {
    mavenLocal()
    mavenCentral()
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

val voxelbrewVersion = providers.gradleProperty("voxelbrewVersion").get()
dependencies {
    compileOnly("dev.voxelbrew:export-api:$voxelbrewVersion")
}
```

Publish the local API artifacts from the VoxelBrew repository root:

```text
./gradlew :api:model:publishToMavenLocal :api:export:publishToMavenLocal
```

Then build the plugin with `./gradlew jar -PvoxelbrewVersion=<matching-host-version>`.
On Windows use `gradlew.bat` instead of `./gradlew`. Do not bundle host API classes
in the plugin JAR. An API-only exporter needs no JavaFX, picocli, Protobuf, Dagger,
or native-pointer dependency. If your implementation needs native evaluation,
that is an additional engine dependency; the public export API does not expose
native solids. The built-in STEP provider demonstrates that separation.

## Implement a document exporter

`Exporter<R>` has one operation:

```java
CompletableFuture<R> export(CadDocument document, ExportWindow parent);
```

The exporter requests its own interactions through `parent`. `R` is your result:
a file path, cloud receipt, remote identifier, or another value. A public provider
with a public no-argument constructor makes the implementation discoverable.

This complete example writes a small document report and publishes it only after
the temporary file has been written successfully:

```java
package example;

import dev.voxelbrew.exporter.api.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.*;

public final class DocumentReportProvider implements ExporterProvider {
    public DocumentReportProvider() {}

    @Override
    public DocumentExporterDescriptor descriptor() {
        return new DocumentExporterDescriptor("document-report", "Document report", API_VERSION);
    }

    @Override
    public Exporter<Path> create(Executor worker) {
        return (document, parent) -> {
            var selected = parent.show(new FileExportDialog("Document report", "txt"));
            if (selected.isEmpty())
                return CompletableFuture.failedFuture(new CancellationException());

            var destination = selected.get();
            String report = "document=" + document.id() + "\n";
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return publish(destination, report);
                } catch (IOException failure) {
                    throw new CompletionException(failure);
                }
            }, worker);
        };
    }

    private static Path publish(FileExportDialog.Destination destination, String report)
            throws IOException {
        Path target = destination.path().toAbsolutePath().normalize();
        Path temporary = Files.createTempFile(target.getParent(), ".document-report-", ".tmp");
        try {
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var bytes = ByteBuffer.wrap(report.getBytes(StandardCharsets.UTF_8));
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            if (destination.overwrite()) {
                Files.move(temporary, target,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                // Atomic create-if-absent: a concurrent writer cannot be overwritten.
                Files.createLink(target, temporary);
            }
            return target;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
```

The destination parent must exist. Filesystems without atomic replacement or hard
links fail explicitly; do not fall back to truncating an existing destination.
Document exporters own publication themselves: `ExportService` does not wrap them.

Request desktop interactions on the caller's UI thread, then schedule expensive work
on the supplied executor. Do not shut down that executor. Complete the future only
after the operation finishes, propagate failures, and report cancellation with
`CancellationException`. Do not mutate the input document or its save metadata for
an ordinary export. Geometry exporters must reject failed or rolled-back evaluations.

Providers that rebuild custom features can override `create(Executor, Path)` to
receive the host's feature-plugin directory. The default overload delegates to
`create(Executor)`.

Create this UTF-8 resource in your plugin project:

```text
src/main/resources/META-INF/services/dev.voxelbrew.exporter.api.ExporterProvider
```

Its content is the provider class name, not the returned exporter class:

```text
example.DocumentReportProvider
```

### Custom or cloud dialogs

`ExportWindow` is not tied to a file destination. Define a request implementing
`ExportDialog<YourSettings>`, including its `title()`, and call `parent.show(request)`.
An empty `Optional` means cancellation. You can request account/project selection
and return an upload receipt without writing a local file.

Each supported frontend must register a handler for your request type:

- Desktop: `DesktopExportWindow.register(Request.class, (owner, request) -> ...)`
  receives the actual JavaFX owner window in the GUI adapter.
- CLI or tests: `ExportDialogs.register(Request.class, request -> ...)` supplies
  settings without JavaFX. For file destinations, use
  `FileExportDialog.destination(path, overwrite)`.

Handlers are local to a host/window. Unknown request types fail explicitly. **JAR
discovery does not automatically install custom dialog renderers**; shipping a cloud
provider alone does not teach the stock CLI or desktop how to collect its settings.
Keep JavaFX types out of the provider's public interaction contract.

## Implement a model exporter

Implement `ModelExporter` with a public no-argument constructor. Its
`descriptor()` returns an `ExporterDescriptor` containing the ID, label, API version,
file extensions, multiple-instance capability and format options. Its writer is:

```java
void write(ModelView model, MeshOptions meshOptions,
           Map<String, String> options, OutputStream output) throws IOException;
```

The host validates supplied option names/values and fills descriptor defaults.
Options support string, boolean, integer, decimal and choice values. The GUI builds
the corresponding editors; the CLI accepts `--option key=value`.

Iterate `model.instances()` when writing placed geometry. Tessellate each instance's
body using `model.tessellate(instance.bodyId(), meshOptions)`, then apply its transform
exactly once or serialize that transform in the target format. Preserve millimeter
units, winding, body/instance identity and finite coordinates. Use
`MeshValidation.requireClosed` when the format requires closed solids. Respect the
provided mesh tolerance instead of substituting a fixed tessellation.

Write to the borrowed stream synchronously. Do not close or retain the stream or
model view, and do not launch background writes after `write` returns. `ExportService`
owns the temporary destination, durability flush and atomic publication; exceptions
discard partial output. A format needing a complete print setup can require
`ManufacturingView`, as the Prusa project exporter does.

Register the implementation class in:

```text
src/main/resources/META-INF/services/dev.voxelbrew.exporter.api.ModelExporter
```

See [`ObjExporter`](src/main/java/dev/voxelbrew/exporter/ObjExporter.java) for a
geometry writer and the independent
[`ProbeExporter`](../fixtures/exporter-plugin/src/main/java/consumer/ProbeExporter.java)
for descriptor options and an API-only consumer.

## Install and discover providers

Copy the built JAR into `~/.voxelbrew/plugins`, or the directory configured through
`--plugin-dir`. Open **File > Export** again: it refreshes the registry and discovers
new JARs without rebuilding the application. External providers use Java's
`ServiceLoader`; they do not need Dagger annotations.

```text
cli exporters --plugin-dir "path with spaces/plugins"
cli export design.vxb --format document-report --output report.txt --plugin-dir "path with spaces/plugins"
```

Here `cli` means the installed CLI launcher (`cli.bat` on Windows). Add `--overwrite`
to replace an existing output intentionally. The stock CLI requires `--output` for
file-dialog and model exporters, but not for a provider that never requests a file.

Duplicate IDs keep the first registered provider; built-ins are registered first.
Incompatible API versions and broken providers produce diagnostics without hiding
healthy entries. Class loaders remain open while exports may be in flight. Close
relevant application windows before replacing/removing loaded JARs, especially on
Windows. Plugins are trusted code running with the application's permissions.

### Adding a first-party exporter

Put implementations under `dev.voxelbrew.exporter`. Register document providers as
`@IntoSet` bindings in [`FileIoModule`](src/main/java/dev/voxelbrew/exporter/di/FileIoModule.java)
and include them in the standalone `ExporterRegistry(Path)` composition. Application
CLI/window composition roots inject the registry factory through Dagger. Model
exporters use this module's `META-INF/services/...ModelExporter` resource. Neither
route requires a hardcoded GUI format entry. Follow the repository's
[DI guidance](../docs/dependency-injection.md); do not add DI annotations to API contracts.

## Validate an exporter

Test discovery from an independently compiled JAR, cancellation, option validation,
overwrite refusal, failed writes preserving an existing destination, and filenames
containing spaces. Use an independent reader to check real output: units, transforms,
topology and measurements matter more than matching the writer's text. Geometry
acceptance requires the real kernel; test doubles only cover failure handling.

Run these focused commands from the repository root (use `.\gradlew.bat` on Windows):

```text
./gradlew :engine:buildExporterFixture :api:export:test :export:test
./gradlew :export:integrationTest --tests dev.voxelbrew.exporter.ExportTest --tests dev.voxelbrew.exporter.AdditionalFormatsTest
./gradlew spotlessApply
```

The integration command needs the native prerequisites described in the
[root README](../README.md). It covers STL/3MF/OBJ readers and STEP read-back through
a separate OCCT executable; the STEP reader uses the same kernel as the writer.
These focused checks do not run full GUI E2E or qualify slicers/packaged applications.
See [`fixtures/exporter-plugin`](../fixtures/exporter-plugin) for the independent
consumer build and [file I/O](../docs/file-io.md) for wider module responsibilities.
