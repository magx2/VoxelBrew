package dev.voxelbrew.engine.sketch;

import static dev.voxelbrew.engine.sketch.SketchGeometry.*;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.util.*;
import lombok.NonNull;

/** Headless font outlines, persisted so reopening never depends on installed fonts. */
public final class SketchText {
    private SketchText() {}

    public record Glyph(double advance, List<List<Entity>> contours) {
        public Glyph {
            if (!Double.isFinite(advance) || advance < -1) throw new IllegalArgumentException("Invalid glyph advance");
            contours = contours.stream().map(List::copyOf).toList();
            if (contours.stream().mapToInt(List::size).sum() > 512)
                throw new IllegalArgumentException("Glyph is too complex");
            for (var contour : contours)
                for (var curve : contour)
                    if (curve.kind() != Kind.LINE && curve.kind() != Kind.SPLINE)
                        throw new IllegalArgumentException("Glyph outlines must contain lines and cubic curves");
        }
    }

    public record Data(
            @NonNull String content,
            @NonNull String font,
            int style,
            @NonNull String pathId,
            List<Glyph> glyphs) {
        public Data {

            glyphs = List.copyOf(glyphs);
            if (content.isBlank()
                    || content.codePointCount(0, content.length()) > 32
                    || glyphs.size() != content.codePointCount(0, content.length())
                    || style < 0
                    || style > 3)
                throw new IllegalArgumentException("Text needs 1–32 characters and valid glyph outlines");
            if (!pathId.isEmpty()) dev.voxelbrew.engine.model.CadDocument.identifier(pathId, "text path ID");
        }
    }

    public static Entity create(
            String id,
            String content,
            String fontName,
            int style,
            double x,
            double y,
            double height,
            double angle,
            double spacing,
            String pathId,
            boolean construction) {
        if (content.isBlank() || content.codePointCount(0, content.length()) > 32)
            throw new IllegalArgumentException("Text needs 1–32 characters");
        var font = new Font(fontName, style, 1000);
        if (!fontName.equalsIgnoreCase(font.getFamily())
                && !Set.of("SansSerif", "Serif", "Monospaced", "Dialog", "DialogInput")
                        .contains(fontName)) throw new IllegalArgumentException("Font is not installed: " + fontName);
        var context = new FontRenderContext(new AffineTransform(), true, true);
        var glyphs = new ArrayList<Glyph>();
        for (int code : content.codePoints().toArray()) {
            if (code == '\n') {
                glyphs.add(new Glyph(-1, List.of()));
                continue;
            }
            if (!font.canDisplay(code))
                throw new IllegalArgumentException("The font cannot display U+" + Integer.toHexString(code));
            var vector = font.createGlyphVector(context, new String(Character.toChars(code)));
            var iterator = vector.getOutline().getPathIterator(null);
            var contours = new ArrayList<List<Entity>>();
            var current = new ArrayList<Entity>();
            Vec start = null, previous = null;
            double[] coordinates = new double[6];
            int index = 0;
            while (!iterator.isDone()) {
                int kind = iterator.currentSegment(coordinates);
                var next = new Vec(coordinates[0], -coordinates[1]);
                if (kind == PathIterator.SEG_MOVETO) {
                    current = new ArrayList<>();
                    contours.add(current);
                    previous = start = next;
                } else if (kind == PathIterator.SEG_CLOSE) {
                    if (previous.distance(start) > 1e-8) current.add(line("g" + index++, previous, start));
                    previous = start;
                } else if (kind == PathIterator.SEG_LINETO) {
                    if (previous.distance(next) > 1e-8) current.add(line("g" + index++, previous, next));
                    previous = next;
                } else {
                    Vec a = next, b, end;
                    if (kind == PathIterator.SEG_QUADTO) {
                        end = new Vec(coordinates[2], -coordinates[3]);
                        b = end.mix(a, 2.0 / 3);
                        a = previous.mix(a, 2.0 / 3);
                    } else {
                        b = new Vec(coordinates[2], -coordinates[3]);
                        end = new Vec(coordinates[4], -coordinates[5]);
                    }
                    current.add(SketchGeometry.entity(
                            "g" + index++,
                            Kind.SPLINE,
                            false,
                            previous.x(),
                            previous.y(),
                            a.x(),
                            a.y(),
                            b.x(),
                            b.y(),
                            end.x(),
                            end.y()));
                    previous = end;
                }
                iterator.next();
            }
            glyphs.add(new Glyph(
                    vector.getGlyphPosition(vector.getNumGlyphs()).getX(),
                    contours.stream().filter(c -> !c.isEmpty()).toList()));
        }
        return new Entity(
                id,
                Kind.TEXT,
                List.of(x, y, height, angle, spacing),
                construction,
                0,
                false,
                new Data(content, fontName, style, pathId, glyphs));
    }

