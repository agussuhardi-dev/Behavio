package id.behavio.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import id.behavio.core.engine.SimRequest;
import id.behavio.core.engine.SimResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Port/Server Manager (design.md §6.3): buka-tutup server HTTP per-simulator secara
 * runtime. Tiap simulator = satu port; semua mengarah ke satu mesin eksekusi. Memakai
 * JDK HttpServer agar isolasi per-port nyata (stop = connection-refused → basis Fault A).
 */
@Component
public class SimulatorServerManager {

    private static final Logger log = LoggerFactory.getLogger(SimulatorServerManager.class);

    private final SimulationExecutor executor;
    private final SnapRequestMapper mapper;
    private final Map<UUID, HttpServer> servers = new ConcurrentHashMap<>();

    public SimulatorServerManager(SimulationExecutor executor, SnapRequestMapper mapper) {
        this.executor = executor;
        this.mapper = mapper;
    }

    public synchronized void start(UUID simulatorId, int port) {
        if (servers.containsKey(simulatorId)) {
            return; // sudah jalan
        }
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", exchange -> handle(simulatorId, exchange));
            server.setExecutor(Executors.newFixedThreadPool(8));
            server.start();
            servers.put(simulatorId, server);
            log.info("Simulator {} START di port {}", simulatorId, port);
        } catch (IOException e) {
            throw new IllegalStateException("Gagal membuka port " + port + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stop(UUID simulatorId) {
        HttpServer server = servers.remove(simulatorId);
        if (server != null) {
            server.stop(0);
            log.info("Simulator {} STOP", simulatorId);
        }
    }

    public boolean isRunning(UUID simulatorId) {
        return servers.containsKey(simulatorId);
    }

    private void handle(UUID simulatorId, HttpExchange exchange) throws IOException {
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> headers = headers(exchange);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            SimRequest request = new SimRequest(method, path, headers, mapper.toFields(body), body);
            SimResponse response = executor.execute(simulatorId, request);
            write(exchange, response.httpStatus(), response.headers(), response.body());
        } catch (Exception e) {
            log.warn("Simulator {} error memproses request: {}", simulatorId, e.toString());
            write(exchange, 500, Map.of("Content-Type", "application/json"),
                    "{\"responseCode\":\"5000000\",\"responseMessage\":\"Internal error\"}");
        } finally {
            exchange.close();
        }
    }

    /** Ambil header dengan nama kanonik SNAP uppercase (X-PARTNER-ID, X-EXTERNAL-ID, ...). */
    private static Map<String, String> headers(HttpExchange exchange) {
        Map<String, String> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : exchange.getRequestHeaders().entrySet()) {
            if (e.getValue() != null && !e.getValue().isEmpty()) {
                out.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue().get(0));
            }
        }
        return out;
    }

    private static void write(HttpExchange exchange, int status, Map<String, String> headers, String body) throws IOException {
        byte[] bytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        if (headers != null) {
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        }
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        }
    }
}
