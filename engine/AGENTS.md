# Engine instructions

- Read `docs/native-design.md` and `docs/editable-workflow.md` for existing
  ownership, evaluation, persistence, and failure behavior.
- Keep units in millimeters and model values in double precision. Validate finite
  dimensions and references with feature-specific diagnostics at public entry points.
- Preserve ordered feature definitions, stable IDs, references to earlier named
  outputs, and the separation between timeline rollback and undo/redo transactions.
- Failed rebuilds must preserve editable definitions and identify failed/blocked
  features. Do not publish or export geometry from a stale successful revision.
- Every returned owning `Solid` must be closed. `Evaluation` owns its borrowed
  body solids; callers must respect that lifetime. Preserve immutable independent
  results, registry-token handles, close safety, and translated OCCT/C++ failures.
- Keep JNI narrow and validate actual kernel results. Changes to native binaries
  must invalidate relevant Gradle tests, even when Java sources are unchanged.
- Depend on the MIT api/model values; keep native evaluation and persistence codecs here.
- Keep production code independent of the import/export modules. Cross-module integration
  tests may use their public readers and writers; export-specific tests belong in export.
- Evolve `src/main/proto/document.proto` and the in-memory `DocumentCodec` compatibly: reserve retired field numbers,
  never hand-edit generated code, and protect newer/unknown documents from
  destructive writes. File access and atomic publication belong to import/export.
- For plugins, keep feature contracts here and file-I/O contracts in api/import and api/export.
  Follow the versioned APIs, dedicated class
  loader, ServiceLoader registry, compatibility diagnostics, and independent
  consumer fixture. Keep tessellation tolerance an explicit option.
- Use `:engine:test` for isolated unit tests, `:engine:integrationTest` for
  real-kernel/service integration, and `:engine:nativeIntegrationTest` for the native subset;
  neither replaces the full root milestone check. Verify native changes on both
  Windows and Ubuntu using each platform's native libraries.
