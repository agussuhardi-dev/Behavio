package id.behavio.app;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Health & config tingkat-aplikasi (lintas produk) — dulu diduplikasi di tiap adapter-web,
 * kini tinggal di main-app supaya tak bentrok saat bank & qris dimuat bersama.
 */
@RestController
@RequestMapping("/api/admin/v1")
public class AdminHealthController {

    private final PublicHost publicHost;

    public AdminHealthController(PublicHost publicHost) {
        this.publicHost = publicHost;
    }

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "service", "behavio",
                "status", "UP",
                "time", Instant.now().toString()
        );
    }

    /**
     * Konfigurasi yang perlu diketahui dashboard. {@code publicHost} dipakai menyusun
     * contoh curl — frontend tak boleh menebak sendiri.
     */
    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("publicHost", publicHost.resolve());
    }
}
