package id.behavio.bank.port;

import id.behavio.bank.domain.VirtualAccount;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: simpan/baca Virtual Account (entitas non-uang, tabel generik
 * {@code entities}). Isolasi per (simulatorId, partnerId).
 */
public interface VirtualAccountRepository {

    void save(VirtualAccount va);

    Optional<VirtualAccount> find(UUID simulatorId, UUID partnerId, String virtualAccountNo);

    List<VirtualAccount> list(UUID simulatorId);

    void delete(UUID simulatorId, UUID partnerId, String virtualAccountNo);
}
