# VoxelBrew application icon

The blue and teal extruded V on a dark rounded tile is shared by JavaFX windows
and native GUI launchers. Transparent corners and several resolutions keep it
usable on light and dark desktops, including small title-bar icons.

Checked-in artwork and derived assets:

- Original artwork: `gui/src/main/branding/voxelbrew.png`.
- JavaFX PNGs: `gui/src/main/resources/dev/voxelbrew/gui/icons/voxelbrew-{size}.png`
  for 16, 32, 48, 64, 128, 256 and 512 pixels.
- Windows launcher: `gui/src/main/packaging/voxelbrew.ico` (16 through 256 pixels).
- Linux launcher: the 512-pixel JavaFX PNG.
- macOS bundle: `gui/src/main/packaging/voxelbrew.icns` (16 through 1024 pixels).
  This asset does not qualify macOS GUI operation, which remains deferred.

To regenerate the derived assets, run from the repository root with Java 21:

```text
java -Djava.awt.headless=true scripts/GenerateAppIcons.java
```

The converter uses the JDK's image library to resize the original and create icon
containers. Builds consume the committed files and need no image service or
additional image tools. `:gui:appImage` tracks the platform icon as an input.

## Artwork provenance

Created with the built-in image-generation tool, with transparent output enabled.
The final generation prompt was:

> Use case: logo-brand. Create one production-ready desktop application icon for VoxelBrew, a precise parametric 3D CAD application. Square 1024 x 1024 composition. A bold sculptural V formed from two joined solid voxel beams, viewed in a restrained isometric perspective so the top and side faces communicate CAD extrusion. The V is the single recognizable silhouette, with broad flat faces, crisp geometry and subtle bevels. Main faces vivid blue and teal, small lighter cyan top faces, darker blue extrusion sides. Place this one large centered mark on a deep navy rounded-square tile with restrained corner rounding and generous even padding, clean and modern like a professional desktop development tool. High contrast at 16 and 32 pixels; few large shapes, no fine details. Very subtle lighting for depth, no glass effects, no glossy toy plastic, no wires, no decorative grid, no coffee cup, no text or wordmark, no extra letters, no watermark, no scene or mockup. Tile fills about 88 percent of canvas; outside the tile is genuinely transparent, including corners. One icon only.
