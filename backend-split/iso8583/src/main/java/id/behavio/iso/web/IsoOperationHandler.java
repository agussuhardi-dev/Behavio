package id.behavio.iso.web;

import id.behavio.iso.codec.IsoMessage;
import id.behavio.iso.persistence.IsoScenarioRepository;
import id.behavio.iso.persistence.IsoStateRepository;
import id.behavio.iso.scenario.IsoScenario;
import id.behavio.iso.transport.IsoServerManager;
import id.behavio.iso.spec.OperationRoute;
import id.behavio.iso.spec.ResolvedSpec;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;

/**
 * Logika host ISO-8583: menerima {@code 0200}/{@code 0800}, membalas {@code 0210}/{@code 0810}.
 *
 * <p>Operasinya sengaja mencerminkan bank simulator (cek saldo, transfer, tarik tunai)
 * tapi berjalan di atas state MILIK SENDIRI ({@code iso8583.accounts}) — saldo di sini
 * bukan saldo bank ({@code docs/iso8583-plan.md} §3 poin 4).
 */
@Component
public class IsoOperationHandler {

    /** DE39 — kosakata respons ISO-8583 yang lazim. */
    public static final String OK = "00";
    public static final String INSUFFICIENT_FUNDS = "51";
    public static final String INVALID_CARD = "14";
    public static final String INVALID_ACCOUNT = "52";
    public static final String FORMAT_ERROR = "30";
    /** DE39 25 — "unable to locate record": transaksi asli tak ditemukan saat reversal. */
    public static final String ORIGINAL_NOT_FOUND = "25";
    public static final String SYSTEM_MALFUNCTION = "96";

    private static final DateTimeFormatter DE7 = DateTimeFormatter.ofPattern("MMddHHmmss");

    private final IsoStateRepository state;
    private final IsoScenarioRepository scenarios;

    public IsoOperationHandler(IsoStateRepository state, IsoScenarioRepository scenarios) {
        this.state = state;
        this.scenarios = scenarios;
    }

    @Transactional
    public IsoServerManager.Outcome handle(UUID simulatorId, ResolvedSpec spec, IsoMessage req) {
        IsoMessage resp = req.newResponse();
        echoBack(req, resp);

        String operation = spec.route(req).map(OperationRoute::name).orElse(null);
        if (operation == null) {
            // Tak ada rute = profil tak mengenali pesan ini. Balas 30 (format error) alih-alih
            // diam: host penguji yang menunggu balasan akan timeout tanpa petunjuk apa pun.
            return new IsoServerManager.Outcome(resp.set(39, FORMAT_ERROR), null);
        }

        IsoMessage natural = switch (operation) {
            case "network-management" -> networkManagement(req, resp);
            case "balance-inquiry" -> balanceInquiry(simulatorId, req, resp);
            case "reversal" -> reversal(simulatorId, req, resp);
            case "change-pin" -> changePin(simulatorId, req, resp);
            case "change-phone" -> changePhone(simulatorId, req, resp);
            case "reset-password-ib" -> resetPasswordIb(simulatorId, req, resp);

            // ── inquiry: MEMERIKSA, tidak memindahkan dana ───────────────────
            case "transfer-on-us-inquiry" -> transferInquiryOnUs(simulatorId, req, resp);
            case "transfer-off-us-inquiry", "transfer-in-saving-inquiry",
                 "transfer-in-giro-inquiry", "transfer-via-saving-inquiry",
                 "transfer-via-giro-inquiry" -> transferInquiryExternal(simulatorId, req, resp);

            // ── eksekusi ────────────────────────────────────────────────────
            // "transfer" dipertahankan demi profil lama (iso8583-1987 v1.0/v1.1).
            case "transfer", "transfer-on-us" -> transferOnUs(simulatorId, req, resp);
            case "transfer-off-us" -> transferOffUs(simulatorId, req, resp);
            case "transfer-in-saving", "transfer-in-giro" -> transferExternal(simulatorId, req, resp);
            case "transfer-via-saving", "transfer-via-giro", "router-interbank" ->
                    transferPassThrough(simulatorId, req, resp);
            case "cash-withdrawal", "purchase" -> withdrawal(simulatorId, req, resp);

            default -> resp.set(39, FORMAT_ERROR);
        };

        // Scenario ditimpakan SETELAH logika alami berjalan. Urutan ini disengaja: mutasi
        // saldo tetap terjadi apa adanya, lalu scenario hanya mengubah apa yang DILAPORKAN —
        // sehingga "paksa DE39=51" bisa diuji tanpa memalsukan state.
        scenarios.ensureProvisioned(simulatorId, operation);
        IsoScenario sc = scenarios.active(simulatorId, operation);
        return new IsoServerManager.Outcome(sc.apply(natural), sc.fault());
    }

