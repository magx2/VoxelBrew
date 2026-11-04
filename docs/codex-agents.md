# Codex agents for VoxelBrew

The repository provides four custom Codex roles in `.codex/agents/`:

| Agent | Assigned work |
|---|---|
| `cad_engine` | Engine model, OCCT/JNI, history, persistence, export and plugin API |
| `cad_cli` | picocli commands, public headless workflows, scripts and distribution |
| `cad_gui` | JavaFX viewport, sketch interaction, timeline, properties and dialogs |
| `cad_validation` | Code review, acceptance evidence, Windows and Ubuntu WSL checks |

Each definition uses the official `name`, `description`, and
`developer_instructions` TOML fields. Models and reasoning effort inherit the
parent session's settings; these files do not change global Codex configuration.
Agents also inherit the parent session's permissions. The validation role's
instruction to avoid source edits is a working convention, not a separate sandbox.

Start a fresh Codex session in this repository after adding or changing agent
instructions. Request delegation explicitly, for example:

> Use cad_engine to implement the next engine task in the current milestone, and
> cad_cli for the corresponding commands after agreeing on the engine API. Give
> them separate file ownership. After integration, have cad_validation review
> the diff and run the full checks on Windows and Ubuntu WSL.

For a review without implementation:

> Ask cad_validation to review the current diff against the implementation plan
> and report concrete issues and missing acceptance evidence.

The parent coordinates work, integrates changes, and owns final milestone checks
and commits. Do not run multiple builds in the same checkout or validate a tree
while workers are editing it. Ubuntu WSL must use its own filesystem checkout and
native build output. See [README](../README.md) for bootstrap and check commands.

[Root instructions](../AGENTS.md) apply project-wide. Module-specific instructions
live in [engine](../engine/AGENTS.md), [cli](../cli/AGENTS.md), and
[gui](../gui/AGENTS.md). These retain the implementation plan's sequential gates;
adding roles alone does not complete or begin a CAD milestone.

Configuration reference: [Codex custom agents](https://learn.chatgpt.com/docs/agent-configuration/subagents)
and [AGENTS.md discovery](https://learn.chatgpt.com/docs/agent-configuration/agents-md).
