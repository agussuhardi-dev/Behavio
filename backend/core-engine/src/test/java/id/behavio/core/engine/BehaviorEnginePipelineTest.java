package id.behavio.core.engine;

import id.behavio.core.blueprint.TransferIntrabankBlueprint;
import id.behavio.core.domain.Account;
import id.behavio.core.domain.Partner;
import id.behavio.core.domain.Transaction;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.EventPublisher;
import id.behavio.core.port.StateRepository;
import id.behavio.core.port.StoredResponse;
import id.behavio.core.rule.Scenario;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Uji pipeline Behavior Engine terisolasi (tanpa Spring/DB) — slice Transfer Intrabank. */
class BehaviorEnginePipelineTest {

    private final UUID SIM = UUID.randomUUID();
    private final UUID PARTNER_UUID = UUID.randomUUID();
    private static final String PARTNER_HEADER = "PARTNER001";

    private FakeState state;
    private FakeConfig config;
    private FakeEvents events;
    private DefaultBehaviorEngine engine;

    @BeforeEach
    void setup() {
        state = new FakeState();
        config = new FakeConfig(new Partner(PARTNER_UUID, SIM, PARTNER_HEADER, null, null));
        events = new FakeEvents();
        engine = new DefaultBehaviorEngine(state, config, events);
    }

    private void seed(String accountNo, String balance) {
        state.accounts.put(key(accountNo),
                new Account(UUID.randomUUID(), SIM, PARTNER_UUID, accountNo, "Holder", "IDR", new BigDecimal(balance)));
    }

    private String key(String accountNo) { return PARTNER_UUID + "/" + accountNo; }