    /** Field korelasi WAJIB digemakan — tanpa STAN/RRN yang sama, peer tak bisa memasangkan. */
    private void echoBack(IsoMessage req, IsoMessage resp) {
        copy(req, resp, 2);
        copy(req, resp, 3);
        copy(req, resp, 4);
        copy(req, resp, 11);
        copy(req, resp, 12);
        copy(req, resp, 13);
        copy(req, resp, 37);
        copy(req, resp, 41);
        copy(req, resp, 49);
        copy(req, resp, 70);
        resp.set(7, LocalDateTime.now().format(DE7));
    }

    private static void copy(IsoMessage from, IsoMessage to, int de) {
        from.get(de).ifPresent(v -> to.set(de, v));
    }

    private IsoMessage networkManagement(IsoMessage req, IsoMessage resp) {
        return resp.set(39, OK);   // echo/sign-on: cukup diakui
    }

    private IsoMessage balanceInquiry(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        var acc = resolveAccount(simulatorId, req);
        if (acc.isEmpty()) {
            return resp.set(39, INVALID_CARD);
        }
        return resp.set(39, OK).set(54, additionalAmounts(acc.get()));
    }

    private IsoMessage withdrawal(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        var accOpt = resolveAccount(simulatorId, req);
        if (accOpt.isEmpty()) {
            return resp.set(39, INVALID_CARD);
        }
        var acc = accOpt.get();
        BigDecimal amount = amountOf(req);
        if (amount == null) {
            return resp.set(39, FORMAT_ERROR);
        }
        if (acc.balance().compareTo(amount) < 0) {
            return resp.set(39, INSUFFICIENT_FUNDS).set(54, additionalAmounts(acc));
        }
        state.debit(simulatorId, acc.accountNo(), amount);
        record(simulatorId, req, acc.accountNo(), null, amount);
        var after = state.findAccount(simulatorId, acc.accountNo()).orElse(acc);
        return resp.set(39, OK).set(54, additionalAmounts(after));
    }

    /**
     * Transfer <b>on-us</b> (DE3 {@code 400000}): kedua rekening ada di host ini, jadi
     * dana benar-benar berpindah — didebit dari pengirim, dikredit ke penerima.
     */
    private IsoMessage transferOnUs(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        var fromOpt = resolveAccount(simulatorId, req);
        if (fromOpt.isEmpty()) {
            return resp.set(39, INVALID_CARD);
        }
        String toNo = req.raw(103);
        if (toNo == null || toNo.isBlank()) {
            return resp.set(39, FORMAT_ERROR);
        }
        var toOpt = state.findAccount(simulatorId, toNo.trim());
        if (toOpt.isEmpty()) {
            return resp.set(39, INVALID_ACCOUNT);
        }
        BigDecimal amount = amountOf(req);
        if (amount == null) {
            return resp.set(39, FORMAT_ERROR);
        }
        var from = fromOpt.get();
        if (from.balance().compareTo(amount) < 0) {
            return resp.set(39, INSUFFICIENT_FUNDS).set(54, additionalAmounts(from));
        }
        state.debit(simulatorId, from.accountNo(), amount);
        state.credit(simulatorId, toNo.trim(), amount);
        record(simulatorId, req, from.accountNo(), toNo.trim(), amount);
        var after = state.findAccount(simulatorId, from.accountNo()).orElse(from);
        return resp.set(39, OK).set(54, additionalAmounts(after));
    }

