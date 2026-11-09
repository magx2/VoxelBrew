# File import instructions

- Use `dev.voxelbrew.importer` (Java reserves the word `import`).
- Own bounded file reading, importer discovery, .vxb and G-code providers; return engine domain values.
- Put only public importer contracts in api/import; keep concrete readers and ServiceLoader registrations here.
- Keep readers headless and independent of export, CLI and JavaFX.
- Preserve .vxb schema compatibility, opaque plugin payloads and corruption/newer-schema diagnostics.
- Do not reinterpret a mesh import as editable CAD history or claim unsupported formats work.
- Unit tests use temporary files; full root checks must pass on Windows and Ubuntu WSL.
