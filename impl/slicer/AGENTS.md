# Slicer implementation instructions

- Own backend discovery, configuration, process execution and atomic job publication under `dev.voxelbrew.slicer`.
- Keep public plugin contracts in api/slicer. Depend on file I/O implementations, never the reverse.
- Keep Prusa project serialization in impl/exporter. Use exporter providers for all backend input.
- Keep configuration isolated by provider ID, preserve destinations on cancellation/failure, and close plugin loaders.
- Real backend qualification requires real executables; process doubles only validate orchestration.