    /**
     * Transfer <b>keluar ke bank lain</b> (DE3 {@code 411000}).
     *
     * <p>Hanya pengirim yang didebit. Rekening tujuan ada di bank LAIN — simulator ini
     * tak boleh mengarang saldonya, dan mengkredit rekening lokal yang kebetulan bernomor
     * sama akan menghasilkan uang dari udara. Nomor tujuan tetap divalidasi keberadaannya
     * di pesan (DE103), bukan di database.
     *
     * <p>Reversal-nya otomatis benar: transaksi dicatat tanpa counterpart, jadi pembalikan
     * hanya mengembalikan dana ke pengirim.
     */
    private IsoMessage transferOffUs(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        var fromOpt = resolveAccount(simulatorId, req);
        if (fromOpt.isEmpty()) {
            return resp.set(39, INVALID_CARD);
        }
        String toNo = req.raw(103);
        if (toNo == null || toNo.isBlank()) {
            return resp.set(39, FORMAT_ERROR);
        }
        BigDecimal amount = amountOf(req);
        if (amount == null) {
            return resp.set(39, FORMAT_ERROR);
        }
        var from = fromOpt.get();
        if (from.balance().compareTo(amount) < 0) {
            return resp.set(39, INSUFFICIENT_FUNDS).set(54, additionalAmounts(from));
        }
        state.debit(simulatorId, from.accountNo(), amount);
        record(simulatorId, req, from.accountNo(), null, amount);
        var after = state.findAccount(simulatorId, from.accountNo()).orElse(from);
        return resp.set(39, OK).set(54, additionalAmounts(after));
    }

    /**
     * Transfer yang melibatkan bank lain — tabungan ({@code 421000}) maupun giro
     * ({@code 422000}).
     *
     * <p><b>Arahnya ditentukan oleh data, bukan oleh nama operasi.</b> Simulator ini
     * hanya memegang rekening satu bank, jadi sisi mana yang ADA di sini sudah cukup
     * menentukan siapa yang saldonya berubah:
     *
     * <ul>
     *   <li>pengirim (DE102/PAN) ada di sini → dana <b>keluar</b>, pengirim didebit;</li>
     *   <li>kalau tidak, penerima (DE103) ada di sini → dana <b>masuk</b>, penerima
     *       dikredit;</li>
     *   <li>tak satu pun ada → {@code DE39=52}.</li>
     * </ul>
     *
     * <p>Ini menggantikan asumsi sebelumnya bahwa {@code 42x000} selalu berarti "masuk".
     * Pesan nyata membuktikan kode yang sama juga dipakai untuk dana KELUAR (DE102
     * rekening lokal, DE103 + DE127 menunjuk bank tujuan) — dan asumsi lama membalasnya
     * {@code 52} padahal rekeningnya jelas ada. Membaca arah dari data menghapus seluruh
     * kelas salah tebak itu.
     *
     * <p>Sisi seberang tak pernah disentuh: ia ada di bank lain, dan memindahkan saldo
     * rekening lokal yang kebetulan bernomor sama akan menciptakan uang.
     */
    private IsoMessage transferExternal(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        BigDecimal amount = amountOf(req);
        if (amount == null) {
            return resp.set(39, FORMAT_ERROR);
        }

        var fromOpt = resolveAccount(simulatorId, req);
        if (fromOpt.isPresent()) {
            var from = fromOpt.get();
            if (from.balance().compareTo(amount) < 0) {
                return resp.set(39, INSUFFICIENT_FUNDS).set(54, additionalAmounts(from));
            }
            state.debit(simulatorId, from.accountNo(), amount);
            record(simulatorId, req, from.accountNo(), null, amount);
            var after = state.findAccount(simulatorId, from.accountNo()).orElse(from);
            return resp.set(39, OK).set(54, additionalAmounts(after));
        }

        String toNo = req.raw(103);
        if (toNo == null || toNo.isBlank()) {
            toNo = req.raw(102);
        }
        if (toNo == null || toNo.isBlank()) {
            return resp.set(39, FORMAT_ERROR);
        }
        var toOpt = state.findAccount(simulatorId, toNo.trim());
        if (toOpt.isEmpty()) {
            return resp.set(39, INVALID_ACCOUNT);
        }
        state.credit(simulatorId, toNo.trim(), amount);
        // accountNo = penerima, counterpart kosong → reversal mendebit balik penerima.
        record(simulatorId, req, toNo.trim(), null, amount);
        var after = state.findAccount(simulatorId, toNo.trim()).orElse(toOpt.get());
        return resp.set(39, OK).set(54, additionalAmounts(after));
    }

