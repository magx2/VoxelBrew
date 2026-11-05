# VoxelBrew contributor instructions

## Read before changing code

- Read `README.md`, `docs/implementation-plan.md`, and the acceptance evidence for
  the milestone being changed under `docs/acceptance/`.
- The implementation plan defines the backlog, exit criteria, and milestone
  order. Check actual code and evidence before assuming a milestone is complete.
  Deliver a runnable increment; do not skip a gate to begin a later milestone.
- Read the `AGENTS.md` in each module you change, even when working from the root.
- Preserve existing user changes and unrelated files. Keep generated output,
  downloaded toolchains, and local caches out of source control.

## Architecture

- Use Java 21, the committed Gradle wrapper, and `dev.voxelbrew` packages.
- Keep six first-party subprojects: `util`, `engine`, `import`, `export`, `cli`, and `gui`.
  Engine owns CAD/modeling, manufacturing models, the in-memory document schema and plugin contracts.
  Import/export own file readers, writers, provider discovery and slicer file pipelines.
  Production dependencies point from file I/O to engine, never from engine to file I/O.
  Util owns CAD-independent application services and depends on none of the other projects.
- Keep the GUI a JavaFX desktop application. Headless engine and CLI workflows
  must not require JavaFX, a display server, or a GPU.
- Keep JavaFX, CLI parsing, generated Protobuf classes, and native pointers out
  of public plugin contracts. Put example plugins in independent consumer builds
  or test fixtures, rather than adding another application subproject.
- Respect the dependency pins in the build and `scripts/native-versions.properties`.
  Keep native redistribution notices with redistributed libraries.

## Build and verification

- Follow the bootstrap and build commands in `README.md`. Windows uses
  `gradlew.bat`; Ubuntu uses `./gradlew` and its own Linux native toolchain.
- Validate implementation changes on both Windows and Ubuntu WSL. Use a separate
  Ubuntu filesystem checkout with the same source revision. Never share native
  build output or run concurrent Gradle builds in the same checkout.
- Before completing any milestone, run the full root `gradlew.bat check` and
  `./gradlew check` without task exclusions. Fix failures and rerun the full check
  after any further milestone changes. Do not edit sources during a gate run.
- Review and stage only the milestone changes, then commit with the milestone ID
  and outcome in the subject before starting the next milestone. Report the
  commit, exact commands, results, artifact paths, and pending checks at handoff.
- Native acceptance requires real OCCT; export acceptance requires independent
  format readers; slicing acceptance requires the real slicer. Test doubles can
  exercise failure handling, but cannot satisfy these release gates.
- Distinguish automated results, manual checks, failures, and untested behavior.
  Headless tests do not qualify GUI gestures, installers, macOS, or hardware.

## Reference part

Use the plan's 60 x 40 mm plate with a diameter-4 mm through-hole at (10, 10).
Save and reopen the editable 5 mm part, then change thickness to 8 mm. Required
bounds are (0, 0, 0) to (60, 40, 8) mm; exact volume is
19099.469035085127 mm^3 within 0.01 mm^3, with bounds within 0.001 mm.
Rollback before the cut must give 19200 mm^3. Use a separately documented
tolerance for mesh measurements. Keep the public CLI reference scripts working.

## Working with Codex agents

Project roles are defined in `.codex/agents/`; usage is in `docs/codex-agents.md`.
Use them when the user requests agents or delegated/parallel work. Do not launch
workers merely because their definitions exist.

Before delegating, assign a bounded task, owned files, expected public behavior,
and validation criteria. Assign shared files to one owner; coordinate API changes
before concurrent frontend work. Workers report changes and test evidence to the
parent and do not stage or commit. The parent integrates, resolves conflicts,
coordinates full checks, and owns milestone acceptance and commits. Run validation
against a stable integrated source tree after implementation workers have stopped
editing it. Instruction-only maintenance does not complete a CAD milestone.
