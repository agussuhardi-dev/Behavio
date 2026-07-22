package id.behavio.iso.spec;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.codec.IsoMessage;

/**
 * Pemetaan <b>pesan masuk → operasi</b>. Padanan "method + path" milik produk HTTP:
 * ISO-8583 tak punya URL, jadi routing ditentukan <b>MTI + processing code (DE3)</b>.
 *
 * @param name           nama operasi ({@code balance-inquiry}, {@code transfer}, …)
 * @param mti            MTI persis 4 digit ({@code 0200})
 * @param processingCode awalan DE3 yang dicocokkan ({@code "30"} = cek saldo), atau
 *                       {@code null}/kosong bila operasi tak bergantung DE3 (mis. 0800)
 */
public record OperationRoute(String name, String mti, String processingCode) {

    public OperationRoute {
        if (name == null || name.isBlank()) {
            throw new IsoCodecException("operations[].name wajib diisi");
        }
        if (mti == null || !mti.matches("\\d{4}")) {
            throw new IsoCodecException("operasi '" + name + "': mti harus 4 digit, dapat: " + mti);
        }
        if (processingCode != null && !processingCode.isBlank()
                && !processingCode.matches("\\d{1,6}")) {
            throw new IsoCodecException(
                    "operasi '" + name + "': processingCode harus 1–6 digit, dapat: " + processingCode);
        }
        name = name.trim();
        processingCode = (processingCode == null || processingCode.isBlank())
                ? null : processingCode.trim();
    }

    /** Cocok bila MTI sama DAN (tak ada syarat DE3, atau DE3 berawalan processingCode). */
    public boolean matches(IsoMessage msg) {
        if (!mti.equals(msg.mti())) {
            return false;
        }
        if (processingCode == null) {
            return true;
        }
        String de3 = msg.raw(3);
        return de3 != null && de3.startsWith(processingCode);
    }

    /**
     * Kunci untuk deteksi rute bertabrakan. Dua rute dengan kunci sama = pesan yang sama
     * bisa jatuh ke dua operasi — ambigu, jadi ditolak saat unggah.
     */
    public String key() {
        return mti + "/" + (processingCode == null ? "*" : processingCode);
    }
}
