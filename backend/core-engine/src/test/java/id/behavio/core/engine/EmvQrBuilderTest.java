package id.behavio.core.engine;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

class EmvQrBuilderTest {

    @Test
    void crc16_cocok_vektor_uji_standar_CCITT_FALSE() {
        // Vektor uji baku CRC-16/CCITT-FALSE (poly 0x1021, init 0xFFFF): "123456789" -> 0x29B1
        assertEquals("29B1", EmvQrBuilder.crc16Ccitt("123456789"));
    }

    @Test
    void dynamic_qr_menyertakan_amount_dan_diawali_payload_format() {
        String qr = EmvQrBuilder.build(new BigDecimal("15000.00"), "MERCHANT1", "Toko Budi", "Jakarta", "REF-1");
        assertTrue(qr.startsWith("000201"));      // 00=Payload Format Indicator "01", 01=len2
        assertTrue(qr.contains("010212"));        // Point of Initiation "12" (dynamic)
        assertTrue(qr.contains("5408" + "15000.00")); // tag 54, len 08 ("15000.00" = 8 char)
        assertTrue(qr.contains("6304"));          // header CRC
        assertEquals(4, qr.substring(qr.length() - 4).length());
    }

    @Test
    void static_qr_tanpa_amount_lebih_pendek_dan_tak_memuat_nilai_nominal() {
        String dynamic = EmvQrBuilder.build(new BigDecimal("15000.00"), "MERCHANT1", "Toko Budi", "Jakarta", "REF-3");
        String staticQr = EmvQrBuilder.build(null, "MERCHANT1", "Toko Budi", "Jakarta", "REF-3");
        assertTrue(staticQr.contains("010211"));   // Point of Initiation "11" (static)
        assertTrue(staticQr.length() < dynamic.length()); // static tidak menyertakan field amount
        assertFalse(staticQr.contains("15000.00"));
        assertTrue(dynamic.contains("15000.00"));
    }

    @Test
    void crc_valid_saat_diverifikasi_ulang() {
        String qr = EmvQrBuilder.build(new BigDecimal("1000"), "M1", "Toko", "Kota", "R1");
        String withoutCrc = qr.substring(0, qr.length() - 4);
        String expectedCrc = qr.substring(qr.length() - 4);
        assertEquals(expectedCrc, EmvQrBuilder.crc16Ccitt(withoutCrc));
    }
}
