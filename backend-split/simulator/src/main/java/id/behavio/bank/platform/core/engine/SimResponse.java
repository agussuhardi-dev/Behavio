package id.behavio.bank.platform.core.engine;

import java.util.Map;

/**
 * Response yang dihasilkan Behavior Engine (dipetakan adapter web ke HTTP).
 * {@code fault} = directive fisik (delay/drop/corrupt) yang diterapkan adapter web
 * SETELAH transaksi commit (design.md §4.2) — mis. commit-then-drop.
 */
public record SimResponse(
        int httpStatus,
        String responseCode,
        Map<String, String> headers,
        String body,
        Fault fault
) {
    /** Constructor lama (tanpa fault) — kompatibilitas. */
    public SimResponse(int httpStatus, String responseCode, Map<String, String> headers, String body) {
        this(httpStatus, responseCode, headers, body, null);
    }

    public static SimResponse of(int httpStatus, String responseCode, String body) {
        return new SimResponse(httpStatus, responseCode, Map.of(), body, null);
    }

    public SimResponse withFault(Fault fault) {
        return new SimResponse(httpStatus, responseCode, headers, body, fault);
    }

    /**
     * Efek fisik fault yang diterapkan adapter web (bukan core):
     * delay respons, drop koneksi (commit-then-drop), atau corrupt body.
     */
    public record Fault(long delayMillis, boolean drop, boolean corrupt) {}
}
