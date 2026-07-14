package id.behavio.bank.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Outbound port untuk Admin API: kelola Account (rekening + saldo) dari dashboard.
 * Khusus produk BANK — profil QRIS tidak punya rekening. Pengelolaan partner ada di
 * {@code id.behavio.core.port.PartnerAdmin} karena generik lintas produk.
 */
public interface AccountAdmin {

    List<AccountView> listAccounts(UUID simulatorId);

    UUID createAccount(UUID simulatorId, UUID partnerRowId, String accountNo, String holderName, BigDecimal balance);

    /** Ubah saldo langsung (mis. top-up untuk keperluan uji), tetap dijaga >= 0 oleh DB. */
    void setBalance(UUID simulatorId, UUID accountId, BigDecimal newBalance);

    void deleteAccount(UUID simulatorId, UUID accountId);

    record AccountView(UUID id, UUID partnerRowId, String partnerLabel, String accountNo,
                       String holderName, String currency, BigDecimal balance) {}
}
