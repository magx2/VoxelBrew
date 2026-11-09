# Export API

The `:api:export` project defines VoxelBrew's public exporter contracts
under `dev.voxelbrew.exporter.api`. It depends only on `:api:model`, so plugins can
use document and manufacturing values without depending on JavaFX, CLI parsing or
the native engine implementation.

This guide explains how to write, package, install and validate your own exporter.
For built-in formats, geometry helpers and the slicing pipeline, see the
[export implementation module](../../export/README.md).

## One export contract

Every format implements `Exporter<R>.export(CadDocument, ExportWindow)` and is
created by an `ExporterProvider`. This includes mesh formats, exact STEP, native
documents and cloud destinations. The exporter owns its interaction requests and
publication; frontends discover providers without a separate geometry-export route.
Use a unique, stable ID and a display name. Exporter descriptors have no API version field.

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
        return new ExporterDescriptor("document-report", "Document report");
    }

    @Override
    public Exporter<Path> create(Executor worker) {
        return (document, parent) -> {
            var dialog = new FileExportDialog("Document report", "txt");
            if (parent.show(dialog).isEmpty())
                return CompletableFuture.failedFuture(new CancellationException());

            var destination = dialog.getSettings();
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

### Reading confirmed settings

`ExportDialog<R>` is a base class with `R getSettings()`. Call it after
`parent.show(dialog)` confirms the interaction. It throws `IllegalStateException`
before confirmation and after cancellation or failure. Showing the same dialog
again clears the previous result before the handler runs, so stale settings cannot
be reused accidentally. Use each dialog instance for one interaction at a time.

`ExportDialogs` manages this automatically. Custom `ExportWindow` implementations
must wrap their interaction in `dialog.collectSettings(() -> ...)`, returning an
`Optional<R>` with accepted settings or an empty result on cancellation. Built-in
desktop and CLI hosts already follow this contract;
custom dialog types now extend the base class instead of implementing an interface.

### Custom or cloud dialogs

`ExportWindow` is not tied to a file destination. Define a dialog extending
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
Broken providers produce diagnostics without hiding
healthy entries. Class loaders remain open while exports may be in flight. Close
relevant application windows before replacing/removing loaded JARs, especially on
Windows. Plugins are trusted code running with the application's permissions.

### Adding a first-party exporter

Put implementations in `impl/exporter` under `dev.voxelbrew.exporter`. Include each
provider in the [`ExporterRegistry(Path)`](../../impl/exporter/src/main/java/dev/voxelbrew/exporter/ExporterRegistry.java) composition. Application
CLI/window composition roots inject the registry factory through Dagger, including
for slicing. No hardcoded GUI format entry or second service resource is needed.
Follow the repository's [DI guidance](../../docs/dependency-injection.md); keep DI
annotations out of API contracts.

## Validate an exporter

Test discovery from an independently compiled JAR, cancellation, option validation,
overwrite refusal, failed writes preserving an existing destination, and filenames
containing spaces. Use an independent reader to check real output: units, transforms,
topology and measurements matter more than matching the writer's text. Geometry
acceptance requires the real kernel; test doubles only cover failure handling.

Run these focused commands from the repository root (use `.\gradlew.bat` on Windows):

```text
./gradlew :engine:buildExporterFixture :api:export:test :impl:exporter:test
./gradlew :impl:exporter:integrationTest --tests dev.voxelbrew.exporter.ExportTest --tests dev.voxelbrew.exporter.AdditionalFormatsTest --tests dev.voxelbrew.exporter.UnifiedExportTest --tests dev.voxelbrew.exporter.printing.SlicerExportPipelineTest
./gradlew spotlessApply
```

The integration command needs the native prerequisites described in the
[root README](../../README.md). It covers STL/3MF/OBJ readers and STEP read-back through
a separate OCCT executable; the STEP reader uses the same kernel as the writer.
These focused checks do not run full GUI E2E or qualify slicers/packaged applications.
See [`fixtures/exporter-plugin`](../../fixtures/exporter-plugin) for the independent
consumer build and [file I/O](../../docs/file-io.md) for wider module responsibilities.
