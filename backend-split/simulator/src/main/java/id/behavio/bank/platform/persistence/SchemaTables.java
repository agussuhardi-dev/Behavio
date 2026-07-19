package id.behavio.bank.platform.persistence;

import java.util.regex.Pattern;

/**
 * Nama tabel ber-kualifikasi schema untuk satu produk (design.md §3.4). Seluruh mesin
 * konfigurasi di modul ini ditulis SEKALI terhadap kelas ini, lalu di-instansiasi
 * sekali per produk ({@code bank}, {@code qris}) — bukan dipercabangkan di dalam kode.
 *
 * Schema di-interpolasi ke SQL (identifier tak bisa jadi bind parameter di JDBC), jadi
 * nilainya divalidasi ketat di sini. Sumbernya {@code ProductCatalog.key()} yang berasal
 * dari kode, bukan input user — validasi ini pagar berlapis, bukan satu-satunya.
 */
public final class SchemaTables {

    private static final Pattern SAFE = Pattern.compile("[a-z][a-z0-9_]{0,29}");

    private final String schema;

    public SchemaTables(String schema) {
        if (schema == null || !SAFE.matcher(schema).matches()) {
            throw new IllegalArgumentException("Nama schema tidak valid: " + schema);
        }
        this.schema = schema;
    }

    public String schema() { return schema; }

    public String simulators()   { return schema + ".simulators"; }
    public String partners()     { return schema + ".partners"; }
    public String endpoints()    { return schema + ".endpoints"; }
    public String scenarios()    { return schema + ".scenarios"; }
    public String accessTokens() { return schema + ".access_tokens"; }
    public String entities()     { return schema + ".entities"; }
    public String requestLogs()  { return schema + ".request_logs"; }
    public String webhookOutbox(){ return schema + ".webhook_outbox"; }
    public String webhookSubscriptions() { return schema + ".webhook_subscriptions"; }

    @Override
    public String toString() { return schema; }
}
