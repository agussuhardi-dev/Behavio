package id.behavio.bank.platform.web.live;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Live View SSE: stream request real-time per simulator ke dashboard.
 *
 * Broadcaster di-key oleh simulatorId yang unik lintas produk, jadi segmen {@code product}
 * di URL hanya menjaga bentuk Admin API konsisten dengan endpoint lain.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:bank}/simulators/{id}/logs")
public class LiveViewController {

    private final SseBroadcaster broadcaster;

    public LiveViewController(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable String product, @PathVariable UUID id) {
        return broadcaster.register(id);
    }
}
