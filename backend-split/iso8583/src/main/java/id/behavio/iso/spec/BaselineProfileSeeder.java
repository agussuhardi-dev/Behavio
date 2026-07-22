package id.behavio.iso.spec;

import id.behavio.iso.codec.FieldDictionary;
import id.behavio.iso.persistence.SpecProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * Menanam profil bawaan <b>ISO 8583:1987</b> saat boot.
 *
 * <p>Gunanya sebagai TITIK AWAL untuk di-clone lalu disesuaikan — spec bank di dunia
 * nyata adalah "standar, kecuali N field ini", jadi profil host cukup {@code extends}
 * profil ini dan memuat perbedaannya saja ({@code docs/iso8583-plan.md} §2).
 *
 * <p>Idempoten: kalau sudah ada, tak melakukan apa-apa — profil bersifat immutable.
 */
@Configuration
public class BaselineProfileSeeder {

    public static final String NAME = "iso8583-1987";
    /**
     * Dinaikkan ke 1.1 saat operasi change-pin & change-phone ditambahkan. Profil bersifat
     * IMMUTABLE, jadi versi baru dibuat berdampingan — simulator yang menunjuk 1.0 tetap
     * berperilaku persis seperti saat diuji.
     */
    public static final String VERSION = "1.1";

    private static final Logger log = LoggerFactory.getLogger(BaselineProfileSeeder.class);

    @Bean
    public ApplicationRunner isoBaselineProfileSeeder(SpecProfileRepository repo) {
        return args -> {
            if (repo.exists(NAME, VERSION)) {
                return;
            }
            try {
                repo.save(baseline(), "JSON");
                log.info("Profil spec ISO-8583 bawaan ditanam: {} v{}", NAME, VERSION);
            } catch (RuntimeException e) {
                // Boot tak boleh gagal hanya karena seed — mis. dua instance start bersamaan
                // dan yang kedua kalah di UNIQUE(name, version).
                log.warn("Seed profil bawaan dilewati: {}", e.getMessage());
            }
        };
    }

    /** Baseline sebagai profil: kamus DE standar + rute operasi host yang lazim. */
    public static SpecProfile baseline() {
        FieldDictionary dict = FieldDictionary.baseline1987();
        var fields = new ArrayList<>(dict.defined().stream().map(dict::require).toList());

        List<OperationRoute> ops = List.of(
                new OperationRoute("balance-inquiry", "0200", "30"),
                new OperationRoute("transfer", "0200", "40"),
                new OperationRoute("cash-withdrawal", "0200", "01"),
                new OperationRoute("purchase", "0200", "00"),
                new OperationRoute("network-management", "0800", null),
                new OperationRoute("reversal", "0400", null),
                // Processing code di bawah ini KONVENSI simulator, bukan standar ISO —
                // tiap host memakai kodenya sendiri. Sesuaikan lewat profil, bukan kode.
                new OperationRoute("change-pin", "0200", "92"),
                new OperationRoute("change-phone", "0200", "93"));

        return new SpecProfile(NAME, VERSION, null, TransportSpec.defaults(), fields, ops);
    }
}
