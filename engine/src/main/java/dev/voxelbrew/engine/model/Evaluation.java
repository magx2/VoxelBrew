package dev.voxelbrew.engine.model;

import dev.voxelbrew.engine.geometry.Solid;
import java.util.List;

/** Owns all native replay results. Callers must close the evaluation, not its borrowed solids. */
public final class Evaluation implements AutoCloseable {
    public enum State {
        OK,
        FAILED,
        BLOCKED,
        ROLLED_BACK
    }

    public record Diagnostic(String featureId, State state, String message) {}

    public record Body(String id, Feature.BodyRef output, Solid solid) {}

    private final CadDocument document;
    private final List<Diagnostic> diagnostics;
    private final List<Body> bodies;
    private final List<Solid> owned;
    private final List<Feature> resolvedFeatures;
    private boolean closed;

    Evaluation(CadDocument document, List<Diagnostic> diagnostics, List<Body> bodies, List<Solid> owned) {
        this(document, diagnostics, bodies, owned, document.features());
    }

    Evaluation(
            CadDocument document,
            List<Diagnostic> diagnostics,
            List<Body> bodies,
            List<Solid> owned,
            List<Feature> resolvedFeatures) {
        this.document = document;
        this.diagnostics = List.copyOf(diagnostics);
        this.bodies = List.copyOf(bodies);
        this.owned = List.copyOf(owned);
        this.resolvedFeatures = List.copyOf(resolvedFeatures);
    }

    /**
     * Detached numeric definitions for the evaluated revision; expressions remain in the source
     * document.
     */
    public List<Feature> resolvedFeatures() {
        return resolvedFeatures;
    }

    public List<Diagnostic> diagnostics() {
        return diagnostics;
    }

    public boolean valid() {
        return diagnostics.stream().noneMatch(d -> d.state() == State.FAILED || d.state() == State.BLOCKED);
    }

    public List<Body> bodies() {
        requireOpen();
        if (!valid()) {
            throw new IllegalStateException("Rebuild failed; partial or stale geometry is unavailable. " + diagnostics);
        }
        return bodies;
    }

    /**
     * Required by full-part export: a rolled-back or broken document cannot be exported as
     * complete.
     */
    public List<Body> requireCompleteDocument() {
        if (document.cursor() != document.features().size()) {
            throw new IllegalStateException("Return timeline cursor to the end before full-part export");
        }
        return bodies();
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            owned.forEach(Solid::close);
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Evaluation is closed");
        }
    }
}
