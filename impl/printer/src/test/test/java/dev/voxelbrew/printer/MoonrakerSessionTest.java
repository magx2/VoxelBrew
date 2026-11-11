package dev.voxelbrew.printer;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import dev.voxelbrew.printer.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MoonrakerSessionTest {
    @TempDir
    Path directory;

    @Test
    void uploadDoesNotStartUntilExplicitRequestAndBusyPrinterRejectsStart() throws Exception {
        var calls = Collections.synchronizedList(new ArrayList<String>());
        var state = new java.util.concurrent.atomic.AtomicReference<>("standby");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if (exchange.getRequestURI().getPath().equals("/websocket")) {
                exchange.sendResponseHeaders(400, -1);
                exchange.close();
                return;
            }
            calls.add(exchange.getRequestURI().getPath());
            assertEquals("key", exchange.getRequestHeaders().getFirst("X-Api-Key"));
            String response;
            if (exchange.getRequestURI().getPath().endsWith("query"))
                response = "{\"result\":{\"status\":{\"print_stats\":{\"state\":\"" + state.get()
                        + "\"},\"virtual_sdcard\":{\"progress\":0.25}}}}";
            else if (exchange.getRequestURI().getPath().endsWith("upload")) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                assertTrue(body.contains("G1 X10"));
                assertFalse(body.contains("name=\"print\""));
                response = "{\"result\":{\"item\":{\"path\":\"stored.gcode\"}}}";
            } else response = "{\"result\":\"ok\"}";
            byte[] data = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, data.length);
            try (var output = exchange.getResponseBody()) {
                output.write(data);
            }
        });
        server.start();
        try (var session = new MoonrakerSession(new PrinterDevice(
                "local",
                "Local",
                PrinterDevice.Kind.MOONRAKER,
                "http://127.0.0.1:" + server.getAddress().getPort(),
                "",
                "",
                "key"))) {
            Path file = directory.resolve("job.gcode");
            Files.writeString(file, "G1 X10\n");
            var receipt = session.upload(file, fraction -> {});
            assertEquals(List.of("/server/files/upload"), calls);
            session.start(receipt);
            assertTrue(calls.contains("/printer/print/start"));
            state.set("printing");
            assertThrows(java.io.IOException.class, () -> session.start(receipt));
            session.command(PrinterSession.Command.PAUSE);
            assertEquals("/printer/print/pause", calls.getLast());
            assertEquals(0.25, session.status().progress().orElseThrow());
        } finally {
            server.stop(0);
        }
    }
}
