# Import API

The `:api:import` project defines VoxelBrew's public importer contracts under
`dev.voxelbrew.importer.api`. It depends only on the MIT `:api:model` module.
Shared domain classes retain their `dev.voxelbrew.engine.*` package names, but
using them does not require the native engine implementation.

This guide explains how to build, package and install your own importer. Built-in
VXB and JSON importers restore editable documents; the G-code importer returns a
toolpath preview. See [file I/O](../../docs/file-io.md) for implementation boundaries
and the [export API guide](../export/README.md) for the corresponding export workflow.

## Build an external plugin

Use a separate Java 21 project with a matching host version. For Gradle Kotlin DSL:

```kotlin
plugins { `java-library` }

repositories {
    mavenLocal()
    mavenCentral()
}

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

val voxelbrewVersion = providers.gradleProperty("voxelbrewVersion").get()
dependencies {
    compileOnly("dev.voxelbrew:import-api:$voxelbrewVersion")
}
```

Publish local API artifacts from the VoxelBrew repository root:

```text
./gradlew :api:model:publishToMavenLocal :api:import:publishToMavenLocal
```

Then build your separate project with
`./gradlew jar -PvoxelbrewVersion=<matching-host-version>`. Use `gradlew.bat` on
Windows. The host supplies `import-api` and its transitive `model-api` dependency
at runtime: do not bundle copies of either in your plugin JAR. Public importer
contracts must not expose JavaFX, picocli, generated Protobuf classes or native
pointers. External providers do not need Dagger or Lombok.

## Implement a provider

`ImporterProvider` exposes discovery metadata and creates an importer with the
host's worker executor:

```java
ImporterDescriptor descriptor();
Importer<? extends ImportResult> create(Executor worker);
```

The importer implements `CompletableFuture<R> importFrom(ImportWindow parent)`.
For a discoverable provider, return `ImportResult.Document` or `ImportResult.Gcode`
and advertise the matching `ImportResult.Kind`. The host rejects mismatched results.
Use `ImporterProvider.API_VERSION` (currently 2), a unique stable ID matching
`[a-z0-9][a-z0-9.-]*`, and a nonblank display name. File extensions and options
belong to the importer-owned dialog, not the descriptor.

The following complete example reads a UTF-8 file containing `width,height,thickness`
in millimeters, such as `60,40,5`. It returns a rectangle sketch and extrusion that
remain editable after import. The optional `scale` setting defaults to 1.

```java
package example;

import dev.voxelbrew.engine.model.CadDocument;
import dev.voxelbrew.engine.model.Feature;
import dev.voxelbrew.importer.api.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.*;

public final class PlateImporterProvider implements ImporterProvider {
    private static final int MAX_BYTES = 4096;

    public PlateImporterProvider() {}

    @Override
    public ImporterDescriptor descriptor() {
        return new ImporterDescriptor(
                "example-plate", "Example plate", API_VERSION, ImportResult.Kind.DOCUMENT);
    }

    @Override
    public Importer<ImportResult.Document> create(Executor worker) {
        return parent -> {
            try {
                var dialog = new FileImportDialog(
                        "Import plate", List.of("plate"),
                        List.of(new ImportOption(
                                "scale", "Scale", ImportOption.Type.DECIMAL, "1", List.of())));
                if (parent.show(dialog).isEmpty())
                    return CompletableFuture.failedFuture(new CancellationException());

                var settings = dialog.getSettings();
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return read(settings);
                    } catch (IOException failure) {
                        throw new CompletionException(failure);
                    }
                }, worker);
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    private static ImportResult.Document read(FileImportDialog.Source settings) throws IOException {
        byte[] bytes;
        try (var input = Files.newInputStream(settings.path())) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("Plate input exceeds 4 KiB");
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        String[] fields = text.strip().split(",", -1);
        if (fields.length != 3) throw new IOException("Expected width,height,thickness");
        double scale = positive(settings.options().get("scale"));
        double width = positive(fields[0]) * scale;
        double height = positive(fields[1]) * scale;
        double thickness = positive(fields[2]) * scale;
        if (!Double.isFinite(width) || !Double.isFinite(height) || !Double.isFinite(thickness))
            throw new IOException("Scaled dimensions must be finite");

        var sketch = new Feature.Sketch(
                "plate", "profile", new Feature.Plane("origin-xy", 0),
                new Feature.Rectangle(0, 0, width, height));
        var extrude = new Feature.Extrude(
                "thickness", new Feature.ProfileRef("plate", "profile"), thickness);
        return new ImportResult.Document(new CadDocument("imported-plate", List.of(sketch, extrude), 2));
    }

    private static double positive(String value) throws IOException {
        try {
            double number = Double.parseDouble(value.strip());
            if (Double.isFinite(number) && number > 0) return number;
        } catch (NumberFormatException ignored) {
            // Report the same useful diagnostic for malformed and out-of-range values.
        }
        throw new IOException("Dimensions and scale must be positive finite numbers");
    }
}
```

Collect settings synchronously on the caller's interaction thread (the JavaFX thread
in the desktop), then perform reading and conversion on the supplied executor.
Capture accepted settings before starting background work. Never shut down the
executor, retain mutable invocation settings on a shared provider, or write the
source file. Complete the future only when reading, validation and resource cleanup
have finished. Propagate failures and use `CancellationException` for cancellation.
Cancelling a future alone does not interrupt a running read; implement cooperative
cancellation yourself if your format needs it.

