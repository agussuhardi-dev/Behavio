package id.behavio.iso.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Menyamakan status simulator dengan kenyataan saat aplikasi baru hidup.
 *
 * <p>Status {@code RUNNING} disimpan di database, sedangkan yang benar-benar membuat port
 * terbuka adalah listener TCP di memori. Begitu aplikasi restart, listener-nya hilang tapi
 * barisnya tetap {@code RUNNING} — dashboard menyala hijau sementara klien ditolak
 * <i>connection refused</i>. Itu jenis kebohongan yang paling mahal: gejalanya menyerupai
 * kerusakan jaringan, padahal cukup ditekan Start.
 *
 * <p>Karena itu semua simulator dikembalikan ke {@code STOPPED} saat boot, dan dijalankan
 * kembali secara <b>manual</b>. Menyalakannya otomatis sempat dipertimbangkan dan ditolak:
 * port bisa sudah dipakai proses lain sesudah restart, dan gagal diam-diam saat boot lebih
 * buruk daripada tombol Start yang jelas.
 */
@Configuration
public class IsoStartupReconciler {

    private static final Logger log = LoggerFactory.getLogger(IsoStartupReconciler.class);

    @Bean
    public ApplicationRunner isoSimulatorStatusReconciler(JdbcClient db) {
        return args -> {
            int reset = db.sql("UPDATE iso8583.simulators SET status = 'STOPPED' WHERE status <> 'STOPPED'")
                    .update();
            if (reset > 0) {
                log.info("{} simulator ISO-8583 dikembalikan ke STOPPED setelah restart — "
                        + "jalankan ulang dari dashboard bila perlu.", reset);
            }
        };
    }
}
