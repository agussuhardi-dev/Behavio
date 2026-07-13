package id.behavio.core.domain;

/**
 * Mode verifikasi signature SNAP.
 * STRICT   = verifikasi RSA/HMAC betulan (tolak signature salah).
 * SIMULATED = abaikan/paksa hasil (fokus alur bisnis).
 */
public enum SignatureMode {
    STRICT,
    SIMULATED
}
