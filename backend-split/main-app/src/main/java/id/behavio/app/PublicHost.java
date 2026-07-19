package id.behavio.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

/**
 * Host publik tingkat-aplikasi (untuk {@code /api/admin/v1/config} yang dibaca dashboard).
 *
 * <p>Tiap module produk juga membawa PublicHost-nya sendiri untuk {@code servers[].url}
 * export OpenAPI (terpisah, sesuai pemisahan penuh). Yang INI milik main-app karena host
 * yang dilaporkan ke dashboard adalah properti seluruh aplikasi, bukan salah satu produk.
 *
 * <p>Urutan: {@code DEPLOY_HOST}/{@code behavio.public-host} → host request
 * ({@code X-Forwarded-Host} lewat {@code forward-headers-strategy}) → {@code localhost}.
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
