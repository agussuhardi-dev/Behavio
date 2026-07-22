package id.behavio.iso.web;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Live View ISO-8583 lewat <b>SSE</b> — mengikuti pola bank simulator
 * ({@code id.behavio.bank.platform.web.live.SseBroadcaster}).
 *
 * <p>Kenapa push, bukan polling: pesan ISO datang lewat socket kapan saja, dan justru
 * pesan pertama sesudah klien disambungkan yang paling ingin dilihat. Polling membuatnya
 * telat sampai satu interval, sementara "live" yang harus diklik dulu bukanlah live.
 *
 * <p>Disiarkan pada thread pemroses socket (virtual thread), bukan thread HTTP — karena
 * itu kegagalan kirim ke satu dashboard tak boleh menjatuhkan pemrosesan pesan: emitter
 * yang mati cukup dibuang.
 */
@Component
public class IsoSseBroadcaster {

    private final Map<UUID, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(UUID simulatorId) {
        SseEmitter emitter = new SseEmitter(0L);   // tanpa timeout
        emitters.computeIfAbsent(simulatorId, k -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(simulatorId, emitter));
        emitter.onTimeout(() -> remove(simulatorId, emitter));
        emitter.onError(e -> remove(simulatorId, emitter));
        return emitter;
    }

    private void remove(UUID simulatorId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(simulatorId);
        if (list != null) {
            list.remove(emitter);
        }
    }

    /**
     * Satu pertukaran pesan. {@code error} terisi berarti klien TIDAK menerima balasan —
     * di situlah sebabnya (lihat request_logs.error).
     */
    public record Exchange(String simulatorId, String mti, String operation,
                           String responseCode, String requestHex, String responseHex,
                           long durationMillis, String error) {}

    public void publish(Exchange event) {
        List<SseEmitter> list = emitters.get(UUID.fromString(event.simulatorId()));
        if (list == null || list.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event().name("exchange").data(event));
            } catch (IOException | IllegalStateException e) {
                // Dashboard ditutup / koneksi putus — buang emitternya, jangan ganggu socket.
                remove(UUID.fromString(event.simulatorId()), emitter);
            }
        }
    }
}
