package id.behavio.qris.platform.core.port;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port untuk Admin API: kelola Partner (klien pemanggil + kunci signature).
 *
 * Generik lintas produk — bank maupun QRIS sama-sama punya partner dengan kredensial
 * SNAP sendiri, dan masing-masing menyimpannya di schema-nya sendiri. Dipisah dari
 * {@code AccountAdmin} (yang kini milik :product-bank) karena rekening adalah konsep
 * khusus bank: profil QRIS tak punya saldo.
 */
public interface PartnerAdmin {

    List<PartnerView> listPartners(UUID simulatorId);

    /** Tambah partner baru pada simulator. publicKey/clientSecret boleh null. */
    UUID createPartner(UUID simulatorId, String partnerId, String publicKeyPem, String clientSecret);

    void deletePartner(UUID simulatorId, UUID partnerRowId);

    record PartnerView(UUID id, String partnerId, boolean hasPublicKey, boolean hasClientSecret) {}
}
