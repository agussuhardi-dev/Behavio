package id.behavio.iso.spec;

import id.behavio.iso.persistence.SpecProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Profil bawaan <b>Shinhan</b> — processing code (DE3) sesuai {@code TransactionType} milik
 * klien, bukan tebakan kami:
 *
 * <pre>
 *   310000 info saldo on-us        340000 change PIN         700000 change phone
 *   330000 inquiry transfer on-us  400000 transfer on-us     710000 reset password IB
 *   351000 inquiry ke bank lain    411000 transfer ke bank lain
 *   361000/362000 inquiry masuk (tabungan/giro)   421000/422000 transfer masuk
 *   371000/372000 inquiry lewat (tabungan/giro)   431000/432000 transfer lewat
 *   900000 router interbank
 * </pre>
 *
 * <p>Kodenya ditulis <b>6 digit penuh</b>, bukan awalan 2 digit. Itu penting:
 * {@code 700000} (change phone) dan {@code 710000} (reset password) sama-sama berawalan
 * "70", jadi awalan pendek akan membuat keduanya ambigu dan salah satunya tak pernah
 * terpanggil.
 *
 * <p>Mewarisi kamus DE dari {@code iso8583-1987} — yang berbeda hanya rute operasinya.
 * Bitmap/transport ikut induk; kalau host Anda memakai bitmap ASCII hex, unggah packager
 * XML-nya (kelas {@code IFA_BITMAP} akan terbaca) atau timpa transport lewat profil JSON.
 *
 * <p>Idempoten: kalau sudah ada, tak melakukan apa-apa — profil bersifat immutable.
 */
@Configuration
public class ShinhanProfileSeeder {

    public static final String NAME = "shinhan-default";

    /**
     * Profil kedua: <b>persis kode yang ada di enum {@code TransactionType} klien</b>
     * ({@code bankshinhan-termsys}) — 17 processing code, TANPA {@code 010000} WITHDRAW,
     * {@code 341000} CREATE_PIN, dan {@code 500000} SALE.
     *
     * <p>Ketiga kode itu hanya ada di simulator referensi, tak pernah dikirim klien. Rute
     * yang tak pernah cocok memang tak berbahaya — profil ini bukan perbaikan bug,
     * melainkan pilihan bagi yang ingin permukaan simulator <b>persis</b> sebesar yang
     * dipakai klien, sehingga processing code di luar itu dibalas {@code DE39=30} alih-alih
     * diam-diam dilayani.
     *
     * <p>Sengaja bernama LAIN, bukan {@code shinhan-default} v1.2: profil turunan menunjuk
     * NAMA induk dan selalu memakai versi terbaru, jadi menambah v1.2 akan mengubah
     * perilaku profil turunan yang sudah dipakai — diam-diam.
     */
    public static final String CLIENT_NAME = "shinhan-klien";
    public static final String CLIENT_VERSION = "1.0";
    /**
     * 1.1 — ditambahkan setelah membandingkan dengan simulator referensi milik klien
     * ({@code bankshinhan-simulator}): SALE, CREATE_PIN, WITHDRAW, dan reversal MTI 0420.
     * Profil immutable, jadi 1.0 tetap ada berdampingan.
     */
    public static final String VERSION = "1.1";

    private static final Logger log = LoggerFactory.getLogger(ShinhanProfileSeeder.class);

    @Bean
    public ApplicationRunner isoShinhanProfileSeeder(SpecProfileRepository repo) {
        return args -> {
            seed(repo, NAME, VERSION, ShinhanProfileSeeder::profile);
            seed(repo, CLIENT_NAME, CLIENT_VERSION, ShinhanProfileSeeder::clientProfile);
        };
    }

    private static void seed(SpecProfileRepository repo, String name, String version,
                             java.util.function.Supplier<SpecProfile> factory) {
        if (repo.exists(name, version)) {
            return;
        }
        try {
            repo.save(factory.get(), "JSON");
            log.info("Profil spec ISO-8583 ditanam: {} v{}", name, version);
        } catch (RuntimeException e) {
            // Boot tak boleh gagal hanya karena seed — mis. dua instance start bersamaan.
            log.warn("Seed profil {} v{} dilewati: {}", name, version, e.getMessage());
        }
    }

    /** Processing code yang HANYA ada di simulator referensi, tak ada di enum klien. */
    private static final List<String> HANYA_DI_REFERENSI = List.of("010000", "341000", "500000");

    /**
     * Profil sepadan enum klien: {@link #profile()} dikurangi {@link #HANYA_DI_REFERENSI}.
     *
     * <p>Diturunkan, bukan ditulis ulang — dua daftar yang disalin akan melenceng begitu
     * salah satunya diubah, dan selisihnya tak akan terlihat sampai ada pesan yang ditolak.
     */
    public static SpecProfile clientProfile() {
        List<OperationRoute> ops = profile().operations().stream()
                .filter(r -> r.processingCode() == null
                        || !HANYA_DI_REFERENSI.contains(r.processingCode()))
                .toList();
        return new SpecProfile(CLIENT_NAME, CLIENT_VERSION, BaselineProfileSeeder.NAME,
                null, List.of(), ops);
    }

    public static SpecProfile profile() {
        List<OperationRoute> ops = List.of(
                // 0800 — sign-on 001, sign-off 002, key exchange 101, echo 301.
                // Keempatnya satu rute: pembedanya DE70, dan DE70 digemakan apa adanya
                // di balasan sehingga klien tetap bisa memasangkannya.
                new OperationRoute("network-management", "0800", null),

                new OperationRoute("balance-inquiry", "0200", "310000"),
                new OperationRoute("withdraw", "0200", "010000"),
                new OperationRoute("sale", "0200", "500000"),
                new OperationRoute("change-pin", "0200", "340000"),
                new OperationRoute("create-pin", "0200", "341000"),
                new OperationRoute("change-phone", "0200", "700000"),
                new OperationRoute("reset-password-ib", "0200", "710000"),

                new OperationRoute("transfer-on-us-inquiry", "0200", "330000"),
                new OperationRoute("transfer-on-us", "0200", "400000"),

                new OperationRoute("transfer-off-us-inquiry", "0200", "351000"),
                new OperationRoute("transfer-off-us", "0200", "411000"),

                new OperationRoute("transfer-in-saving-inquiry", "0200", "361000"),
                new OperationRoute("transfer-in-saving", "0200", "421000"),
                new OperationRoute("transfer-in-giro-inquiry", "0200", "362000"),
                new OperationRoute("transfer-in-giro", "0200", "422000"),

                new OperationRoute("transfer-via-saving-inquiry", "0200", "371000"),
                new OperationRoute("transfer-via-saving", "0200", "431000"),
                new OperationRoute("transfer-via-giro-inquiry", "0200", "372000"),
                new OperationRoute("transfer-via-giro", "0200", "432000"),

                new OperationRoute("router-interbank", "0200", "900000"),

                // DUA MTI reversal. Klien Shinhan mengirim 0420 (advice), bukan 0400 —
                // tanpa rute ini pesannya tak dikenali dan dibalas DE39=30. 0400
                // dipertahankan karena profil generik memakainya.
                new OperationRoute("reversal", "0400", null),
                new OperationRoute("reversal", "0420", null));

        return new SpecProfile(NAME, VERSION, BaselineProfileSeeder.NAME, null, List.of(), ops);
    }
}
