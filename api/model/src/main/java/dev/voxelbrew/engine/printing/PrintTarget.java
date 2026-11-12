package dev.voxelbrew.engine.printing;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Destination selected when a plate is sliced. A profile-only job cannot be uploaded. */
public final class PrintTarget {
    public static final String PRINTER_ID = "print.target.printer";

    private PrintTarget() {}

    public static PrintArtifact attach(PrintArtifact artifact, java.util.List<String> lines, PrintSetup setup) {
        String printerId = setup.workspaceSettings().getOrDefault(PRINTER_ID, "");
        Map<String, String> metadata = new LinkedHashMap<>(artifact == null ? Map.of() : artifact.metadata());
        metadata.put("target_kind", printerId.isBlank() ? "profile" : "printer");
        metadata.put("target_id", printerId.isBlank() ? setup.printerId() : printerId);
        metadata.put("target_profile", setup.printerId());
        return PrintArtifact.of(
                artifact == null ? "gcode" : artifact.format(),
                artifact == null ? String.join("\n", lines).getBytes(StandardCharsets.UTF_8) : artifact.bytes(),
                metadata);
    }
}
