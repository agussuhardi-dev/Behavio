package id.behavio.qris.engine;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Pembangun payload QR sesuai format EMVCo QR Code Specification (dipakai QRIS) —
 * TLV (Tag-Length-Value) + checksum CRC16-CCITT (poly 0x1021, init 0xFFFF) SUNGGUHAN,
 * bukan string tiruan. Subset field yang relevan untuk simulasi MPM (design.md
 * Lampiran A3.3): field 54 (amount) HANYA disertakan untuk QR dynamic; QR static
 * tidak menyertakannya (nominal diisi pelanggan saat bayar).
 */
public final class EmvQrBuilder {

    private EmvQrBuilder() {}

    /**
     * @param amount     null/kosong → QR static (Point of Initiation "11");
     *                   terisi → QR dynamic ("12"), nominal tertanam (field 54).
     * @param merchantId dipetakan ke sub-field Merchant Account Information.
     * @param referenceNo dipetakan ke Bill Number (field 62-01).
     */
    public static String build(BigDecimal amount, String merchantId, String merchantName,
                               String merchantCity, String referenceNo) {
        boolean dynamic = amount != null;
        StringBuilder sb = new StringBuilder();
        sb.append(tlv("00", "01"));                          // Payload Format Indicator
        sb.append(tlv("01", dynamic ? "12" : "11"));          // Point of Initiation Method

        String merchantAccountInfo = tlv("00", "ID.CO.QRIS.WWW")
                + tlv("01", truncate(nullToEmpty(merchantId), 15));
        sb.append(tlv("26", merchantAccountInfo));            // Merchant Account Info (QRIS)

        sb.append(tlv("52", "0000"));                         // Merchant Category Code
        sb.append(tlv("53", "360"));                          // Transaction Currency (IDR)
        if (dynamic) {
            sb.append(tlv("54", amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()));
        }
        sb.append(tlv("58", "ID"));                           // Country Code
        sb.append(tlv("59", truncate(merchantName == null ? "BEHAVIO MERCHANT" : merchantName, 25)));
        sb.append(tlv("60", truncate(merchantCity == null ? "JAKARTA" : merchantCity, 15)));

        String additionalData = tlv("01", truncate(nullToEmpty(referenceNo), 25)); // Bill Number
        sb.append(tlv("62", additionalData));

        sb.append("6304"); // header field CRC (tag=63, len=04) — nilai CRC menyusul
        String crc = crc16Ccitt(sb.toString());
        sb.append(crc);
        return sb.toString();
    }

    private static String tlv(String id, String value) {
        String v = value == null ? "" : value;
        return id + String.format("%02d", v.length()) + v;
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF, no reflect, xorout 0) — standar EMVCo. */
    static String crc16Ccitt(String data) {
        int crc = 0xFFFF;
        for (byte b : data.getBytes(StandardCharsets.US_ASCII)) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                crc = (crc & 0x8000) != 0 ? (crc << 1) ^ 0x1021 : crc << 1;
                crc &= 0xFFFF;
            }
        }
        return String.format("%04X", crc);
    }
}
