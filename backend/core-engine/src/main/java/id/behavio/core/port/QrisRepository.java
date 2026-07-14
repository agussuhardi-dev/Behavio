package id.behavio.core.port;

import id.behavio.core.domain.QrisTransaction;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Outbound port: simpan/baca QR (entitas non-uang, tabel generik {@code entities}).
 * Isolasi per (simulatorId, partnerId).
 */
public interface QrisRepository {

    void save(QrisTransaction qr);

    Optional<QrisTransaction> find(UUID simulatorId, UUID partnerId, String referenceNo);

    /** Cari lintas partner (untuk Admin API/dashboard & refund yang identifikasi via referenceNo saja). */
    Optional<QrisTransaction> findAny(UUID simulatorId, String referenceNo);

    List<QrisTransaction> list(UUID simulatorId);
}
