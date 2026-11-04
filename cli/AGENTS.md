# CLI instructions

- Read `docs/editable-workflow.md` and the public reference scripts under `scripts/`.
- Use picocli under `dev.voxelbrew.cli`. Delegate modeling, history, persistence,
  export, and slicing to engine services instead of duplicating their logic.
- Preserve useful diagnostics and nonzero failure exit codes. Reject invalid
  inputs before publishing output; failed commands must not corrupt documents.
- Keep output usable in scripts. Check save/reopen/edit workflows across fresh
  JVM processes, including native loading and paths containing spaces.
- Exercise public commands through the installed distribution when validating
  distribution behavior. Use `:cli:installDist`; the development distribution
  still requires Java 21 and must not be described as a bundled-JVM installer.
- Keep `scripts/reference-part.ps1` and `scripts/reference-part.sh` equivalent.
  Use a fresh destination for demos; preserve their overwrite protection.
- Use `:cli:test` for focused feedback and the full root check for milestone gates.
  Changes to launchers, native loading, or process invocation need Windows and
  Ubuntu checks. Use argument lists for external processes, not shell interpolation.
