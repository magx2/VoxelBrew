package dev.voxelbrew.exporter.api;

import static org.junit.jupiter.api.Assertions.*;

import dev.voxelbrew.engine.model.CadDocument;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ExportDialogsTest {
    @lombok.Getter
    @lombok.experimental.Accessors(fluent = true)
    static final class CloudDialog extends ExportDialog<CloudSettings> {
        private final String title;
        private final List<String> projects;

        CloudDialog(String title, List<String> projects) {
            this.title = title;
            this.projects = List.copyOf(projects);
        }
    }

    record CloudSettings(String project, boolean shared) {}

    record CloudReceipt(String resourceId, String project) {}

    @Test
    void providerDefinedCloudInteractionAndResultNeedNoLocalFile() throws Exception {
        var cli = new ExportDialogs().register(CloudDialog.class, request -> {
            assertEquals(List.of("team", "personal"), request.projects());
            return Optional.of(new CloudSettings("team", true));
        });
        // A provider can collect its own settings and return its own receipt type.
        // No network call is needed to exercise the interaction contract.
        Exporter<CloudReceipt> exporter = (document, parent) -> {
            var settings = parent.show(new CloudDialog("Publish design", List.of("team", "personal")));
            return settings.map(
                            value -> CompletableFuture.completedFuture(new CloudReceipt("remote-id", value.project())))
                    .orElseGet(() -> CompletableFuture.failedFuture(new CancellationException()));
        };
        assertEquals(
                new CloudReceipt("remote-id", "team"),
                exporter.export(CadDocument.empty(), cli).get());
        var cancelled = new ExportDialogs().register(CloudDialog.class, request -> Optional.empty());
        assertThrows(
                CancellationException.class,
                () -> exporter.export(CadDocument.empty(), cancelled).join());
    }

    @Test
    void unsupportedDialogsAndDuplicateHandlersFailExplicitly() {
        var host = new ExportDialogs();
        var dialog = new CloudDialog("Publish", List.of("team"));
        assertThrows(UnsupportedOperationException.class, () -> host.show(dialog));
        host.register(CloudDialog.class, request -> Optional.empty());
        assertThrows(
                IllegalArgumentException.class,
                () -> host.register(CloudDialog.class, request -> Optional.of(new CloudSettings("team", false))));
        assertTrue(host.show(dialog).isEmpty());
    }

    @Test
    void settingsAreAvailableOnlyAfterConfirmationAndReplacedOnReuse() {
        var dialog = new CloudDialog("Publish", List.of("team", "personal"));
        assertThrows(IllegalStateException.class, dialog::getSettings);
        var first = new CloudSettings("team", true);
        var second = new CloudSettings("personal", false);
        var host = new ExportDialogs().register(CloudDialog.class, request -> {
            assertThrows(IllegalStateException.class, request::getSettings);
            return Optional.of(first);
        });
        assertEquals(Optional.of(first), host.show(dialog));
        assertSame(first, dialog.getSettings());
        new ExportDialogs()
                .register(CloudDialog.class, request -> Optional.of(second))
                .show(dialog);
        assertSame(second, dialog.getSettings());
        assertTrue(new ExportDialogs()
                .register(CloudDialog.class, request -> Optional.empty())
                .show(dialog)
                .isEmpty());
        assertThrows(IllegalStateException.class, dialog::getSettings);
    }

    @Test
    void failedOrUnsupportedInteractionsClearPreviouslyConfirmedSettings() {
        var dialog = new CloudDialog("Publish", List.of("team"));
        var success = new ExportDialogs()
                .register(CloudDialog.class, request -> Optional.of(new CloudSettings("team", false)));
        var failure = new IllegalArgumentException("Invalid account");
        var failing = new ExportDialogs().register(CloudDialog.class, request -> {
            throw failure;
        });
        success.show(dialog);
        assertSame(failure, assertThrows(IllegalArgumentException.class, () -> failing.show(dialog)));
        assertThrows(IllegalStateException.class, dialog::getSettings);
        success.show(dialog);
        assertThrows(UnsupportedOperationException.class, () -> new ExportDialogs().show(dialog));
        assertThrows(IllegalStateException.class, dialog::getSettings);
        success.show(dialog);
        assertThrows(
                NullPointerException.class,
                () -> new ExportDialogs()
                        .register(CloudDialog.class, request -> null)
                        .show(dialog));
        assertThrows(IllegalStateException.class, dialog::getSettings);
    }
}