    private SimRequest transfer(String externalId, String source, String benef, String amount) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-PARTNER-ID", PARTNER_HEADER);
        if (externalId != null) headers.put("X-EXTERNAL-ID", externalId);
        Map<String, Object> fields = new HashMap<>();
        fields.put("sourceAccountNo", source);
        fields.put("beneficiaryAccountNo", benef);
        fields.put("amount", new BigDecimal(amount));
        fields.put("partnerReferenceNo", "PREF-" + amount);
        fields.put("currency", "IDR");
        return new SimRequest(TransferIntrabankBlueprint.METHOD, TransferIntrabankBlueprint.PATH,
                headers, fields, "{}");
    }

    @Test
    void normal_transfer_sukses_debit_credit_dan_catat_transaksi() {
        config.scenario = TransferIntrabankBlueprint.normal();
        seed("111", "100000");
        seed("222", "0");

        SimResponse res = engine.handle(SIM, transfer("EXT-1", "111", "222", "30000"));

        assertEquals(200, res.httpStatus());
        assertEquals("2001800", res.responseCode());
        assertTrue(res.body().contains("2001800"));
        assertTrue(res.body().contains("referenceNo"));
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("70000")));
        assertEquals(0, state.accounts.get(key("222")).balance().compareTo(new BigDecimal("30000")));
        assertEquals(1, state.transactions.size());
        assertEquals("SUCCESS", state.transactions.get(0).status().name());
        assertEquals(1, events.list.size());
    }

    @Test
    void normal_transfer_saldo_kurang_ditolak_saldo_utuh() {
        config.scenario = TransferIntrabankBlueprint.normal();
        seed("111", "10000");
        seed("222", "0");

        SimResponse res = engine.handle(SIM, transfer("EXT-2", "111", "222", "20000"));

        assertEquals(400, res.httpStatus());
        assertEquals("4001714", res.responseCode());
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("10000")));
        assertEquals(0, state.accounts.get(key("222")).balance().compareTo(BigDecimal.ZERO));
        assertTrue(state.transactions.isEmpty());
    }

    @Test
    void idempotensi_request_ulang_tidak_debit_dua_kali() {
        config.scenario = TransferIntrabankBlueprint.normal();
        seed("111", "100000");
        seed("222", "0");

        SimResponse first = engine.handle(SIM, transfer("EXT-DUP", "111", "222", "30000"));
        SimResponse second = engine.handle(SIM, transfer("EXT-DUP", "111", "222", "30000"));

        assertEquals(first.body(), second.body());
        assertEquals(first.responseCode(), second.responseCode());
        // saldo hanya terpotong sekali
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("70000")));
        assertEquals(1, state.transactions.size());
    }

    @Test
    void scenario_saldo_kurang_paksa_selalu_tolak_tanpa_ubah_state() {
        config.scenario = TransferIntrabankBlueprint.forcedInsufficient();
        seed("111", "100000");
        seed("222", "0");

        SimResponse res = engine.handle(SIM, transfer("EXT-3", "111", "222", "30000"));

        assertEquals(400, res.httpStatus());
        assertEquals("4001714", res.responseCode());
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("100000")));
        assertTrue(state.transactions.isEmpty());
    }

    @Test
    void scenario_limit_tolak_di_atas_batas_lolos_di_bawah() {
        config.scenario = TransferIntrabankBlueprint.limit(); // batas 25 juta
        seed("111", "100000000");
        seed("222", "0");

        SimResponse over = engine.handle(SIM, transfer("EXT-OVER", "111", "222", "30000000"));
        assertEquals(403, over.httpStatus());
        assertEquals("4031800", over.responseCode());
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("100000000")));

        SimResponse under = engine.handle(SIM, transfer("EXT-UNDER", "111", "222", "1000000"));
        assertEquals(200, under.httpStatus());
        assertEquals("2001800", under.responseCode());
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("99000000")));
    }

    @Test
    void header_partner_wajib_dan_partner_tak_dikenal_ditolak() {
        config.scenario = TransferIntrabankBlueprint.normal();
        seed("111", "100000");

        // tanpa X-PARTNER-ID
        SimRequest noHeader = transfer("EXT-4", "111", "222", "1000");
        noHeader.headers().remove("X-PARTNER-ID");
        SimResponse r1 = engine.handle(SIM, noHeader);
        assertEquals(400, r1.httpStatus());
        assertEquals("4001702", r1.responseCode());

        // partner tak dikenal
        Map<String, String> h = new HashMap<>();
        h.put("X-PARTNER-ID", "UNKNOWN");
        SimRequest badPartner = new SimRequest(TransferIntrabankBlueprint.METHOD, TransferIntrabankBlueprint.PATH,
                h, transfer(null, "111", "222", "1000").fields(), "{}");
        SimResponse r2 = engine.handle(SIM, badPartner);
        assertEquals(401, r2.httpStatus());
        assertEquals("4011700", r2.responseCode());
    }

    // ---------------- fakes ----------------

    private final class FakeState implements StateRepository {
        final Map<String, Account> accounts = new HashMap<>();
        final List<Transaction> transactions = new ArrayList<>();
        final Map<String, StoredResponse> idempo = new HashMap<>();

        public Optional<Account> findAccount(UUID sim, UUID partner, String accountNo) {
            return Optional.ofNullable(accounts.get(partner + "/" + accountNo));
        }
        public void saveAccount(Account account) {
            accounts.put(account.partnerId() + "/" + account.accountNo(), account);
        }
        public void saveTransaction(Transaction transaction) { transactions.add(transaction); }
        public boolean externalIdExists(UUID sim, UUID partner, String externalId) {
            return idempo.containsKey(partner + "/" + externalId);
        }
        public void recordExternalId(UUID sim, UUID partner, String externalId, StoredResponse response) {
            idempo.put(partner + "/" + externalId, response);
        }
        public Optional<StoredResponse> findStoredResponse(UUID sim, UUID partner, String externalId) {
            return Optional.ofNullable(idempo.get(partner + "/" + externalId));
        }
    }

    private static final class FakeConfig implements ConfigRepository {
        final Partner partner;
        Scenario scenario;
        FakeConfig(Partner partner) { this.partner = partner; }
        public Optional<Partner> findPartner(UUID sim, String partnerHeaderId) {
            return partner.partnerId().equals(partnerHeaderId) ? Optional.of(partner) : Optional.empty();
        }
        public Optional<Scenario> findActiveScenario(UUID sim, String method, String path) {
            boolean match = TransferIntrabankBlueprint.METHOD.equals(method)
                    && TransferIntrabankBlueprint.PATH.equals(path);
            return match ? Optional.of(scenario) : Optional.empty();
        }
    }

    private static final class FakeEvents implements EventPublisher {
        final List<RequestEvent> list = new ArrayList<>();
        public void publishRequestEvent(RequestEvent event) { list.add(event); }
    }
}
