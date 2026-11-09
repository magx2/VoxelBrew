package dev.voxelbrew.cli;

import dev.voxelbrew.engine.model.BodyStyle;
import dev.voxelbrew.exporter.DocumentOutput;
import dev.voxelbrew.importer.api.DocumentSource;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "body-style",
        mixinStandardHelpOptions = true,
        description = "List or assign CAD body material and color; independent of slicer settings.")
final class BodyStyleCommand extends VoxelBrew.FileCommand {
    @javax.inject.Inject
    BodyStyleCommand(DocumentSource documents, DocumentOutput documentWriter) {
        super(documents, documentWriter);
    }

    @Option(names = "--body", description = "Stable body ID from inspect, e.g. extrude.body")
    String body;

    @Option(names = "--material", description = "PLA, PETG or a custom material label; empty clears it")
    String material;

    @Option(names = "--color", description = "Opaque sRGB color, e.g. #FF0000")
    String color;

    @Option(names = "--reset", description = "Remove the override and inherit the source/default style")
    boolean reset;

    public Integer call() throws Exception {
        boolean editing = reset || material != null || color != null;
        if (editing && body == null) throw new IllegalArgumentException("Assignments require --body");
        if (reset && (material != null || color != null))
            throw new IllegalArgumentException("--reset cannot be combined with --material or --color");
        var session = session();
        var document = session.document();
        if (editing && document.cursor() != document.features().size())
            throw new IllegalArgumentException("Return to the timeline end before assigning a body style");
        java.util.List<String> bodies;
        try (var evaluation = session.evaluate(pluginDirectory)) {
            if (!evaluation.valid())
                throw new IllegalArgumentException("Rebuild the model successfully before using body-style");
            bodies = evaluation.bodies().stream().map(b -> b.id()).toList();
        }
        if (body != null && !bodies.contains(body)) throw new IllegalArgumentException("Unknown active body: " + body);
        if (editing) {
            var previous = document.bodyStyle(body);
            session.setBodyStyle(
                    body,
                    reset
                            ? null
                            : new BodyStyle(
                                    material == null ? previous.material() : material,
                                    color == null ? previous.color() : color));
            int result = save(session);
            if (result != 0) return result;
        }
        for (var id : bodies) {
            if (body != null && !body.equals(id)) continue;
            var style = session.document().bodyStyle(id);
            spec.commandLine().getOut().printf("%s\t%s\t%s%n", id, style.material(), style.color());
        }
        return 0;
    }
}
