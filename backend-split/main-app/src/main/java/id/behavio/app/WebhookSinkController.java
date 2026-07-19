package id.behavio.app;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Test-sink webhook lintas produk: menerima & merekam callback (verifikasi outbox tanpa
 * server eksternal). Satu instance di main-app melayani bank & qris. Bukan bagian
 * simulasi — hanya alat uji/demo.
 */
@RestController
@RequestMapping("/api/admin/v1/webhook-sink")
public class WebhookSinkController {

    private static final Logger log = LoggerFactory.getLogger(WebhookSinkController.class);
    private final List<Map<String, Object>> received = new CopyOnWriteArrayList<>();

    @PostMapping
    public Map<String, String> receive(@RequestBody(required = false) String body) {
        received.add(Map.of("at", Instant.now().toString(), "body", body == null ? "" : body));
        log.info("[webhook-sink] diterima: {}", body);
        return Map.of("status", "OK");
    }

    @GetMapping
    public List<Map<String, Object>> list() {
        return received;
    }

    @DeleteMapping
    public void clear() {
        received.clear();
    }
}
