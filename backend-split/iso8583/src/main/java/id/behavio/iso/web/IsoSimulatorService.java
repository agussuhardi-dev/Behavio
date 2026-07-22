package id.behavio.iso.web;

import id.behavio.iso.codec.IsoCodecException;
import id.behavio.iso.persistence.IsoStateRepository;
import id.behavio.iso.spec.ResolvedSpec;
import id.behavio.iso.spec.SpecProfileService;
import id.behavio.iso.transport.IsoServerManager;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Siklus hidup simulator ISO-8583: buat, start (buka port TCP), stop, hapus. */
@Service
public class IsoSimulatorService {

    private final JdbcClient db;
    private final SpecProfileService profiles;
    private final IsoServerManager servers;
    private final IsoOperationHandler handler;
    private final IsoStateRepository state;

    public IsoSimulatorService(JdbcClient db, SpecProfileService profiles,
                               IsoServerManager servers, IsoOperationHandler handler,
                               IsoStateRepository state) {
        this.db = db;
        this.profiles = profiles;
        this.servers = servers;
        this.handler = handler;
        this.state = state;
    }

    public record SimulatorView(UUID id, String name, int port, String status,
                                String specProfileName, String specProfileVersion) {}

    public List<SimulatorView> list() {
        return db.sql("""
                SELECT id, name, port, status, spec_profile_name, spec_profile_version
                FROM iso8583.simulators ORDER BY created_at
                """).query(SimulatorView.class).list();
    }

    public Optional<SimulatorView> find(UUID id) {
        return db.sql("""
                SELECT id, name, port, status, spec_profile_name, spec_profile_version
                FROM iso8583.simulators WHERE id = ?
                """).param(id).query(SimulatorView.class).optional();
    }

    @Transactional
    public SimulatorView create(String name, int port, String profileName, String profileVersion) {
        // Profil diverifikasi SEKARANG: simulator yang menunjuk profil rusak akan gagal
        // saat pesan pertama tiba — jauh lebih membingungkan daripada gagal saat dibuat.
        profiles.resolve(profileName, profileVersion);

        UUID id = UUID.randomUUID();
        db.sql("""
                INSERT INTO iso8583.simulators (id, name, port, spec_profile_name, spec_profile_version)
                VALUES (?, ?, ?, ?, ?)
                """)
                .param(id).param(name).param(port).param(profileName).param(profileVersion)
                .update();
        // Keunikan port lintas produk (bank 9001+, qris 9101+, iso 9201+) dijaga di DB —
        // bukan cek aplikasi yang punya celah race.
        db.sql("INSERT INTO platform.port_registry (port, product, simulator_id) VALUES (?, 'iso8583', ?)")
                .param(port).param(id).update();
        return find(id).orElseThrow();
    }

    public SimulatorView start(UUID id) {
        SimulatorView s = find(id).orElseThrow(() -> new IsoCodecException("Simulator tidak ditemukan"));
        ResolvedSpec spec = profiles.resolve(s.specProfileName(), s.specProfileVersion());

        servers.start(id, s.port(), spec,
                (simId, req) -> handler.handle(simId, spec, req),
                (simId, mti, reqHex, respHex, ms) ->
                        state.logExchange(simId, mti, null, null, reqHex, respHex, ms));

        db.sql("UPDATE iso8583.simulators SET status = 'RUNNING' WHERE id = ?").param(id).update();
        return find(id).orElseThrow();
    }

    public SimulatorView stop(UUID id) {
        servers.stop(id);
        db.sql("UPDATE iso8583.simulators SET status = 'STOPPED' WHERE id = ?").param(id).update();
        return find(id).orElseThrow();
    }

    @Transactional
    public void delete(UUID id) {
        servers.stop(id);
        db.sql("DELETE FROM platform.port_registry WHERE product = 'iso8583' AND simulator_id = ?")
                .param(id).update();
        db.sql("DELETE FROM iso8583.simulators WHERE id = ?").param(id).update();
    }

    /** Seed contoh: 2 rekening + 1 kartu, supaya simulator langsung bisa diuji. */
    @Transactional
    public Map<String, Object> seedDemo(UUID simulatorId) {
        state.addAccount(simulatorId, "1234567890", "Andi Sumber", new BigDecimal("1000000.00"), "360");
        state.addAccount(simulatorId, "9876543210", "Budi Tujuan", BigDecimal.ZERO, "360");
        state.addCard(simulatorId, "6281388370001", "1234567890");
        return Map.of("accounts", 2, "cards", 1);
    }
}
