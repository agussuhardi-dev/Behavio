package id.behavio.web.live;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.UUID;

/** Live View SSE: stream request real-time per simulator ke dashboard. */
@RestController
@RequestMapping("/api/admin/v1/simulators/{id}/logs")
public class LiveViewController {

    private final SseBroadcaster broadcaster;

    public LiveViewController(SseBroadcaster broadcaster) {
        this.broadcaster = broadcaster;
    }

    @GetMapping("/stream")
    public SseEmitter stream(@PathVariable UUID id) {
        return broadcaster.register(id);
    }
}
