# Slicer API instructions

- Own public slicer backend, catalog, execution and runtime-provider contracts.
- Depend only on api/model and api/export; never on AGPL application modules.
- Keep native handles, Protobuf, JavaFX, CLI parsing and DI annotations out of contracts.
- Keep concrete backends, discovery, process execution and file publication in export.
- Publish the MIT slicer-api artifact with source JAR and license. Verify independent consumer compilation.
- Document package and service-registration migrations in README.md and docs/file-io.md.
