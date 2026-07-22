package id.behavio.iso.transport;

import id.behavio.iso.codec.Hex;
import id.behavio.iso.codec.IsoCodec;
import id.behavio.iso.codec.IsoMessage;
import id.behavio.iso.scenario.IsoFault;
import id.behavio.iso.spec.OperationRoute;
import id.behavio.iso.spec.ResolvedSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Membuka/menutup listener <b>TCP</b> per simulator ISO-8583 saat runtime.
 *
 * <p>Padanan {@code SimulatorServerManager} milik produk HTTP, tapi ditulis terpisah
 * karena bedanya mendasar: HTTP punya batas request bawaan, TCP tidak — koneksi bersifat
 * <b>persisten</b> dan satu koneksi membawa banyak pesan berturut-turut, sehingga
 * pembingkaian ({@link IsoFraming}) dan siklus hidup koneksi harus diurus sendiri.
 *
 * <p>Tiap koneksi dilayani thread virtual: host penguji lazim membuka koneksi lalu
 * membiarkannya menganggur lama, dan thread OS akan boros untuk pola seperti itu.
 */
@Component
public class IsoServerManager {

    private static final Logger log = LoggerFactory.getLogger(IsoServerManager.class);

    /** Satu listener + kumpulan sumber daya yang harus ditutup bersamanya. */
    private record Listener(ServerSocket socket, ExecutorService workers) {}

    private final Map<UUID, Listener> listeners = new ConcurrentHashMap<>();

    /**
     * Hasil pemrosesan: balasan + gangguan yang harus diterapkan di level transport.
     * Fault sengaja diputuskan di lapisan aplikasi (scenario) tapi DITERAPKAN di sini —
     * memutus koneksi & diam total hanya punya arti di level socket.
     */
    public record Outcome(IsoMessage response, IsoFault fault) {
        public Outcome {
            fault = fault == null ? IsoFault.none() : fault;
        }
    }

    /** Diimplementasikan lapisan aplikasi: pesan masuk → balasan + fault. */
    public interface Handler {
        Outcome handle(UUID simulatorId, IsoMessage request);
    }

    /**
     * Dipanggil setelah tiap pesan selesai — untuk Live View & request_logs.
     *
     * @param error alasan gagal, atau {@code null} bila berhasil. Wajib ikut dicatat:
     *              balasan kosong tanpa alasan hanya tampak sebagai "timeout" di sisi
     *              klien, dan itu kegagalan yang paling mahal dilacak.
     */
    public interface Listener2 {
        void onExchange(UUID simulatorId, String mti, String operation, String responseCode,
                        String requestHex, String responseHex, long durationMillis, String error);
    }

