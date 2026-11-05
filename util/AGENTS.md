# Shared utility instructions

- Keep this module independent of engine, JavaFX, picocli, Protobuf and native CAD libraries.
- Own application configuration, translations, logging, update infrastructure and generic concurrency.
- Keep CAD units, geometry, document persistence and manufacturing services in engine.
- Preserve unknown configuration keys and publish writes atomically; tests must use temporary paths.
- Keep CLI output stable and send diagnostic logs to stderr or files, never stdout.
- Updates remain explicit; startup must not contact the network.
- Use `:util:test` for isolated tests and `:util:integrationTest` for process/service boundaries.
  Packaging/update changes also need the full Windows and Ubuntu root checks.
