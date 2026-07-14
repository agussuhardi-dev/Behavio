package id.behavio.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import id.behavio.core.engine.SimRequest;
import id.behavio.core.engine.SimResponse;
import id.behavio.core.port.EndpointRegistry;
import id.behavio.core.port.EventPublisher;
import id.behavio.core.rule.FaultSpec;
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
    private final AccountService accountService;
    private final TransactionHistoryService transactionHistoryService;
    private final QrisService qrisService;
    private final EndpointRegistry endpointRegistry;
    /** Fan-out ke semua EventPublisher (log + request_logs + SSE Live View), sama seperti CoreBeansConfig. */
    private final EventPublisher events;
    private final Map<UUID, HttpServer> servers = new ConcurrentHashMap<>();

    public SimulatorServerManager(SimulationExecutor executor, SnapRequestMapper mapper,
                                  AccessTokenService accessTokenService,
                                  VirtualAccountService virtualAccountService,
                                  AccountService accountService,
                                  TransactionHistoryService transactionHistoryService,
                                  QrisService qrisService,
                                  EndpointRegistry endpointRegistry,
                                  List<EventPublisher> eventPublishers) {
        this.executor = executor;
        this.mapper = mapper;
        this.accessTokenService = accessTokenService;
        this.virtualAccountService = virtualAccountService;
        this.accountService = accountService;
        this.transactionHistoryService = transactionHistoryService;
        this.qrisService = qrisService;
        this.endpointRegistry = endpointRegistry;
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
        long startNanos = System.nanoTime();
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
                    writeAndEmit(simulatorId, method, path, headers, body, r.status(), r.body(), exchange, startNanos);
                }
                case "va-create" -> {
                    VirtualAccountService.Result r = virtualAccountService.create(simulatorId, method, path, headers, body);
                    writeAndEmit(simulatorId, method, path, headers, body, r.status(), r.body(), exchange, startNanos);
                }
                case "va-status" -> {
                    VirtualAccountService.Result r = virtualAccountService.inquiry(simulatorId, method, path, headers, body);
                    writeAndEmit(simulatorId, method, path, headers, body, r.status(), r.body(), exchange, startNanos);
                }
                case "va-delete" -> {
                    VirtualAccountService.Result r = virtualAccountService.delete(simulatorId, method, path, headers, body);
                    writeAndEmit(simulatorId, method, path, headers, body, r.status(), r.body(), exchange, startNanos);
                }
                case "qris-generate" -> writeQris(simulatorId, method, path, headers, body, exchange,
                        qrisService.generate(simulatorId, method, path, headers, body), startNanos);
                case "qris-query" -> writeQris(simulatorId, method, path, headers, body, exchange,
                        qrisService.query(simulatorId, method, path, headers, body), startNanos);
                case "qris-refund" -> writeQris(simulatorId, method, path, headers, body, exchange,
                        qrisService.refund(simulatorId, method, path, headers, body), startNanos);
                case "qris-cancel", "qris-expire" -> writeQris(simulatorId, method, path, headers, body, exchange,
                        qrisService.cancel(simulatorId, method, path, headers, body), startNanos);
                case "qris-decode" -> writeQris(simulatorId, method, path, headers, body, exchange,
                        qrisService.decode(simulatorId, method, path, headers, body), startNanos);
                case "qris-payment" -> writeQris(simulatorId, method, path, headers, body, exchange,
                        qrisService.payment(simulatorId, method, path, headers, body), startNanos);
                case "qris-apply-ott" -> writeQris(simulatorId, method, path, headers, body, exchange,
                        qrisService.applyOtt(simulatorId, method, path, headers, body), startNanos);
                case "transfer" -> handleTransfer(simulatorId, method, path, headers, body, exchange);
                case "transfer-interbank" -> handleTransfer(simulatorId, method, path, headers, body, exchange);
                case "balance-inquiry" -> {
                    AccountService.Result r = accountService.balanceInquiry(simulatorId, method, path, headers, body);
                    writeAndEmit(simulatorId, method, path, headers, body, r.status(), r.body(), exchange, startNanos);
                }
                case "account-inquiry-internal" -> {
                    AccountService.Result r = accountService.internalInquiry(simulatorId, method, path, headers, body);
                    writeAndEmit(simulatorId, method, path, headers, body, r.status(), r.body(), exchange, startNanos);
                }
                case "transaction-history-list" -> {
                    TransactionHistoryService.Result r = transactionHistoryService.historyList(simulatorId, method, path, headers, body);
                    writeAndEmit(simulatorId, method, path, headers, body, r.status(), r.body(), exchange, startNanos);
                }
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

    /**
     * Tulis respons QRIS lalu siarkan RequestEvent ke Live View (SSE) + request_logs.
     * Transfer sudah emit event dari dalam engine; QRIS lewat QrisService langsung
     * sehingga event dipublikasikan di sini (pasca-tulis, di luar transaksi bisnis).
     */
    private void writeQris(UUID simulatorId, String method, String path,
                           Map<String, String> requestHeaders, String requestBody,
                           HttpExchange exchange, QrisService.Result r, long startNanos) throws IOException {
        // Efek fisik fault diterapkan PASCA-commit (design.md §4.2) — sama seperti transfer.
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
        if (!dropped) {
            if (fault != null && fault.corrupt()) {
                outBody = corrupt(outBody);
            }
            write(exchange, r.status(), Map.of("Content-Type", "application/json"), outBody);
        } else {
            // commit-then-drop: state sudah berubah, respons TIDAK dikirim (koneksi ditutup)
            log.info("Simulator {} FAULT commit-then-drop (QRIS) — respons di-drop", simulatorId);
        }

        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        try {
            events.publishRequestEvent(new EventPublisher.RequestEvent(
                    simulatorId.toString(), method, path, r.status(),
                    extractResponseCode(r.body()), durationMillis,
                    requestHeaders, requestBody, dropped ? "" : outBody));
        } catch (Exception e) {
            log.warn("Simulator {} gagal publikasi event QRIS: {}", simulatorId, e.toString());
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

    /** Tulis respons lalu siarkan event ke Live View (SSE) + request_logs. */
    private void writeAndEmit(UUID simulatorId, String method, String path,
                               Map<String, String> requestHeaders, String requestBody,
                               int status, String responseBody,
                               HttpExchange exchange, long startNanos) throws IOException {
        write(exchange, status, Map.of("Content-Type", "application/json"), responseBody);
        long durationMillis = (System.nanoTime() - startNanos) / 1_000_000;
        try {
            events.publishRequestEvent(new EventPublisher.RequestEvent(
                    simulatorId.toString(), method, path, status,
                    extractResponseCode(responseBody), durationMillis,
                    requestHeaders, requestBody, responseBody));
        } catch (Exception e) {
            log.warn("Simulator {} gagal publikasi event: {}", simulatorId, e.toString());
        }
    }
}
