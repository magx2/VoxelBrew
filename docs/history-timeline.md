# Compact modeling history

The bottom history strip shows one icon per modeling operation. Sketches use a
pencil, extrusions a raised cube, cuts a pierced cube, splits a divided cube and
plugin features a cube with a plus. Hover an icon for its operation number,
translated name, evaluation state and any rebuild diagnostic. Click it to inspect
or edit its dimensions; the tree and viewport selection follow the same feature.

The vertical marker separates evaluated operations from the faded future history.
Drag it between icons to roll back. With the marker focused, use Left/Right to
step and Home/End to jump to the beginning/end. The controls at the left provide
the same first/previous/next/last navigation. Return to the end before adding
features or exporting. Rolling back does not create an undo transaction.

Play evaluates operations in order, waiting for each background rebuild. Starting
at the end replays from the beginning. Click Pause to stop at the current position.
Manual history navigation, feature selection, editing, modal dialogs, changing
documents and closing the window stop playback. A failed rebuild also stops it;
the failed operation stays editable and no stale geometry becomes actionable.

Long histories scroll horizontally using the scrollbar or mouse wheel. Navigation
keeps the marker visible. Light and dark appearances use the same layout, and all
controls and help are available in English and Polish.

Run the development GUI with `gradlew.bat :gui:run` on Windows or
`./gradlew :gui:run` on Ubuntu. See [qualification](acceptance/history-timeline.md)
for the real-window regression checks.
