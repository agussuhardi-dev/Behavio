package id.behavio.iso.codec;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kamus data element — <b>data, bukan kode</b>.
 *
 * <p>Spec host bank tidak publik dan berbeda per jaringan (ATM Bersama, PRIMA, spec
 * internal bank…). Karena itu {@link #baseline1987()} hanyalah TITIK AWAL; begitu
 * dokumen host nyata (mis. Bank Shinhan) tersedia, penyesuaian dilakukan dengan
 * mengganti/menimpa entri di sini — codec tak perlu disentuh.
 *
 * <p>Lihat {@code docs/iso8583-plan.md} §2 & §4.
 */
public final class FieldDictionary {

    private final Map<Integer, FieldSpec> specs;

    private FieldDictionary(Map<Integer, FieldSpec> specs) {
        this.specs = specs;
    }

    public FieldSpec require(int de) {
        FieldSpec s = specs.get(de);
        if (s == null) {
            throw new IsoCodecException("DE " + de + " tidak ada di kamus — "
                    + "tambahkan definisinya sebelum memakainya");
        }
        return s;
    }

    public boolean has(int de) {
        return specs.containsKey(de);
    }

    /**
     * Turunan kamus ini dengan sebagian DE ditimpa/ditambah — inilah mesin di balik
     * {@code extends} pada profil: profil turunan cukup mendeklarasikan yang BERBEDA
     * (docs/iso8583-plan.md §2), karena spec bank umumnya "standar, kecuali N field ini".
     */
    public FieldDictionary with(FieldSpec... overrides) {
        return with(java.util.Arrays.asList(overrides));
    }

    public FieldDictionary with(java.util.Collection<FieldSpec> overrides) {
        Map<Integer, FieldSpec> copy = new LinkedHashMap<>(specs);
        for (FieldSpec s : overrides) {
            copy.put(s.de(), s);
        }
        return new FieldDictionary(copy);
    }

    /** Kamus dari daftar spec (mis. hasil parse packager XML). */
    public static FieldDictionary of(java.util.Collection<FieldSpec> fields) {
        Map<Integer, FieldSpec> m = new LinkedHashMap<>();
        for (FieldSpec s : fields) {
            if (m.putIfAbsent(s.de(), s) != null) {
                throw new IsoCodecException("DE " + s.de() + " didefinisikan lebih dari sekali");
            }
        }
        if (m.isEmpty()) {
            throw new IsoCodecException("Kamus DE kosong");
        }
        return new FieldDictionary(m);
    }

    /** Nomor DE yang terdefinisi, terurut. */
    public java.util.Set<Integer> defined() {
        return new java.util.TreeSet<>(specs.keySet());
    }

    /**
     * Baseline ISO 8583:1987 — subset yang dipakai operasi host (saldo, transfer, tarik
     * tunai, echo, reversal). Sengaja TIDAK memuat seluruh 128 DE: yang tak dipakai lebih
     * baik absen dan gagal keras saat dipanggil, daripada diam-diam salah format.
     */
    public static FieldDictionary baseline1987() {
        Map<Integer, FieldSpec> m = new LinkedHashMap<>();
        put(m, FieldSpec.llvar(2, "Primary Account Number", FieldType.N, 19));
        put(m, FieldSpec.fixed(3, "Processing Code", FieldType.N, 6));
        put(m, FieldSpec.fixed(4, "Amount, Transaction", FieldType.N, 12));
        put(m, FieldSpec.fixed(7, "Transmission Date & Time", FieldType.N, 10));
        put(m, FieldSpec.fixed(11, "System Trace Audit Number", FieldType.N, 6));
        put(m, FieldSpec.fixed(12, "Time, Local Transaction", FieldType.N, 6));
        put(m, FieldSpec.fixed(13, "Date, Local Transaction", FieldType.N, 4));
        put(m, FieldSpec.fixed(14, "Date, Expiration", FieldType.N, 4));
        put(m, FieldSpec.fixed(15, "Date, Settlement", FieldType.N, 4));
        put(m, FieldSpec.fixed(18, "Merchant Type", FieldType.N, 4));
        put(m, FieldSpec.fixed(22, "POS Entry Mode", FieldType.N, 3));
        put(m, FieldSpec.fixed(25, "POS Condition Code", FieldType.N, 2));
        put(m, FieldSpec.llvar(32, "Acquiring Institution ID", FieldType.N, 11));
        put(m, FieldSpec.llvar(35, "Track 2 Data", FieldType.Z, 37));
        put(m, FieldSpec.fixed(37, "Retrieval Reference Number", FieldType.AN, 12));
        put(m, FieldSpec.fixed(38, "Authorization ID Response", FieldType.AN, 6));
        put(m, FieldSpec.fixed(39, "Response Code", FieldType.AN, 2));
        put(m, FieldSpec.fixed(41, "Card Acceptor Terminal ID", FieldType.ANS, 8));
        put(m, FieldSpec.fixed(42, "Card Acceptor ID Code", FieldType.ANS, 15));
        put(m, FieldSpec.fixed(43, "Card Acceptor Name/Location", FieldType.ANS, 40));
        put(m, FieldSpec.lllvar(48, "Additional Data - Private", FieldType.ANS, 999));
        put(m, FieldSpec.fixed(49, "Currency Code, Transaction", FieldType.AN, 3));
        // PIN block itu 8 byte BINER (16 karakter hex di representasi kita), bukan ASCII.
        put(m, FieldSpec.fixed(52, "PIN Data", FieldType.B, 16).withEncoding(Encoding.BINARY));
        // DE53 dipakai operasi change-pin untuk membawa PIN block BARU (DE52 = yang lama).
        put(m, FieldSpec.fixed(53, "Security Related Control Info", FieldType.B, 16)
                .withEncoding(Encoding.BINARY));
        put(m, FieldSpec.lllvar(54, "Additional Amounts", FieldType.AN, 120));
        put(m, FieldSpec.lllvar(60, "Reserved National", FieldType.ANS, 999));
        put(m, FieldSpec.lllvar(61, "Reserved National", FieldType.ANS, 999));
        put(m, FieldSpec.lllvar(62, "Reserved Private", FieldType.ANS, 999));
        put(m, FieldSpec.lllvar(63, "Reserved Private", FieldType.ANS, 999));
        put(m, FieldSpec.fixed(70, "Network Management Info Code", FieldType.N, 3));
        put(m, FieldSpec.fixed(90, "Original Data Elements", FieldType.N, 42));
        put(m, FieldSpec.llvar(102, "Account Identification 1", FieldType.ANS, 28));
        put(m, FieldSpec.llvar(103, "Account Identification 2", FieldType.ANS, 28));
        return new FieldDictionary(m);
    }

    private static void put(Map<Integer, FieldSpec> m, FieldSpec s) {
        m.put(s.de(), s);
    }
}
