package id.behavio.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Host yang dipakai untuk MENYEBUT simulator ke dunia luar — contoh {@code curl} di
 * dashboard & {@code servers[].url} di export OpenAPI.
 *
 * <p><b>Kenapa ada.</b> Host-host itu dulu string harfiah {@code "localhost"} di kode
 * (frontend & {@code OpenApiExporter}), jadi contoh curl hanya berguna di mesin yang
 * menjalankan Behavio — padahal gunanya justru untuk ditempel ke Postman/klien di mesin
 * lain. {@code server.forward-headers-strategy} tak menolong sedikit pun: setelan itu
 * hanya berlaku saat Spring merekonstruksi URL dari request, sementara tak ada yang
 * bertanya — hostnya dihardcode.
 *
 * <p><b>Urutan resolusi</b> (yang pertama menang):
 * <ol>
 *   <li>{@code DEPLOY_HOST} / properti {@code behavio.public-host} — deklarasi eksplisit
 *       operator. Wajib menang: hanya operator yang tahu alamat publik saat port
 *       simulator dipetakan berbeda (container, NAT, proxy).</li>
 *   <li>Host dari request yang sedang berjalan. Di sinilah
 *       {@code forward-headers-strategy: framework} akhirnya berguna — Spring membaca
 *       {@code X-Forwarded-Host}, sehingga host yang dipakai = host yang diketik user
 *       di browser (mis. IP LAN mesin ini), bukan loopback server.</li>
 *   <li>{@code localhost} — hanya kalau tak ada request terikat (mis. dipanggil dari
 *       job/test).</li>
 * </ol>
 *
 * <p><b>Yang TIDAK bisa diperbaiki di sini:</b> port. Contoh curl menunjuk <i>port
 * simulator</i> (mis. 9101) yang dilayani {@code HttpServer} JDK tersendiri dan tak
 * pernah melewati Spring — jadi kalau reverse proxy Anda hanya meneruskan :8080, host
 * yang benar saja tak membuat port itu terjangkau. Petakan port simulator di proxy, atau
 * set {@code DEPLOY_HOST} ke alamat yang memang menjangkaunya.
 */
@Component
public class PublicHost {

    private static final String FALLBACK = "localhost";

    private final String configured;

    public PublicHost(@Value("${behavio.public-host:}") String configured) {
        this.configured = configured == null ? "" : configured.trim();
    }

    /** Host publik saat ini — aman dipanggil di luar konteks request. */
    public String resolve() {
        if (!configured.isBlank()) {
            return configured;
        }
        try {
            String host = ServletUriComponentsBuilder.fromCurrentRequest().build().getHost();
            if (host != null && !host.isBlank()) {
                return host;
            }
        } catch (IllegalStateException e) {
            // tak ada request terikat ke thread ini — jatuh ke fallback
        }
        return FALLBACK;
    }
}
