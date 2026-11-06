# File import and export modules

The `import` and `export` Gradle projects separate file I/O from the CAD engine.
The Java packages are `dev.voxelbrew.importer` and `dev.voxelbrew.exporter`;
`import` is a Java keyword, so it cannot be a package segment.

| Project   | Owns                                                                                          | Production dependencies      |
| --------- | --------------------------------------------------------------------------------------------- | ---------------------------- |
| util      | Configuration, translations, logging, updates, generic CPU workers                            | No other project             |
| engine    | CAD and manufacturing models, geometry, tessellation, document schema/codec, plugin contracts | util                         |
| import    | Bounded `.jcad` reading and independent G-code motion parsing                                 | engine                       |
| export    | `.jcad` writing, STL/3MF writers, exporter discovery, slicer adapters and output publication  | engine, import, util         |
| cli / gui | Commands and desktop interactions                                                             | engine, import, export, util |

The engine has no production dependency on the file-I/O modules. Existing engine
integration tests may exercise the shared readers/writers; format/slicer suites
now live under export and the G-code parser suite under import. All modules stay
headless except GUI. No additional file formats are introduced by this refactor.
STEP/STL-to-editable-CAD import remains outside the implemented workflow.

## Public file operations

```java
import dev.voxelbrew.importer.DocumentReader;
import dev.voxelbrew.importer.GcodeReader;
import dev.voxelbrew.exporter.DocumentWriter;
import dev.voxelbrew.exporter.ExporterRegistry;
import dev.voxelbrew.exporter.ExportService;

var document = DocumentReader.load(source);
DocumentWriter.save(document, destination, false);
var preview = GcodeReader.read(gcodeFile);
```

Replace `DocumentStore.load/save` with `DocumentReader.load/DocumentWriter.save`.
Schema version and stable revision fingerprints are exposed by
`dev.voxelbrew.engine.persistence.DocumentCodec`; it also provides in-memory
`encode/decode` and never accesses files. `.jcad` schema 6 now includes the optional
`application_version` string at field 18, populated from `Version.version()` on
every save. Existing field numbers, checksums, opaque provider payloads and CAD
fingerprints retain their meanings. Writer metadata is excluded from fingerprints.
Reads enforce
the 16 MiB payload limit before parsing, even if a file grows during reading.
Writes retain explicit overwrite, unsupported-file protection, atomic replacement
and atomic create-if-absent behavior.

Files from older application versions, including legacy files with no writer
metadata, open through the existing schema readers. Opening never rewrites the
source; the next explicit save upgrades the schema and stamps the current writer
version. The document/feature IDs, parameters, print setup and opaque plugin data
are preserved. No Save As copy is required, and normal Save As remains available.
Files declaring a newer application version are rejected with an error naming
the saved and supported versions and asking the user to update VoxelBrew. Explicit
overwrite cannot bypass this check. Malformed writer versions, newer schemas and
unknown fields are also rejected. GUI failures keep the current document open;
CLI commands return a nonzero exit code without writing.

Version comparison is numeric across major/minor/patch components; a final
release follows its `-SNAPSHOT`. IDE/class-directory runs use `0.0.0` for both
saving and comparison, as returned by `Version.version()`; they do not bypass the
newer-file guard. Use a built distribution to open files from a released version.

`BinaryStlExporter`, `ThreeMfExporter`, `ExporterRegistry` and `ExportService` now
live in `dev.voxelbrew.exporter`. Prusa project/configuration writers, the backend,
slicer registry and `SliceService` live in `dev.voxelbrew.exporter.printing`.
`GcodeJob` stays an immutable engine preview model; use `GcodeReader.read` to load it.

Exporter and slicer **SPI version 1 contracts keep their existing engine packages**.
Existing provider JARs still use the same service descriptor names and compile
against the published engine artifact. Built-in registrations and the pinned
Prusa profile resource now ship in export.jar. CLI commands and GUI workflows use
the new modules without changing their user-facing file operations.

## Distribution and verification

Both new libraries are published alongside engine/util and automatically included
in development distributions and bundled images. Installation metadata identifies
`importJar` and `exportJar`; the updater checks their presence and build versions.
Older format-1 installation metadata remains readable for recovery/downgrades.

Focused checks:

```text
./gradlew :import:test :export:test :export:integrationTest
```

The compatibility fixture in `export/src/test/test/resources/fixtures/` was
generated by the previously installed `efe2535` build. Its complete file SHA-256 is
`0481466803177edae0ca10582497b7e60b698fe2d15f347f78c0ba679aa80570` and document
fingerprint is `df3bc6b5f8389380bf29db8af9e3618043d57dda4176b669883c560d643f3476`.
Tests verify unchanged legacy definitions and fingerprints after adding writer
metadata, repeat-save byte equality, older-writer upgrades, future-writer rejection,
schema-1 compatibility, corrupted and
oversized inputs, concurrent create races and failed writes preserving destinations.
Existing independent STL/3MF readers, external providers and real-slicer scenarios
exercise the relocated implementations.

Completion requires full `gradlew.bat check` on Windows and `./gradlew check` in a
separate Ubuntu WSL checkout, including GUI interaction and real packaged/extracted
application updates. Local evidence is in `.tools/io-*.log` and
`.tools/io-qualification.json`; reports remain in each module's `build/reports/tests`.
CI artifacts retain the existing three-day limit. macOS GUI qualification remains deferred.