    private static Entity line(String id, Vec a, Vec b) {
        return SketchGeometry.entity(id, Kind.LINE, false, a.x(), a.y(), b.x(), b.y());
    }

    public static List<Entity> curves(SketchGeometry geometry, Entity text) {
        var data = text.text();
        if (data == null) return List.of(text);
        Entity path = data.pathId().isEmpty() ? null : geometry.entity(data.pathId());
        if (path != null && (path.kind() == Kind.TEXT || path.kind() == Kind.POINT))
            throw new IllegalArgumentException("Text path must be a curve");
        var result = new ArrayList<Entity>();
        double scale = text.value(2) / 1000, x = 0, y = 0;
        int glyphIndex = -1;
        for (var glyph : data.glyphs()) {
            glyphIndex++;
            int index = 0;
            if (glyph.advance() < 0) {
                x = 0;
                y -= 1200;
                continue;
            }
            double angle = text.value(3);
            Vec origin = new Vec(text.value(0), text.value(1));
            double localX = x, localY = y;
            if (path != null) {
                // Check both advance endpoints, even when the glyph center still fits.
                parameterAtDistance(path, x * scale + text.value(0));
                parameterAtDistance(path, (x + glyph.advance()) * scale + text.value(0));
                double center = (x + glyph.advance() / 2) * scale + text.value(0);
                double t = parameterAtDistance(path, center);
                var tangent = path.derivative(t).unit();
                origin = path.at(t).plus(tangent.normal().times(text.value(1)));
                angle += Math.atan2(tangent.y(), tangent.x());
                localX = -glyph.advance() / 2;
            }
            for (var contour : glyph.contours())
                for (var curve : contour) {
                    var values = new ArrayList<Double>();
                    for (int i = 0; i < curve.values().size(); i += 2) {
                        var p = SketchCurves.rotate(
                                        new Vec(curve.value(i) + localX, curve.value(i + 1) + localY).times(scale),
                                        angle)
                                .plus(origin);
                        values.add(p.x());
                        values.add(p.y());
                    }
                    result.add(new Entity(
                            SketchGeometry.derivedId(text.id(), "-glyph-" + glyphIndex + "-outline-" + index++),
                            curve.kind(),
                            values,
                            text.construction()));
                }
            x += glyph.advance() + text.value(4) * 1000;
        }
        return List.copyOf(result);
    }

    private static double parameterAtDistance(Entity curve, double distance) {
        var points = curve.sample();
        double length = 0;
        for (int i = 1; i < points.size(); i++) length += points.get(i - 1).distance(points.get(i));
        if (curve.loop()) distance = ((distance % length) + length) % length;
        if (distance < 0 || distance > length)
            throw new IllegalArgumentException("Text extends beyond its path; reduce height, spacing or offset");
        for (int i = 1; i < points.size(); i++) {
            double span = points.get(i - 1).distance(points.get(i));
            if (distance <= span) return (i - 1 + (span == 0 ? 0 : distance / span)) / (points.size() - 1);
            distance -= span;
        }
        return 1;
    }
}
