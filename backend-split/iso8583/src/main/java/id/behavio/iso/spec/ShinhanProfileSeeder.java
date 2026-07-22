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
    public static final String VERSION = "1.0";

    private static final Logger log = LoggerFactory.getLogger(ShinhanProfileSeeder.class);

    @Bean
    public ApplicationRunner isoShinhanProfileSeeder(SpecProfileRepository repo) {
        return args -> {
            if (repo.exists(NAME, VERSION)) {
                return;
            }
            try {
                repo.save(profile(), "JSON");
                log.info("Profil spec ISO-8583 Shinhan ditanam: {} v{}", NAME, VERSION);
            } catch (RuntimeException e) {
                log.warn("Seed profil Shinhan dilewati: {}", e.getMessage());
            }
        };
    }

    public static SpecProfile profile() {
        List<OperationRoute> ops = List.of(
                // 0800 — sign-on 001, sign-off 002, key exchange 101, echo 301.
                // Keempatnya satu rute: pembedanya DE70, dan DE70 digemakan apa adanya
                // di balasan sehingga klien tetap bisa memasangkannya.
                new OperationRoute("network-management", "0800", null),

                new OperationRoute("balance-inquiry", "0200", "310000"),
                new OperationRoute("change-pin", "0200", "340000"),
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

                new OperationRoute("reversal", "0400", null));

        return new SpecProfile(NAME, VERSION, BaselineProfileSeeder.NAME, null, List.of(), ops);
    }
}
