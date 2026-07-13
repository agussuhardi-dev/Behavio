package id.behavio.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Inbound adapter (Admin API). Fase 0: health check untuk verifikasi app hidup.
 * Admin API berjalan di port tetap (:8080), terpisah dari API simulasi per-port.
 */
@RestController
@RequestMapping("/api/admin/v1")
public class AdminHealthController {

    @GetMapping("/ping")
    public Map<String, Object> ping() {
        return Map.of(
                "service", "behavio",
                "status", "UP",
                "time", Instant.now().toString()
        );
    }
}
