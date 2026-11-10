package dev.voxelbrew.gui;

import dev.voxelbrew.slicer.api.PrinterCatalog;
import dev.voxelbrew.util.config.UserConfiguration;
import java.io.IOException;
import java.util.*;

/** User preference only: hidden presets remain available to saved projects and exports. */
final class PrinterVisibility {
    private static final String KEY = "printers.hiddenDefaults";
    private final UserConfiguration configuration;

    @javax.inject.Inject
    PrinterVisibility(UserConfiguration configuration) {
        this.configuration = configuration;
    }

    Set<String> hidden() throws IOException {
        var ids = new HashSet<>(Arrays.asList(configuration.get(KEY, "").split(",")));
        ids.remove("");
        return Set.copyOf(ids);
    }

    void saveVisible(List<PrinterCatalog.Entry> entries, Set<String> visible) throws IOException {
        var hidden = new TreeSet<>(hidden());
        for (var entry : entries) {
            if (!entry.bundled()) continue;
            String id = entry.definition().printer().id();
            if (visible.contains(id)) hidden.remove(id);
            else hidden.add(id);
        }
        configuration.set(KEY, String.join(",", hidden));
    }
}
