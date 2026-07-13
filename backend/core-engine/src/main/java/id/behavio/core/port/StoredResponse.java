package id.behavio.core.port;

/**
 * Respons tersimpan untuk replay idempotensi (SNAP X-EXTERNAL-ID). Adapter persistence
 * bebas menyimpannya (mis. kolom terpisah atau JSON) — core hanya butuh 3 nilai ini.
 */
public record StoredResponse(int httpStatus, String responseCode, String body) {}