Each plugin owns its input access and size limits. The host does not supply or bound
an input stream in API version 2. The example bounds the actual read, including files
that grow after opening, and rejects malformed UTF-8. Validate units, dimensions,
references and format-specific limits before returning detached domain values.
Do not represent a triangle mesh as editable feature history unless you actually
construct editable definitions.

## Register and install

Create this UTF-8 resource in the separate plugin project:

```text
src/main/resources/META-INF/services/dev.voxelbrew.importer.api.ImporterProvider
```

Its contents are the fully qualified provider class name:

```text
example.PlateImporterProvider
```

The provider must have a public no-argument constructor. Copy the resulting JAR
into `~/.voxelbrew/plugins`, or use `--plugin-dir` to select another directory.
Open **File > Import** (`Ctrl+I`) again to discover the provider. Choose **Example
plate**, then supply the source path and scale in its file dialog.

Using the installed CLI launcher (`cli.bat` on Windows):

```text
cli importers --plugin-dir "path with spaces/plugins"
cli import "plate dimensions.plate" --format example-plate --option scale=2 --output plate.vxb --plugin-dir "path with spaces/plugins"
```

Document imports require a separate `--output`; add `--overwrite` to replace an
existing output intentionally. The CLI rejects an output that is the source file.
G-code imports return a preview, reject `--output`, and are available through the
CLI/API rather than the desktop document-import menu. Desktop document imports open
an unsaved design requiring Save As; cancellation or failure leaves the current
design intact. `cli importers` lists discovery metadata, not dialog-specific options.

Built-ins register first. Duplicate IDs keep the first provider; incompatible API
versions and broken providers produce diagnostics without hiding healthy entries.
Plugin loaders stay alive until the import completes. Avoid replacing or removing
a JAR while an operation uses it, especially on Windows. Plugins execute with the
application's permissions and must come from a trusted source.

## Settings and custom interactions

`FileImportDialog` carries a title, extensions without leading dots, and optional
`ImportOption` definitions. Supported option types are STRING, BOOLEAN, INTEGER,
DECIMAL and CHOICE. Supply defaults and, for CHOICE, the allowed values. Standard
hosts validate supplied values and apply defaults using `request.settings(path, values)`.
Keep semantic checks such as positive dimensions in the importer too.

`ImportDialog<R>.getSettings()` is available only after a successful `parent.show`.
Before confirmation, after cancellation or after failure, it throws
`IllegalStateException`. Reusing a request clears the previous result before the
next interaction. Keep each request local to one invocation.

For headless invocation of a file importer:

```java
var host = FileImportDialog.source(sourcePath, java.util.Map.of("scale", "2"));
var result = new PlateImporterProvider().create(worker).importFrom(host).get();
```

For cloud or other sources, extend `ImportDialog<YourSettings>`, implement `title()`,
and request it through `ImportWindow.show`. Each supported frontend must supply a
handler for that exact request class:

- Desktop: `DesktopImportWindow.register(Request.class, (owner, request) -> ...)`.
- Headless hosts/tests: `ImportDialogs.register(Request.class, request -> ...)`.

Return `Optional.empty()` for cancellation. Unknown request classes fail explicitly.
Handlers registered through `ImportDialogs` get the settings lifecycle automatically;
a custom `ImportWindow` implementation must wrap the entire interaction, including
handler lookup, in `dialog.collectSettings(() -> ...)`.

Installing a provider JAR does **not** install custom dialog renderers. The stock CLI
and desktop understand `FileImportDialog`; other dialog classes need host integration.
Keep frontend classes out of your public dialog contract.

## First-party providers and migration

First-party implementations belong in `impl/importer`, under `dev.voxelbrew.importer`.
Include the provider in the [`ImporterRegistry(Path)`](../../impl/importer/src/main/java/dev/voxelbrew/importer/ImporterRegistry.java) constructor.
The runtime supplies this registry to the frontend Dagger composition roots.
The GUI discovers formats through that registry; no separate GUI format list is needed.
Keep DI annotations out of public API contracts.

The former API-version-1 `FileImporter` stream interface and its service resource
were removed. Recompile old plugins against API version 2, register `ImporterProvider`,
move file settings from descriptors to dialogs, and own input access inside the
asynchronous importer. `DocumentSource` and `GcodeSource` remain direct reader ports;
they are not the plugin-discovery SPI.

## Validate your importer

Use temporary sources and check successful editable definitions, malformed and oversized
input, unknown options, cancellation without scheduling work, rejected executors,
failures preserving source bytes, and paths containing spaces. Test an independently
built JAR to catch bundled host classes or accidental implementation dependencies.
Use real engine evaluation when verifying geometry rather than only checking feature text.

From the repository root, the existing API-only consumer fixture and importer tests run with:

```text
./gradlew :engine:buildExporterFixture :api:import:test :impl:importer:test
./gradlew :impl:importer:integrationTest
```

The integration suite requires the native prerequisites in the [root README](../../README.md).
Use `gradlew.bat` on Windows. See the working
[`ProbeImporter`](../../fixtures/exporter-plugin/src/main/java/consumer/ProbeImporter.java),
its [independent build](../../fixtures/exporter-plugin/build.gradle.kts), and the
[import validation record](../../docs/acceptance/importers.md). These focused checks
are not full GUI E2E, installer or platform acceptance.
