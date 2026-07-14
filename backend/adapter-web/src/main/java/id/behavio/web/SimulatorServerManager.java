package id.behavio.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import id.behavio.core.engine.SimRequest;
import id.behavio.core.engine.SimResponse;
import id.behavio.core.port.EndpointRegistry;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Port/Server Manager (design.md §6.3): buka-tutup server HTTP per-simulator secara
 * runtime. Tiap simulator = satu port; semua mengarah ke satu mesin eksekusi. Memakai
 * JDK HttpServer agar isolasi per-port nyata (stop = connection-refused → basis Fault A).
 *
 * Routing DATA-DRIVEN via {@link EndpointRegistry}: path setiap operasi SNAP dapat
 * di-custom per-simulator dari dashboard (design.md §2 — bank berbeda kerap punya
 * path/versi berbeda, mis. BRI vs ASPI standar). Path yang tak dikenal → 404 eksplisit.
 */
@Component
public class SimulatorServerManager {

    private static final Logger log = LoggerFactory.getLogger(SimulatorServerManager.class);

    private final SimulationExecutor executor;
    private final SnapRequestMapper mapper;
    private final AccessTokenService accessTokenService;
    private final VirtualAccountService virtualAccountService;
    private final QrisService qrisService;
    private final EndpointRegistry endpointRegistry;
    private final Map<UUID, HttpServer> servers = new ConcurrentHashMap<>();

    public SimulatorServerManager(SimulationExecutor executor, SnapRequestMapper mapper,
                                  AccessTokenService accessTokenService,
                                  VirtualAccountService virtualAccountService,
                                  QrisService qrisService,
                                  EndpointRegistry endpointRegistry) {
        this.executor = executor;
        this.mapper = mapper;
        this.accessTokenService = accessTokenService;
        this.virtualAccountService = virtualAccountService;
        this.qrisService = qrisService;
        this.endpointRegistry = endpointRegistry;
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

            Optional<String> operation = endpointRegistry.resolveOperation(simulatorId, method, path);
            if (operation.isEmpty()) {
                write(exchange, 404, Map.of("Content-Type", "application/json"),
                        "{\"responseCode\":\"4040400\",\"responseMessage\":\"Path not registered on this simulator\"}");
                return;
            }

            switch (operation.get()) {
                case "access-token" -> {
                    AccessTokenService.Result r = accessTokenService.issue(simulatorId, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "va-create" -> {
                    VirtualAccountService.Result r = virtualAccountService.create(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "va-status" -> {
                    VirtualAccountService.Result r = virtualAccountService.inquiry(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "va-delete" -> {
                    VirtualAccountService.Result r = virtualAccountService.delete(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "qris-generate" -> {
                    QrisService.Result r = qrisService.generate(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "qris-query" -> {
                    QrisService.Result r = qrisService.query(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "qris-refund" -> {
                    QrisService.Result r = qrisService.refund(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "qris-cancel", "qris-expire" -> {
                    QrisService.Result r = qrisService.cancel(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "qris-decode" -> {
                    QrisService.Result r = qrisService.decode(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "qris-payment" -> {
                    QrisService.Result r = qrisService.payment(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "qris-apply-ott" -> {
                    QrisService.Result r = qrisService.applyOtt(simulatorId, method, path, headers, body);
                    write(exchange, r.status(), Map.of("Content-Type", "application/json"), r.body());
                }
                case "transfer" -> handleTransfer(simulatorId, method, path, headers, body, exchange);
                default -> write(exchange, 404, Map.of("Content-Type", "application/json"),
                        "{\"responseCode\":\"4040400\",\"responseMessage\":\"Operation not implemented\"}");
            }
        } catch (Exception e) {
            log.warn("Simulator {} error memproses request: {}", simulatorId, e.toString());
            write(exchange, 500, Map.of("Content-Type", "application/json"),
                    "{\"responseCode\":\"5000000\",\"responseMessage\":\"Internal error\"}");
        } finally {
            exchange.close();
        }
    }

    private void handleTransfer(UUID simulatorId, String method, String path, Map<String, String> headers,
                                String body, HttpExchange exchange) throws IOException {
        SimRequest request = new SimRequest(method, path, headers, mapper.toFields(body), body);
        SimResponse response = executor.execute(simulatorId, request);

        // Efek fisik fault diterapkan PASCA-commit (design.md §4.2)
        SimResponse.Fault fault = response.fault();
        if (fault != null && fault.delayMillis() > 0) {
            try {
                Thread.sleep(fault.delayMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        if (fault != null && fault.drop()) {
            // commit-then-drop: state sudah berubah, respons TIDAK dikirim (koneksi ditutup)
            log.info("Simulator {} FAULT commit-then-drop — respons di-drop", simulatorId);
            return;
        }
        String outBody = response.body();
        if (fault != null && fault.corrupt()) {
            outBody = corrupt(outBody);
        }
        write(exchange, response.httpStatus(), response.headers(), outBody);
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

    /** Rusak body respons (malformed JSON) untuk fault titik C. */
    private static String corrupt(String body) {
        if (body == null || body.isEmpty()) {
            return "{malformed";
        }
        int half = Math.max(1, body.length() / 2);
        return body.substring(0, half) + " <<CORRUPTED";
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
