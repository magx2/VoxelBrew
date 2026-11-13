package dev.voxelbrew.gui;

import io.modelcontextprotocol.server.*;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.jetty.ee10.servlet.*;
import org.eclipse.jetty.server.*;

/** Window-owned, authenticated loopback transport. Never executes CAD on HTTP threads. */
final class McpServerHost implements AutoCloseable {
    private final McpAutomation automation;
    private Server http;
    private McpSyncServer mcp;
    private volatile String token = token();
    private java.nio.file.Path credentialFile;
    private int port;
    private int historyLimit = 100;
    private final Deque<Map<String, Object>> activity = new ArrayDeque<>();

    @javax.inject.Inject
    McpServerHost(McpAutomation automation) {
        this.automation = automation;
    }

    void busy(java.util.function.BooleanSupplier value) {
        automation.busy(value);
    }

    String endpoint() {
        return http == null ? "" : "http://127.0.0.1:" + port + "/mcp";
    }

    String credential() {
        return token;
    }

    void credentials(java.nio.file.Path file) throws IOException {
        token = McpTokenStore.load(file, McpServerHost::token);
        credentialFile = file;
    }

    synchronized void start(McpSettings settings) throws Exception {
        close();
        historyLimit = settings.activityHistorySize();
        var transport = HttpServletStreamableServerTransportProvider.builder()
                .mcpEndpoint("/mcp")
                .sessionSweepInterval(Duration.ofMinutes(settings.idleTimeoutMinutes()))
                .build();
        mcp = McpServer.sync(transport)
                .serverInfo("voxelbrew", "1.0")
                .capabilities(
                        McpSchema.ServerCapabilities.builder().tools(false).build())
                .build();
        register("document_inspect", "Read the live editable document and its revision/build diagnostics", Map.of());
        register(
                "sketch_rectangle",
                "Create an editable rectangle sketch. Coordinates in mm; plane XY, XZ or YZ. Profile ID is id.profile.",
                Map.of(
                        "id", "string", "plane", "string", "x", "number", "y", "number", "width", "number", "height",
                        "number"));
        register(
                "sketch_circle",
                "Create an editable circle sketch in mm. Profile ID is id.profile.",
                Map.of("id", "string", "plane", "string", "x", "number", "y", "number", "diameter", "number"));
        register("history_undo", "Undo one live document transaction", Map.of());
        register("history_redo", "Redo one live document transaction", Map.of());
        register("history_cursor", "Set timeline rollback cursor", Map.of("position", "integer"));
        register(
                "feature_edit",
                "Edit a numeric feature field (millimetres for lengths)",
                Map.of("id", "string", "field", "string", "value", "number"));
        register(
                "feature_expression",
                "Bind a unit-aware expression to a feature field",
                Map.of("id", "string", "field", "string", "expression", "string"));
        register(
                "feature_remove",
                "Delete a feature range using engine history semantics",
                Map.of("from", "integer", "to", "integer"));
        register(
                "feature_add",
                "Add a feature: extrude, cut, split, edgeFinish or shell. Definition uses engine record fields.",
                Map.of("type", "string", "definition", "object"));
        register(
                "feature_replace",
                "Replace an existing feature definition",
                Map.of("type", "string", "definition", "object"));
        register("configuration_activate", "Activate a named parameter configuration", Map.of("name", "string"));
        register("document_new", "Create a new document", Map.of("discardUnsaved", "boolean"));
        register("document_open", "Open an editable document", Map.of("path", "string", "discardUnsaved", "boolean"));
        register("document_save", "Save the live document", Map.of("path", "string", "overwrite", "boolean"));
        http = new Server();
        var connector = new ServerConnector(http);
        connector.setHost("127.0.0.1");
        connector.setPort(settings.port());
        http.addConnector(connector);
        var context = new ServletContextHandler();
        context.setContextPath("/");
        var permits = new Semaphore(settings.maxConnections());
        var authentication = new FilterHolder(new Filter() {
            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
                    throws IOException, ServletException {
                var req = (HttpServletRequest) request;
                var res = (HttpServletResponse) response;
                String authority = "127.0.0.1:" + port;
                if (!authority.equals(req.getHeader("Host"))
                        || req.getHeader("Origin") != null
                                && !("http://" + authority).equals(req.getHeader("Origin"))) {
                    res.sendError(403);
                    return;
                }
                String credential = req.getHeader("Authorization");
                if (credential == null
                        || !MessageDigest.isEqual(
                                ("Bearer " + token).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                                credential.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
                    res.sendError(401);
                    return;
                }
                if (!permits.tryAcquire()) {
                    res.sendError(429);
                    return;
                }
                try {
                    chain.doFilter(request, response);
                } finally {
                    permits.release();
                }
            }
        });
        authentication.setAsyncSupported(true);
        context.addFilter(authentication, "/*", EnumSet.of(DispatcherType.REQUEST));
        var servlet = new ServletHolder(transport);
        servlet.setAsyncSupported(true);
        context.addServlet(servlet, "/mcp");
        http.setHandler(context);
        try {
            http.start();
            port = connector.getLocalPort();
        } catch (Exception failure) {
            close();
            throw failure;
        }
    }

    private void register(String name, String description, Map<String, String> fields) {
        var properties = new LinkedHashMap<String, Object>();
        fields.forEach((key, type) -> properties.put(key, Map.of("type", type)));
        if (!name.equals("document_inspect")) {
            properties.put("documentId", Map.of("type", "string"));
            properties.put("revision", Map.of("type", "integer"));
            properties.put("generation", Map.of("type", "integer"));
        }
        var tool = McpSchema.Tool.builder()
                .name(name)
                .description(description)
                .inputSchema(new McpSchema.JsonSchema(
                        "object", properties, List.copyOf(properties.keySet()), false, null, null))
                .build();
        mcp.addTool(new McpServerFeatures.SyncToolSpecification(tool, (exchange, request) -> {
            try {
                var value = automation.call(name, request.arguments()).get();
                recordActivity(name, true);
                return McpSchema.CallToolResult.builder()
                        .structuredContent(value)
                        .addTextContent("Live document updated; inspect rebuilding and valid before using geometry.")
                        .build();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                return McpSchema.CallToolResult.builder()
                        .isError(true)
                        .addTextContent("Interrupted")
                        .build();
            } catch (ExecutionException failure) {
                recordActivity(name, false);
                return McpSchema.CallToolResult.builder()
                        .isError(true)
                        .addTextContent(String.valueOf(failure.getCause().getMessage()))
                        .build();
            }
        }));
    }

    private void recordActivity(String tool, boolean success) {
        synchronized (activity) {
            activity.addLast(Map.of(
                    "tool",
                    tool,
                    "success",
                    success,
                    "time",
                    java.time.Instant.now().toString()));
            while (activity.size() > historyLimit) activity.removeFirst();
        }
    }

    List<Map<String, Object>> activity() {
        synchronized (activity) {
            return List.copyOf(activity);
        }
    }

    void regenerateToken() {
        String replacement = token();
        if (credentialFile != null) {
            try {
                McpTokenStore.save(credentialFile, replacement);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }
        token = replacement;
        close();
    }

    private static String token() {
        var bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    @Override
    public synchronized void close() {
        if (mcp != null) {
            mcp.close();
            mcp = null;
        }
        if (http != null) {
            try {
                http.stop();
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot stop MCP listener", failure);
            } finally {
                http = null;
            }
        }
    }
}
