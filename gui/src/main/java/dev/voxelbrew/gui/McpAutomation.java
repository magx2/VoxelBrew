package dev.voxelbrew.gui;

import com.google.gson.Gson;
import dev.voxelbrew.engine.model.Feature;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;

/** All access to the live window is confined to its JavaFX owner thread. */
final class McpAutomation {
    private final DocumentController document;
    private BooleanSupplier busy;
    private final Gson json = new Gson();
    private final java.util.function.Consumer<Runnable> dispatch;

    @javax.inject.Inject
    McpAutomation(
            DocumentController document, @javax.inject.Named("ui") java.util.function.Consumer<Runnable> dispatch) {
        this(document, () -> true, dispatch);
    }

    void busy(BooleanSupplier value) {
        busy = value;
    }

    McpAutomation(DocumentController document, BooleanSupplier busy) {
        this(document, busy, Platform::runLater);
    }

    McpAutomation(DocumentController document, BooleanSupplier busy, java.util.function.Consumer<Runnable> dispatch) {
        this.document = document;
        this.busy = busy;
        this.dispatch = dispatch;
    }

    CompletableFuture<Map<String, Object>> call(String name, Map<String, Object> args) {
        var result = new CompletableFuture<Map<String, Object>>();
        dispatch.accept(() -> {
            try {
                if (result.isCancelled()) return;
                if (!name.equals("document_inspect")) {
                    if (busy.getAsBoolean() || document.ioBusy() || document.hasPreview())
                        throw new IllegalStateException("busy: finish or cancel the active editor first");
                    var current = document.snapshot();
                    if (!text(args, "documentId").equals(current.document().id())
                            || integer(args, "revision") != current.revision()
                            || integer(args, "generation") != document.generation())
                        throw new IllegalStateException("conflict: inspect the document again");
                }
                CompletableFuture<?> pending = null;
                switch (name) {
                    case "document_inspect" -> {}
                    case "history_undo" -> document.undo();
                    case "history_redo" -> document.redo();
                    case "history_cursor" ->
                        document.cursor(number(args, "position").intValue());
                    case "feature_edit" ->
                        document.edit(
                                text(args, "id"),
                                text(args, "field"),
                                number(args, "value").doubleValue());
                    case "feature_expression" ->
                        document.expression(text(args, "id"), text(args, "field"), text(args, "expression"));
                    case "feature_remove" ->
                        document.deleteFeatures(
                                number(args, "from").intValue(),
                                number(args, "to").intValue());
                    case "feature_add" -> document.add(feature(args));
                    case "feature_replace" -> document.replaceFeature(feature(args));
                    case "sketch_rectangle" ->
                        document.add(new Feature.Sketch(
                                text(args, "id"),
                                text(args, "id") + ".profile",
                                Feature.Plane.origin(text(args, "plane")),
                                new Feature.Rectangle(
                                        number(args, "x").doubleValue(),
                                        number(args, "y").doubleValue(),
                                        number(args, "width").doubleValue(),
                                        number(args, "height").doubleValue())));
                    case "sketch_circle" ->
                        document.add(new Feature.Sketch(
                                text(args, "id"),
                                text(args, "id") + ".profile",
                                Feature.Plane.origin(text(args, "plane")),
                                new Feature.Circle(
                                        number(args, "x").doubleValue(),
                                        number(args, "y").doubleValue(),
                                        number(args, "diameter").doubleValue())));
                    case "configuration_activate" -> document.configuration(text(args, "name"));
                    case "document_new" -> {
                        requireDiscard(args);
                        document.newDocument();
                    }
                    case "document_open" -> {
                        requireDiscard(args);
                        pending = document.open(Path.of(text(args, "path")));
                    }
                    case "document_save" ->
                        pending =
                                document.save(Path.of(text(args, "path")), Boolean.TRUE.equals(args.get("overwrite")));
                    default -> throw new IllegalArgumentException("Unknown tool");
                }
                if (pending == null) result.complete(state());
                else
                    pending.whenComplete((ignored, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else result.complete(state());
                    });
            } catch (Exception failure) {
                result.completeExceptionally(failure);
            }
        });
        return result;
    }

    private void requireDiscard(Map<String, Object> args) {
        if (document.dirty() && !Boolean.TRUE.equals(args.get("discardUnsaved")))
            throw new IllegalStateException("Unsaved changes: supply discardUnsaved explicitly");
    }

    private Feature feature(Map<String, Object> args) {
        Class<? extends Feature> type = switch (text(args, "type")) {
            case "extrude" -> Feature.Extrude.class;
            case "cut" -> Feature.ThroughCut.class;
            case "split" -> Feature.SplitBody.class;
            case "edgeFinish" -> Feature.EdgeFinish.class;
            case "shell" -> Feature.Shell.class;
            default -> throw new IllegalArgumentException("Unsupported feature type");
        };
        return json.fromJson(json.toJson(args.get("definition")), type);
    }

    private Map<String, Object> state() {
        var snapshot = document.snapshot();
        var state = new LinkedHashMap<String, Object>();
        state.put("documentId", snapshot.document().id());
        state.put("revision", snapshot.revision());
        state.put("generation", document.generation());
        state.put("document", json.fromJson(json.toJson(snapshot.document()), Object.class));
        state.put("dirty", document.dirty());
        state.put("rebuilding", document.rebuilding());
        state.put("valid", !document.rebuilding() && document.build().valid());
        state.put("diagnostics", document.build().diagnostics());
        state.put("error", document.error());
        return state;
    }

    private static String text(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof String value) || value.isBlank())
            throw new IllegalArgumentException("Missing " + key);
        return value;
    }

    private static Number number(Map<String, Object> args, String key) {
        if (!(args.get(key) instanceof Number value) || !Double.isFinite(value.doubleValue()))
            throw new IllegalArgumentException("Invalid " + key);
        return value;
    }

    private static long integer(Map<String, Object> args, String key) {
        try {
            return new java.math.BigDecimal(number(args, key).toString()).longValueExact();
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid integer " + key, failure);
        }
    }
}
