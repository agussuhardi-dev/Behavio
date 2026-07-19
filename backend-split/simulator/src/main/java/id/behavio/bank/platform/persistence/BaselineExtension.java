package id.behavio.bank.platform.persistence;

import java.util.UUID;

/**
 * Kait (hook) provisioning khusus produk. Mesin {@link SchemaSimulatorAdmin} generik
 * hanya tahu simulator/partner/endpoint/scenario — hal yang ada di kedua produk.
 * Apa pun yang hanya dimiliki satu produk (mis. rekening baseline milik bank) dipasang
 * lewat antarmuka ini, bukan lewat {@code if (bank)} di dalam mesin.
 */
public interface BaselineExtension {

    /** Produk tanpa state tambahan saat provisioning (mis. QRIS). */
    BaselineExtension NONE = new BaselineExtension() {};

    /** Dipanggil setelah simulator + partner default dibuat. */
    default void afterProvision(UUID simulatorId, UUID partnerId) {}

    /** Dipanggil setelah clone selesai menyalin config — untuk menyalin state awal. */
    default void afterClone(UUID sourceSimulatorId, UUID newSimulatorId, UUID newPartnerId) {}
}
