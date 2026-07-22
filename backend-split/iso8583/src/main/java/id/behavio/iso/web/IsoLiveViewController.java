package id.behavio.iso.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/**
 * Live View SSE untuk ISO-8583 — bentuk URL-nya sengaja sama persis dengan bank simulator
 * ({@code …/simulators/{id}/logs/stream}) agar dashboard tak perlu memperlakukan produk
 * ini sebagai kasus khusus.
 */
@RestController
@RequestMapping("/api/admin/v1/{product:iso8583}/simulators/{id}/logs")
public class IsoLiveViewController {

    private final IsoSseBroadcaster broadcaster;

    public IsoLiveViewController(IsoSseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable String product, @PathVariable UUID id) {
        return broadcaster.register(id);
    }
}
