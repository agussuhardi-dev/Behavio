package id.behavio.qris.platform.core.product;

import java.util.List;

/**
 * Satu operasi SNAP yang dilayani sebuah produk (bank/QRIS).
 *
 * {@code key} adalah identitas stabil yang TIDAK berubah walau {@code defaultPath}
 * di-override per-simulator dari dashboard (design.md §2) — bank berbeda kerap
 * memakai path/versi berbeda untuk operasi yang sama (mis. BRI memakai
 * "/intrabank/snap/v2.0/transfer-intrabank", bukan "/v1.0/transfer-intrabank").
 *
 * @param scenarioNames scenario preset yang di-provision untuk operasi ini. Kosong =
 *                      operasi berlogika tetap (mis. access-token) yang responsnya
 *                      tidak dipilih lewat sakelar scenario.
 */
public record Operation(String key, String method, String defaultPath, String label,
                        List<String> scenarioNames) {

    public Operation {
        scenarioNames = scenarioNames == null ? List.of() : List.copyOf(scenarioNames);
    }

    /** Operasi tanpa scenario (logika tetap). */
    public static Operation plain(String key, String method, String defaultPath, String label) {
        return new Operation(key, method, defaultPath, label, List.of());
    }

    public boolean hasScenarios() {
        return !scenarioNames.isEmpty();
    }
}
