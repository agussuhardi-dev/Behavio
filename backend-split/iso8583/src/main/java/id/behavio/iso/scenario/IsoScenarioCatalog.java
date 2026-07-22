package id.behavio.iso.scenario;

import java.util.List;
import java.util.Map;

/**
 * Scenario bawaan tiap operasi — cetak biru yang bisa di-override user, sama seperti
 * blueprint di produk HTTP.
 *
 * <p>Isinya sengaja mencerminkan kegagalan yang BENAR-BENAR ditemui saat integrasi host,
 * bukan daftar kode lengkap: DE39 yang lazim, plus tiga kegagalan transport yang justru
 * paling jarang diuji klien (menggantung, diputus, balasan rusak).
 */
public final class IsoScenarioCatalog {

    public static final String NORMAL = "Normal";

    private IsoScenarioCatalog() {}

    /** Nama scenario bawaan, urut tampil. */
    public static List<String> names() {
        return defaults().stream().map(IsoScenario::name).toList();
    }

    public static List<IsoScenario> defaults() {
        return List.of(
                IsoScenario.normal(),

                // ── penolakan bisnis (DE39) ────────────────────────────────
                de39("Saldo Tidak Cukup", "51"),
                de39("Kartu Tidak Valid", "14"),
                de39("PIN Salah", "55"),
                de39("Kartu Kedaluwarsa", "54"),
                de39("Rekening Tidak Ada", "52"),
                de39("Melebihi Limit", "61"),
                de39("Transaksi Tak Diizinkan", "57"),
                de39("Format Error", "30"),
                de39("Issuer Tidak Tersedia", "91"),
                de39("System Malfunction", "96"),

                // ── kegagalan transport (khas TCP, tak ada padanan di HTTP) ─
                new IsoScenario("Timeout (delay 30s)", Map.of(), false, IsoFault.delay(30_000)),
                new IsoScenario("Tanpa Balasan", Map.of(), false, IsoFault.silence()),
                new IsoScenario("Koneksi Diputus", Map.of(), false, IsoFault.dropConnection()),
                new IsoScenario("Balasan Rusak", Map.of(), false, IsoFault.corruptResponse()));
    }

    public static IsoScenario byName(String name) {
        return defaults().stream()
                .filter(s -> s.name().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(IsoScenario::normal);
    }

    private static IsoScenario de39(String name, String code) {
        return new IsoScenario(name, Map.of(39, code), false, IsoFault.none());
    }
}
