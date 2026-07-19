package id.behavio.qris.platform.core.product;

import id.behavio.qris.platform.core.rule.FaultSpec;

import java.util.Map;
import java.util.UUID;

/**
 * Penangan satu operasi SNAP. Tiap produk mendaftarkan handler-nya sendiri
 * (lihat {@link ProductCatalog}); server per-port generik cukup melakukan lookup
 * berdasarkan {@code operation} hasil {@code EndpointRegistry} — tanpa {@code switch}
 * yang mencampur operasi bank & QRIS seperti sebelum pemisahan.
 */
public interface OperationHandler {

    Result handle(Request request);

    record Request(UUID simulatorId, String operation, String method, String path,
                   Map<String, String> headers, String body) {

        /** Header SNAP dibaca case-insensitive (klien nyata tidak konsisten kapitalisasinya). */
        public String header(String name) {
            String v = headers.get(name);
            if (v != null) return v;
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
            }
            return null;
        }
    }

    /**
     * @param fault efek fisik (delay/drop/corrupt) yang diterapkan adapter web PASCA-commit
     *              (design.md §4.2). null = tanpa fault.
     */
    record Result(int status, String body, Map<String, String> headers, FaultSpec fault) {

        public Result(int status, String body) {
            this(status, body, Map.of("Content-Type", "application/json"), null);
        }

        public Result(int status, String body, FaultSpec fault) {
            this(status, body, Map.of("Content-Type", "application/json"), fault);
        }
    }
}
