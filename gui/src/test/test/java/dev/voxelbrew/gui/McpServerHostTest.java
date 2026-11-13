package dev.voxelbrew.gui;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import org.junit.jupiter.api.Test;

class McpServerHostTest {
    @Test
    void listenerRequiresTokenAndRejectsForeignOrigin() throws Exception {
        try (var document = new DocumentController(
                        Runnable::run,
                        doc -> new DocumentController.Build(java.util.List.of(), java.util.List.of(), true));
                var server = new McpServerHost(new McpAutomation(document, () -> false, Runnable::run));
                var client = HttpClient.newHttpClient()) {
            server.start(new McpSettings(false, 0, 4, 30, 100));
            var endpoint = URI.create(server.endpoint());
            assertEquals(
                    401,
                    client.send(HttpRequest.newBuilder(endpoint).GET().build(), HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            assertEquals(
                    403,
                    client.send(
                                    HttpRequest.newBuilder(endpoint)
                                            .header("Origin", "https://example.org")
                                            .GET()
                                            .build(),
                                    HttpResponse.BodyHandlers.ofString())
                            .statusCode());
            var request = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + server.credential())
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"test","version":"1"}}}
                        """))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("voxelbrew"));
            String session = response.headers().firstValue("Mcp-Session-Id").orElseThrow();
            var inspect = HttpRequest.newBuilder(endpoint)
                    .header("Authorization", "Bearer " + server.credential())
                    .header("Mcp-Session-Id", session)
                    .header("MCP-Protocol-Version", "2025-11-25")
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json, text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"document_inspect","arguments":{}}}
                        """))
                    .build();
            var inspected = client.send(inspect, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, inspected.statusCode());
            assertTrue(inspected.body().contains("documentId"), inspected.body());
        }
    }
}
