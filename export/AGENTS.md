# File export instructions

- Use `dev.voxelbrew.exporter`; keep format writers, export-view adapters/selection, provider discovery and file publication here.
- Keep CAD domain values in engine and exporter/slicer plugin contracts in api/export.
- Own .jcad writing, STL/3MF writers and the external-slicer output pipeline.
- Validate before publishing; failed operations must preserve existing destinations.
- Never export failed or rolled-back geometry. Preserve units, identities and transforms.
- Use import readers to independently validate document and G-code files where required.
- Run real-kernel/exporter/slicer integration tests, then full Windows and Ubuntu root checks.
