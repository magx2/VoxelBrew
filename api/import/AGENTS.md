# Import API instructions

- Own only public importer contracts, descriptors, typed options/results and reader ports.
- Depend on engine for domain values. Keep concrete formats, registries, file access and service registrations in import.
- Keep native handles, generated Protobuf, JavaFX, CLI parsing and DI annotations out of contracts.
- Version the SPI explicitly; verify external consumption through the independent provider fixture.
- Do not bundle API classes inside plugin JARs. Document changes in docs/file-io.md.