    /**
     * Transfer <b>bank lain → bank lain</b> ({@code 431000}/{@code 432000}) dan
     * {@code router-interbank} ({@code 900000}): host ini cuma dilewati.
     *
     * <p>Tak ada saldo yang berubah — kedua pihak ada di luar. Sengaja TIDAK dicatat
     * sebagai transaksi finansial: kalau dicatat, reversal-nya akan memindahkan dana yang
     * tak pernah berpindah. Yang perlu diuji klien di sini adalah bentuk balasannya.
     */
    private IsoMessage transferPassThrough(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        if (amountOf(req) == null) {
            return resp.set(39, FORMAT_ERROR);
        }
        return resp.set(39, OK);
    }

    /**
     * Inquiry transfer on-us ({@code 330000}): memastikan rekening tujuan ADA dan
     * mengembalikan nama pemiliknya lewat DE48 — persis kegunaan inquiry di dunia nyata
     * (nasabah mengonfirmasi nama sebelum lanjut). <b>Tidak</b> memindahkan dana.
     */
    private IsoMessage transferInquiryOnUs(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        // DE103 dulu, lalu DE102. Pada INQUIRY hanya ada satu rekening yang ditanyakan —
        // milik penerima — dan klien nyata lazim menaruhnya di DE102 (Account
        // Identification 1), bukan DE103 yang baru terpakai saat eksekusi. Menuntut DE103
        // membuat inquiry yang sah dibalas DE39=30 tanpa petunjuk apa yang kurang.
        String toNo = req.raw(103);
        if (toNo == null || toNo.isBlank()) {
            toNo = req.raw(102);
        }
        if (toNo == null || toNo.isBlank()) {
            return resp.set(39, FORMAT_ERROR);
        }
        var toOpt = state.findAccount(simulatorId, toNo.trim());
        if (toOpt.isEmpty()) {
            return resp.set(39, INVALID_ACCOUNT);
        }
        return resp.set(39, OK).set(48, beneficiary(toOpt.get().accountNo(),
                toOpt.get().holderName()));
    }

    /**
     * Inquiry transfer yang melibatkan bank lain. Rekening di seberang tak bisa
     * diverifikasi dari sini, jadi yang diperiksa adalah <b>kelengkapan pesan</b>; nama
     * penerima dibalas sebagai nilai contoh yang jelas-jelas dummy.
     *
     * <p>Kalau Anda butuh inquiry yang MENOLAK (rekening tujuan tak ditemukan di bank
     * lain), pakai scenario — di situlah kendalinya, bukan di kode ini.
     */
    private IsoMessage transferInquiryExternal(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        String toNo = req.raw(103);
        if (toNo == null || toNo.isBlank()) {
            toNo = req.raw(102);
        }
        if (toNo == null || toNo.isBlank()) {
            return resp.set(39, FORMAT_ERROR);
        }
        // Rekening milik host ini tetap dijawab dengan nama sebenarnya bila kebetulan ada.
        String name = state.findAccount(simulatorId, toNo.trim())
                .map(IsoStateRepository.Account::holderName)
                .orElse("NASABAH BANK LAIN");
        return resp.set(39, OK).set(48, beneficiary(toNo.trim(), name));
    }

    /**
     * Reset password internet banking ({@code 710000}).
     *
     * <p>Simulator tak menyimpan password internet banking — dan tak seharusnya. Yang
     * diuji klien di sini adalah alur & bentuk balasannya, jadi yang dilakukan hanyalah
     * memastikan rekening/kartunya dikenal. Penolakan diuji lewat scenario.
     */
    private IsoMessage resetPasswordIb(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        return resolveAccount(simulatorId, req).isEmpty()
                ? resp.set(39, INVALID_CARD)
                : resp.set(39, OK);
    }

