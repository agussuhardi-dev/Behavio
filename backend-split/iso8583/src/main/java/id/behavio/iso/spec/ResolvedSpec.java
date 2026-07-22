package id.behavio.iso.spec;

import id.behavio.iso.codec.FieldDictionary;
import id.behavio.iso.codec.IsoMessage;

import java.util.List;
import java.util.Optional;

/**
 * Profil yang sudah <b>digabung dengan seluruh induknya</b> dan siap dipakai codec.
 * Inilah bentuk yang dilihat runtime — {@link SpecProfile} adalah bentuk mentahnya.
 */
public record ResolvedSpec(String id,
                           TransportSpec transport,
                           FieldDictionary dictionary,
                           List<OperationRoute> operations) {

    /** Operasi yang cocok untuk pesan masuk, bila ada. */
    public Optional<OperationRoute> route(IsoMessage msg) {
        // Rute ber-processingCode diperiksa DULU: yang lebih spesifik harus menang atas
        // rute umum ber-MTI saja, kalau tidak "0800 apa saja" bisa menelan rute khusus.
        return operations.stream().filter(r -> r.processingCode() != null && r.matches(msg))
                .findFirst()
                .or(() -> operations.stream().filter(r -> r.matches(msg)).findFirst());
    }
}
