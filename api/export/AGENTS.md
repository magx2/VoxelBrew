# Export API instructions

- Own only public exporter contracts, read-only export views, descriptors, typed options and writer ports.
- Depend only on api/model for CAD/manufacturing values; never depend on AGPL application modules. Keep STL/3MF formats, registries, slicer execution and file publication in export.
- Keep native handles, generated Protobuf, JavaFX, CLI parsing and DI annotations out of contracts.
- Version the SPI explicitly; verify external consumption through the independent provider fixture.
- Do not bundle API classes inside plugin JARs. Document changes in docs/file-io.md.