    /** DE48 inquiry: nomor rekening + nama penerima, dipisah spasi. */
    private static String beneficiary(String accountNo, String holderName) {
        return accountNo + " " + (holderName == null ? "" : holderName.trim());
    }

    /**
     * Reversal (0400): membatalkan efek finansial transaksi sebelumnya.
     *
     * <p>DE90 membawa identitas pesan ASLI — 4 digit MTI + 6 digit STAN di depan. Hanya
     * itu yang dipakai mencari; rekening & nominal diambil dari catatan kita sendiri,
     * karena DE90 memang tak membawanya.
     *
     * <p><b>Idempoten:</b> reversal LAZIM dikirim ulang saat acquirer tak yakin
     * balasannya sampai. Pengiriman kedua tetap dijawab {@code 00} tapi TIDAK
     * mengembalikan dana lagi — kalau tidak, saldo bertambah dari udara.
     */
    private IsoMessage reversal(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        String de90 = req.raw(90);
        if (de90 == null || de90.length() < 10) {
            return resp.set(39, FORMAT_ERROR);
        }
        String originalStan = de90.substring(4, 10);

        var txnOpt = state.findReversibleByStan(simulatorId, originalStan);
        if (txnOpt.isEmpty()) {
            return resp.set(39, ORIGINAL_NOT_FOUND);
        }
        var txn = txnOpt.get();

        // Penanda dipasang DULU; hanya yang berhasil memasangnya boleh memindahkan dana.
        if (!state.markReversed(txn.id())) {
            return resp.set(39, OK);   // sudah pernah dibalik — jawab sukses, jangan ulangi
        }
        state.credit(simulatorId, txn.accountNo(), txn.amount());
        if (txn.counterpartNo() != null && !txn.counterpartNo().isBlank()) {
            state.debit(simulatorId, txn.counterpartNo(), txn.amount());
        }
        return resp.set(39, OK);
    }

    /**
     * Change PIN. PIN block LAMA di DE52, BARU di DE53 — konvensi yang lazim, tapi tiap
     * host berbeda; kalau spec Anda menaruhnya di tempat lain, ubah profilnya, bukan kode ini.
     *
     * <p><b>PIN lama TIDAK diverifikasi.</b> Memverifikasinya menuntut HSM/ZPK untuk
     * mendekripsi PIN block. Di simulator, penolakan "PIN lama salah" dihasilkan lewat
     * scenario (DE39=55) — itu memberi kendali yang justru lebih berguna saat menguji klien.
     */
    private IsoMessage changePin(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        String pan = req.raw(2);
        if (pan == null || pan.isBlank()) {
            return resp.set(39, FORMAT_ERROR);
        }
        // DE53 dulu, lalu DE48. Dua konvensi yang sama-sama dipakai di lapangan: sebagian
        // host menaruh PIN block BARU di DE53 (Security Related Control Info), sebagian
        // lagi — termasuk klien Shinhan ini — di DE48 (Additional Data - Private).
        // Menuntut DE53 saja membuat change-pin yang sah dibalas DE39=30 tanpa petunjuk
        // apa yang kurang.
        String newPin = req.raw(53);
        if (newPin == null || newPin.isBlank()) {
            newPin = req.raw(48);
        }
        if (newPin == null || newPin.isBlank()) {
            return resp.set(39, FORMAT_ERROR);   // tak ada PIN baru = tak ada yang diubah
        }
        if (state.findAccountByPan(simulatorId, pan.trim()).isEmpty()) {
            return resp.set(39, INVALID_CARD);
        }
        return state.updatePin(simulatorId, pan.trim(), newPin.trim())
                ? resp.set(39, OK)
                : resp.set(39, INVALID_CARD);
    }

