package dev.voxelbrew.gui;

import dev.voxelbrew.util.config.UserConfiguration;
import dev.voxelbrew.util.i18n.Translations;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

final class McpDialog {
    private McpDialog() {}

    static void show(Window owner, McpServerHost server, UserConfiguration store, Translations text) {
        var dialog = new Dialog<Void>();
        dialog.initOwner(owner);
        dialog.setTitle(text.text("mcp.title"));
        var auto = new CheckBox(text.text("mcp.autoStart"));
        var port = new TextField("0");
        var connections = new TextField("4");
        var idle = new TextField("30");
        var history = new TextField("100");
        var endpoint = new TextField(server.endpoint());
        endpoint.setEditable(false);
        var token = new PasswordField();
        token.setText(server.credential());
        token.setEditable(false);
        var status = new Label();
        var activity = new TextArea(server.activity().toString());
        activity.setEditable(false);
        activity.setPrefRowCount(3);
        try {
            var settings = McpSettings.load(store);
            auto.setSelected(settings.autoStart());
            port.setText(Integer.toString(settings.port()));
            connections.setText(Integer.toString(settings.maxConnections()));
            idle.setText(Integer.toString(settings.idleTimeoutMinutes()));
            history.setText(Integer.toString(settings.activityHistorySize()));
        } catch (Exception failure) {
            status.setText(failure.getMessage());
        }
        var start = new Button(text.text("mcp.apply"));
        start.setOnAction(event -> {
            try {
                var settings = new McpSettings(
                        auto.isSelected(),
                        Integer.parseInt(port.getText()),
                        Integer.parseInt(connections.getText()),
                        Integer.parseInt(idle.getText()),
                        Integer.parseInt(history.getText()));
                settings.save(store);
                server.start(settings);
                endpoint.setText(server.endpoint());
                status.setText(server.endpoint());
            } catch (Exception failure) {
                status.setText(failure.getMessage());
            }
        });
        var stop = new Button(text.text("mcp.stop"));
        stop.setOnAction(event -> {
            server.close();
            endpoint.clear();
        });
        var copy = new Button(text.text("mcp.copy"));
        copy.setOnAction(event -> {
            var clipboard = new ClipboardContent();
            clipboard.putString("{\"url\":\"" + server.endpoint() + "\",\"headers\":{\"Authorization\":\"Bearer "
                    + server.credential() + "\"}}");
            Clipboard.getSystemClipboard().setContent(clipboard);
        });
        var rotate = new Button(text.text("mcp.rotate"));
        rotate.setOnAction(event -> {
            server.regenerateToken();
            token.setText(server.credential());
            endpoint.clear();
        });
        dialog.getDialogPane()
                .setContent(new VBox(
                        8,
                        auto,
                        new Label(text.text("mcp.port")),
                        port,
                        new Label(text.text("mcp.connections")),
                        connections,
                        new Label(text.text("mcp.idle")),
                        idle,
                        new Label(text.text("mcp.history")),
                        history,
                        endpoint,
                        token,
                        start,
                        stop,
                        copy,
                        rotate,
                        status,
                        activity));
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait();
    }
}