    public synchronized void start(UUID simulatorId, int port, ResolvedSpec spec,
                                   Handler handler, Listener2 observer) {
        if (listeners.containsKey(simulatorId)) {
            return;   // sudah jalan — start ulang tak boleh membuka port kedua
        }
        try {
            ServerSocket server = new ServerSocket();
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress(port), 50);

            ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
            listeners.put(simulatorId, new Listener(server, workers));

            Thread.ofVirtual().name("iso-accept-" + port).start(
                    () -> acceptLoop(simulatorId, server, workers, spec, handler, observer));
            log.info("Simulator ISO-8583 {} mendengarkan di TCP :{}", simulatorId, port);
        } catch (IOException e) {
            throw new IllegalStateException("Gagal membuka port TCP " + port + ": " + e.getMessage(), e);
        }
    }

    public synchronized void stop(UUID simulatorId) {
        Listener l = listeners.remove(simulatorId);
        if (l == null) {
            return;
        }
        try {
            l.socket().close();   // membuat accept() melempar → loop berhenti
        } catch (IOException e) {
            log.warn("Gagal menutup listener {}: {}", simulatorId, e.getMessage());
        }
        l.workers().shutdownNow();
    }

    public boolean isRunning(UUID simulatorId) {
        return listeners.containsKey(simulatorId);
    }

    private void acceptLoop(UUID simulatorId, ServerSocket server, ExecutorService workers,
                            ResolvedSpec spec, Handler handler, Listener2 observer) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                workers.submit(() -> serve(simulatorId, client, spec, handler, observer));
            } catch (IOException e) {
                if (!server.isClosed()) {
                    log.warn("accept() gagal pada simulator {}: {}", simulatorId, e.getMessage());
                }
                return;   // socket ditutup = stop() dipanggil; keluar dengan tenang
            }
        }
    }

    /** Melayani SATU koneksi: banyak pesan berturut-turut sampai peer menutup. */
    private void serve(UUID simulatorId, Socket client, ResolvedSpec spec,
                       Handler handler, Listener2 observer) {
        IsoFraming framing = new IsoFraming(spec.transport());
        IsoCodec codec = new IsoCodec(spec);
        try (client;
             DataInputStream in = new DataInputStream(client.getInputStream());
             OutputStream out = client.getOutputStream()) {

            while (true) {
                byte[] raw = framing.readFrame(in);
                if (raw == null) {
                    return;   // peer menutup dengan rapi
                }
                long t0 = System.nanoTime();
                String reqHex = Hex.encode(raw);
                String mti = null;
                String operation = null;
                String responseCode = null;
                String respHex = "";
                String error = null;
                try {
                    IsoMessage req = codec.unpack(raw);
                    mti = req.mti();
                    // Nama operasi & DE39 ikut disiarkan supaya Live View bisa dibaca
                    // sekilas — hex saja menuntut orang mem-parse di kepala.
                    operation = spec.route(req).map(OperationRoute::name).orElse(null);
                    Outcome outcome = handler.handle(simulatorId, req);
                    IsoFault fault = outcome == null ? IsoFault.none() : outcome.fault();

                    if (fault.delayMillis() > 0) {
                        Thread.sleep(fault.delayMillis());
                    }
                    if (fault.drop()) {
                        log.info("Simulator {}: koneksi diputus sesuai scenario", simulatorId);
                        if (observer != null) {
                            observer.onExchange(simulatorId, mti, operation, null, reqHex, "",
                                    elapsedMs(t0), "koneksi diputus sesuai scenario");
                        }
                        return;   // menutup socket → klien menerima EOF
                    }
                    if (!fault.noResponse() && outcome != null && outcome.response() != null) {
                        responseCode = outcome.response().raw(39);
                        byte[] packed = codec.pack(outcome.response());
                        if (fault.corrupt()) {
                            packed = corrupt(packed);
                        }
                        respHex = Hex.encode(packed);
                        framing.writeFrame(out, packed);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (RuntimeException e) {
                    // Pesan rusak TIDAK memutus koneksi: host penguji lazim mengirim banyak
                    // pesan lewat satu koneksi, dan menjatuhkannya karena satu pesan cacat
                    // menyembunyikan sisanya. Dicatat, lalu lanjut ke pesan berikutnya.
                    error = e.getMessage();
                    log.warn("Simulator {} gagal memproses pesan: {}", simulatorId, e.getMessage());
                }
                if (observer != null) {
                    observer.onExchange(simulatorId, mti, operation, responseCode, reqHex,
                            respHex, (System.nanoTime() - t0) / 1_000_000, error);
                }
            }
        } catch (IOException e) {
            log.debug("Koneksi simulator {} berakhir: {}", simulatorId, e.getMessage());
        }
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /**
     * Rusak byte terakhir. Sengaja HANYA satu byte di ujung: panjang frame tetap benar,
     * sehingga klien betul-betul menempuh jalur "frame diterima tapi gagal di-parse" —
     * bukan sekadar gagal membaca frame, yang sudah diuji skenario lain.
     */
    private static byte[] corrupt(byte[] packed) {
        byte[] out = packed.clone();
        out[out.length - 1] ^= (byte) 0xFF;
        return out;
    }
}
