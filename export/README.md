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
exporters through one registry. Exporting JSON or STEP does not change the active
project path or save counter. STEP exports complete design solids, without print-bed
placements, supports or editable history. See [file I/O](../docs/file-io.md) and the
[printing workflow](../docs/printing-workflow.md) for more detail.

## One export contract

Every format implements `Exporter<R>.export(CadDocument, ExportWindow)` and is
created by an `ExporterProvider`. This includes mesh formats, exact STEP, native
documents and cloud destinations. The exporter owns its interaction requests and
publication; frontends discover providers without a separate geometry-export route.
Use a unique, stable ID and `ExporterProvider.API_VERSION` (currently 2).

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
    public ExporterDescriptor descriptor() {
        return new ExporterDescriptor("document-report", "Document report", API_VERSION);
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
Exporters own publication themselves; the host does not supply an output stream.

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

## Built-in geometry and slicing

Built-in STL, 3MF, OBJ and Prusa project exporters share `MeshExporter`, an
implementation helper in this module. They request a `MeshExportDialog` containing
format-specific options. Desktop and CLI handlers return a destination, tolerance,
body/instance selection and coordinate space. Evaluation and serialization happen
on the provider's worker; `AtomicExport` publishes the completed file atomically.

`ModelView`, `ManufacturingView`, `MeshFormat` and `MeshExportOption` are implementation
helpers, not plugin contracts in `export-api`. The public `ModelExporter` SPI and its
service resource have been removed. External exporters use `ExporterProvider` and
can define their own typed dialog requests. Existing plugins must be recompiled and
migrated to the unified contract; there is no legacy discovery adapter.

Slicing uses the same registry and exporters:

```text
CadDocument + captured manufacturing state
    -> Exporter.export(document, headless window)
    -> private input file
    -> SlicerBackend.slice(request)
    -> inspected G-code, atomically published
```

`SlicerBackend.inputFormat()` declares an exporter ID, extension and export options.
The host provides headless file/geometry dialog handlers and waits for successful
export before invoking the backend. `SlicerBackend.Request.input()` is the completed
input file; the backend consumes it rather than implementing another model writer.
The slicer SPI is version 2 because its request now includes this input path.

Prusa uses `prusa-project` (3MF) with solid supports included, preserving placements,
profiles and support modifiers. Other backends may select STL, STEP or portable 3MF
when those formats preserve the semantics they need. STEP currently exports design
coordinates, so it is not a substitute for a placed build plate. Failed or cancelled
exports prevent backend execution; temporary input is cleaned up with the slice job.

The geometry dialog can carry a detached manufacturing snapshot for slicing. Its
document ID and revision must match the exported document. Providers must not
export stale geometry, and the existing publication guard still rejects G-code if
the document changes while slicing.

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

Put implementations under `dev.voxelbrew.exporter`. Register every provider as an
`@IntoSet` binding in [`FileIoModule`](src/main/java/dev/voxelbrew/exporter/di/FileIoModule.java)
and include it in the standalone `ExporterRegistry(Path)` composition. Application
CLI/window composition roots inject the registry factory through Dagger, including
for slicing. No hardcoded GUI format entry or second service resource is needed.
Follow the repository's [DI guidance](../docs/dependency-injection.md); keep DI
annotations out of API contracts.

## Validate an exporter

Test discovery from an independently compiled JAR, cancellation, option validation,
overwrite refusal, failed writes preserving an existing destination, and filenames
containing spaces. Use an independent reader to check real output: units, transforms,
topology and measurements matter more than matching the writer's text. Geometry
acceptance requires the real kernel; test doubles only cover failure handling.

Run these focused commands from the repository root (use `.\gradlew.bat` on Windows):

```text
./gradlew :engine:buildExporterFixture :api:export:test :export:test
./gradlew :export:integrationTest --tests dev.voxelbrew.exporter.ExportTest --tests dev.voxelbrew.exporter.AdditionalFormatsTest --tests dev.voxelbrew.exporter.UnifiedExportTest --tests dev.voxelbrew.exporter.printing.SlicerExportPipelineTest
./gradlew spotlessApply
```

The integration command needs the native prerequisites described in the
[root README](../README.md). It covers STL/3MF/OBJ readers and STEP read-back through
a separate OCCT executable; the STEP reader uses the same kernel as the writer.
These focused checks do not run full GUI E2E or qualify slicers/packaged applications.
See [`fixtures/exporter-plugin`](../fixtures/exporter-plugin) for the independent
consumer build and [file I/O](../docs/file-io.md) for wider module responsibilities.