    /**
     * Change nomor telepon. Nomor baru dibaca dari DE48 (Additional Data — private),
     * tempat yang lazim untuk data non-standar; rekening ditentukan DE102 atau PAN.
     * Sekali lagi: kalau host Anda memakai DE lain, sesuaikan lewat profil.
     */
    private IsoMessage changePhone(UUID simulatorId, IsoMessage req, IsoMessage resp) {
        var accOpt = resolveAccount(simulatorId, req);
        if (accOpt.isEmpty()) {
            return resp.set(39, INVALID_CARD);
        }
        String phone = req.raw(48);
        if (phone == null || phone.isBlank()) {
            return resp.set(39, FORMAT_ERROR);
        }
        return state.updatePhone(simulatorId, accOpt.get().accountNo(), phone.trim())
                ? resp.set(39, OK)
                : resp.set(39, INVALID_ACCOUNT);
    }

    private void record(UUID simulatorId, IsoMessage req, String accountNo,
                        String counterpartNo, BigDecimal amount) {
        state.recordTransaction(simulatorId, req.mti(), req.raw(3), req.raw(11), req.raw(37),
                req.raw(2), accountNo, counterpartNo, amount, OK);
    }

    /**
     * Rekening sumber: DE102 bila ada, kalau tidak lewat PAN (DE2) → tabel kartu.
     * Urutan ini penting — transaksi dari ATM menyertakan DE102, sedangkan POS umumnya
     * hanya membawa PAN.
     */
    /**
     * Menentukan rekening sumber dari pesan: <b>DE102 → DE2 → DE35</b>.
     *
     * <p>DE35 (Track 2) ikut dipakai karena terminal kartu-hadir (ATM/EDC) lazim mengirim
     * HANYA track, tanpa DE2. Tanpa jalur ini pesan yang sah dari EDC selalu dibalas
     * DE39=14 — dan penyebabnya tak akan terlihat di mana pun.
     */
    private Optional<IsoStateRepository.Account> resolveAccount(UUID simulatorId, IsoMessage req) {
        String acct = req.raw(102);
        if (acct != null && !acct.isBlank()) {
            return state.findAccount(simulatorId, acct.trim());
        }
        String pan = req.raw(2);
        if (pan == null || pan.isBlank()) {
            pan = panFromTrack2(req.raw(35));
        }
        if (pan == null || pan.isBlank()) {
            return Optional.empty();
        }
        return state.findAccountByPan(simulatorId, pan.trim());
    }

    /**
     * PAN dari Track 2: bagian sebelum pemisah. Pemisahnya {@code =} pada track ASCII,
     * atau {@code D} bila track sudah "di-ASCII-kan" dari nibble heksadesimal — dua-duanya
     * ditemui di lapangan, jadi keduanya diterima.
     */
    private static String panFromTrack2(String track2) {
        if (track2 == null || track2.isBlank()) {
            return null;
        }
        String t = track2.trim();
        int sep = t.indexOf('=');
        if (sep < 0) {
            sep = t.indexOf('D');
        }
        if (sep < 0) {
            sep = t.indexOf('d');
        }
        String pan = sep > 0 ? t.substring(0, sep) : t;
        return pan.chars().allMatch(Character::isDigit) ? pan : null;
    }

    /** DE4 bernilai n12 tanpa desimal — 2 digit terakhir adalah sen. */
    private static BigDecimal amountOf(IsoMessage req) {
        String raw = req.raw(4);
        if (raw == null || !raw.matches("\\d{1,12}")) {
            return null;
        }
        return new BigDecimal(raw).movePointLeft(2);
    }

    /**
     * DE54 (Additional Amounts) format lazim per entri 20 karakter:
     * jenis rekening(2) + jenis amount(2) + mata uang(3) + tanda(1) + nominal(12).
     * {@code 10} = rekening tabungan · {@code 02} = saldo tersedia · {@code C} = positif.
     */
    private static String additionalAmounts(IsoStateRepository.Account acc) {
        String amount = acc.balance().movePointRight(2).setScale(0).toPlainString();
        if (amount.length() > 12) {
            amount = amount.substring(amount.length() - 12);
        }
        return "1002" + acc.currency() + "C" + "0".repeat(12 - amount.length()) + amount;
    }
}
