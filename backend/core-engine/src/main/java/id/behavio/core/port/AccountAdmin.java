package id.behavio.core.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port untuk Admin API: kelola Partner & Account dari dashboard
 * (design.md — melengkapi provisioning baseline dengan CRUD manual). Bukan bagian
 * pipeline simulasi; hanya konfigurasi/administrasi.
 */
public interface AccountAdmin {

    List<PartnerView> listPartners(UUID simulatorId);

    /** Tambah partner baru (nasabah/pemanggil) pada simulator. publicKey/clientSecret boleh null. */
    UUID createPartner(UUID simulatorId, String partnerId, String publicKeyPem, String clientSecret);

    void deletePartner(UUID simulatorId, UUID partnerRowId);

    List<AccountView> listAccounts(UUID simulatorId);

    UUID createAccount(UUID simulatorId, UUID partnerRowId, String accountNo, String holderName, BigDecimal balance);

    /** Ubah saldo langsung (mis. top-up untuk keperluan uji), tetap dijaga ≥ 0 oleh DB. */
    void setBalance(UUID simulatorId, UUID accountId, BigDecimal newBalance);

    void deleteAccount(UUID simulatorId, UUID accountId);

    record PartnerView(UUID id, String partnerId, boolean hasPublicKey, boolean hasClientSecret) {}

    record AccountView(UUID id, UUID partnerRowId, String partnerLabel, String accountNo,
                       String holderName, String currency, BigDecimal balance) {}
}
