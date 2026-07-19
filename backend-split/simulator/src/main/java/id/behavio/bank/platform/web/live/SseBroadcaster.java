package id.behavio.bank.platform.web.live;

import id.behavio.bank.platform.core.port.EventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live View (design.md §10): menyiarkan RequestEvent ke dashboard via SSE.
 * Juga sebuah EventPublisher — di-compose bersama RequestLogWriter di app.
 */
@Component
public class SseBroadcaster implements EventPublisher {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(UUID simulatorId) {
        SseEmitter emitter = new SseEmitter(0L); // tanpa timeout
        emitters.computeIfAbsent(simulatorId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(simulatorId, emitter));
        emitter.onTimeout(() -> remove(simulatorId, emitter));
        return emitter;
    }

    private void remove(UUID simulatorId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(simulatorId);
        if (list != null) list.remove(emitter);
    }

    @Override
    public void publishRequestEvent(RequestEvent event) {
        List<SseEmitter> list = emitters.get(UUID.fromString(event.simulatorId()));
        if (list == null) return;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("request").data(event));
            } catch (IOException e) {
                remove(UUID.fromString(event.simulatorId()), emitter);
            }
        }
    }
}
