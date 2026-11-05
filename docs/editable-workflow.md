# M1 editable CAD and headless workflow

Build with the README's native bootstrap followed by `./gradlew :cli:installDist`
(Windows: `.\gradlew.bat :cli:installDist`). The launcher is
`cli/build/install/cli/bin/cli` (`cli.bat` on Windows). It finds the adjacent
native libraries automatically. `cli --help` and each command's `--help` list
the options. All coordinates are design-space millimeters; all dimensions are
double precision. No export command exists before M2.

## Public commands

```bash
cli new plate.jcad
cli rectangle plate.jcad --id plate --width 60 --height 40
cli extrude plate.jcad --id thickness --sketch plate --distance 5
cli circle plate.jcad --id hole --center-x 10 --center-y 10 --diameter 4
cli cut plate.jcad --id cut --sketch hole --body-feature thickness
cli inspect plate.jcad --point 10,10,2.5
cli edit plate.jcad --feature thickness --field distance --value 8
cli inspect plate.jcad --point 10,10,4
cli cursor plate.jcad --position 3
cli inspect plate.jcad
cli cursor plate.jcad --position 4
cli history plate.jcad
```

The scripts in `scripts/reference-part.ps1` and `scripts/reference-part.sh` run
this sequence. Each invocation is a separate process and reopens the document.
CLI errors return 1, invalid command syntax returns 2, success returns 0. Invalid
numeric edits leave the previous file byte-for-byte unchanged. A valid edit that
fails geometry replay is saved as an editable definition and returns diagnostics
with a nonzero exit code. `history` remains available for repair; `inspect`
refuses to expose partial/stale solids while the active history is broken.

Rectangles accept `--x`, `--y`, `--width`, `--height`; circles accept center and
diameter. Both accept `--offset-z`, `--plane-id`, `--profile-id` and `--id`.
Both profiles can be extruded as new bodies, with signed distances. The M1 cut
operation is a circular through-all subtraction across the complete current Z
extent, normal to the XY-parallel sketch plane. M7 extends sketches with
[geometric constraints and general closed profiles](sketch-constraints.md),
including through cuts. Arbitrary planes and generated-face supports remain
outside the current sketch workflow.

Dimension fields: `width`, `height`, `x`, `y`, `centerX`, `centerY`, `diameter`,
`distance`, `offsetZ`. A shared offset-plane edit updates all sketches with that
plane ID in one transaction. `origin-xy` is fixed at zero. New offset planes have
their own stable IDs. Conflicting definitions of one plane ID fail replay.

## Identity, history and failure behavior

The persistent graph stores an ordered list of immutable definitions, a document
ID, stable feature/profile/plane IDs, explicit earlier-input references and a
cursor. Profile references contain both sketch and profile IDs. Solids are named
`<feature>/body`; new body identities are `<extrusion>.body`. A cut retains the
target body identity and publishes its own named output. An edit never recreates
feature IDs. Body results and meshes are disposable, not serialized.

`ModelSession` is the frontend-neutral transaction boundary. It supports `add`,
`replace`, `editDimension`, `setCursor`, `undo`, `redo`, and `evaluate`.
Undo/redo retain feature definitions only; cursor changes never become undo
transactions. A cursor at the end follows the end when adding/removing a feature;
an explicitly rolled-back cursor stays at its position (clamped if needed).
Undo history is in-memory session state and is not preserved across processes.
Adding while rolled back requires returning to the end first.

Evaluation fully replays the prefix, reporting `OK`, `FAILED`, `BLOCKED`, and
`ROLLED_BACK` for every feature. Missing/forward inputs fail at their owner;
dependents become blocked. A failed evaluation retains definitions but refuses
geometry access. `Evaluation.requireCompleteDocument()` additionally rejects
rollback, providing the full-part export guard for M2. Evaluations own and close
their native results; borrowed body solids are valid only for that evaluation.

Repair a broken reference without duplicating features:

```bash
cli history broken.jcad
cli repair broken.jcad --feature thickness --sketch plate
cli repair broken.jcad --feature cut --body-feature thickness
cli inspect broken.jcad
```

## .jcad schema and safe writes

`engine/src/main/proto/document.proto` is schema version 5 (versions 1–4 remain readable). The binary envelope
contains eight magic bytes (`JCAD`, CR, LF, 0x1A, LF), a big-endian 32-bit payload
length, a SHA-256 digest and the Protobuf message. The 16 MiB payload limit, exact
length check and digest distinguish truncated/corrupt documents from an empty
valid model. Field numbers are reserved for compatibility; removed tags must
never be reused. Units must explicitly be `MILLIMETERS`.

A newer schema or any unsupported Protobuf field is rejected as read-only. M1
does not interpret or rewrite unknown data; the original bytes remain intact.
Version 2 adds editable splitting and versioned print setups. Version 3 adds
provider ID, feature type, payload version and opaque custom-feature bytes.
Missing plugins and unsupported provider payload versions prevent evaluation but
preserve the complete definition on save. The provider owns the byte encoding;
the document reader never interprets it. See [printing](printing-workflow.md) and
[feature plugins](feature-plugins.md).

Saving serializes to a sibling temporary file, flushes its contents and validates
the resulting message before publication. Replacing an existing supported file
requires an explicit overwrite policy and atomic rename. Creating a new file
uses an atomic hard link to the completed sibling, which cannot overwrite a
concurrently created destination. The temporary sibling is removed afterward.
Filesystems without the required atomic rename/hard-link support fail safely;
Windows NTFS and Ubuntu WSL's ext4 were exercised. There is no multi-user editing
protocol: callers must serialize edits to the same existing document.

`new` requires `--overwrite` to replace an existing supported document. Normal
edit commands load the specified document and deliberately save that edit over
it. Newer or damaged destination files are never overwritten even with the flag.

## Current boundary

M1 provides headless editing and a development CLI distribution. M2 adds export
contracts and external plugins, M3 supplies JavaFX gestures, and M4 adds print
setups, slicing and supports. GUI parity, face naming, packaging, mesh validation
and physical printing have not been accepted in this increment.
