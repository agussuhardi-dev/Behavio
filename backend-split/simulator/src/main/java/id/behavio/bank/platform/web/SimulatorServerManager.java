package id.behavio.bank.platform.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import id.behavio.bank.platform.core.port.EndpointRegistry;
import id.behavio.bank.platform.core.port.EventPublisher;
import id.behavio.bank.platform.core.product.OperationHandler;
import id.behavio.bank.platform.core.rule.FaultSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * runtime. Tiap simulator = satu port. Memakai JDK HttpServer agar isolasi per-port nyata
 * (stop = connection-refused → basis Fault A).
 *
 * Satu instance per PRODUK (design.md §3.4): manager profil bank hanya mengenal handler
 * bank, manager profil QRIS hanya handler QRIS. Routing tetap DATA-DRIVEN via
 * {@link EndpointRegistry} (path tiap operasi dapat di-custom per-simulator, §2), tapi
 * dispatch-nya kini lookup ke map handler yang didaftarkan produk — menggantikan satu
 * {@code switch} panjang yang mencampur operasi bank & QRIS di satu tempat.
 */
public class SimulatorServerManager {

    private static final Logger log = LoggerFactory.getLogger(SimulatorServerManager.class);
    private static final Map<String, String> JSON = Map.of("Content-Type", "application/json");

    private final String product;
    private final EndpointRegistry endpointRegistry;
    private final Map<String, OperationHandler> handlers;
    /** Fan-out ke semua EventPublisher (log + request_logs + SSE Live View). */
    private final EventPublisher events;
    private final Map<UUID, HttpServer> servers = new ConcurrentHashMap<>();

    public SimulatorServerManager(String product, EndpointRegistry endpointRegistry,
                                  Map<String, OperationHandler> handlers,
                                  List<EventPublisher> eventPublishers) {
        this.product = product;
        this.endpointRegistry = endpointRegistry;
        this.handlers = Map.copyOf(handlers);
        this.events = event -> eventPublishers.forEach(p -> p.publishRequestEvent(event));
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
            log.info("Simulator {} [{}] START di port {}", simulatorId, product, port);
        } catch (IOException e) {
            throw new IllegalStateException("Gagal membuka port " + port + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stop(UUID simulatorId) {
        HttpServer server = servers.remove(simulatorId);
        if (server != null) {
            server.stop(0);
            log.info("Simulator {} [{}] STOP", simulatorId, product);
        }
    }

    public boolean isRunning(UUID simulatorId) {
        return servers.containsKey(simulatorId);
    }

    private void handle(UUID simulatorId, HttpExchange exchange) throws IOException {
        long startNanos = System.nanoTime();
        try {
            String method = exchange.getRequestMethod();
            String path = exchange.getRequestURI().getPath();
            Map<String, String> headers = headers(exchange);
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);

            Optional<String> operation = endpointRegistry.resolveOperation(simulatorId, method, path);
            if (operation.isEmpty()) {
                write(exchange, 404, JSON,
                        "{\"responseCode\":\"4040400\",\"responseMessage\":\"Path not registered on this simulator\"}");
                return;
            }
            OperationHandler handler = handlers.get(operation.get());
            if (handler == null) {
                write(exchange, 404, JSON,
                        "{\"responseCode\":\"4040400\",\"responseMessage\":\"Operation not implemented\"}");
                return;
            }

            OperationHandler.Result result = handler.handle(new OperationHandler.Request(
                    simulatorId, operation.get(), method, path, headers, body));
            writeAndEmit(simulatorId, method, path, headers, body, result, exchange, startNanos);
        } catch (Exception e) {
            log.warn("Simulator {} [{}] error memproses request: {}", simulatorId, product, e.toString());
            write(exchange, 500, JSON,
                    "{\"responseCode\":\"5000000\",\"responseMessage\":\"Internal error\"}");
        } finally {
            exchange.close();
        }
    }

    /**
     * Tulis respons lalu siarkan RequestEvent ke Live View (SSE) + request_logs.
     *
     * Efek fisik fault diterapkan PASCA-commit (design.md §4.2) — handler sudah menutup
     * transaksinya saat sampai di sini. Emisi event kini SATU jalur untuk semua operasi;
     * sebelumnya transfer meng-emit dari dalam engine sementara QRIS/VA dari lapisan web,
     * sehingga isi & waktu event-nya tak persis sama antar-operasi.
     */
    private void writeAndEmit(UUID simulatorId, String method, String path,
                              Map<String, String> requestHeaders, String requestBody,
                              OperationHandler.Result r, HttpExchange exchange, long startNanos) throws IOException {
        FaultSpec fault = r.fault();
        if (fault != null && fault.delayMillis() > 0) {
            try {
                Thread.sleep(fault.delayMillis());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }

        String outBody = r.body();
        boolean dropped = fault != null && fault.drop();
        if (dropped) {
            // commit-then-drop: state sudah berubah, respons TIDAK dikirim (koneksi ditutup)
            log.info("Simulator {} [{}] FAULT commit-then-drop — respons di-drop", simulatorId, product);
        } else {
            if (fault != null && fault.corrupt()) {
                outBody = corrupt(outBody);
            }
            write(exchange, r.status(), r.headers(), outBody);
        }

        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        try {
            events.publishRequestEvent(new EventPublisher.RequestEvent(
                    simulatorId.toString(), method, path, r.status(),
                    extractResponseCode(r.body()), durationMillis,
                    requestHeaders, requestBody, dropped ? "" : outBody));
        } catch (Exception e) {
            log.warn("Simulator {} [{}] gagal publikasi event: {}", simulatorId, product, e.toString());
        }
    }

    /** Ambil nilai "responseCode" dari body JSON tanpa parsing penuh. */
    private static String extractResponseCode(String body) {
        if (body == null) return "";
        int i = body.indexOf("\"responseCode\"");
        if (i < 0) return "";
        int q1 = body.indexOf('"', i + 14);
        if (q1 < 0) return "";
        int q2 = body.indexOf('"', q1 + 1);
        return q2 < 0 ? "" : body.substring(q1 + 1, q2);
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
