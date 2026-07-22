package id.behavio.bank.engine;

import id.behavio.bank.blueprint.AccountInquiryInternalBlueprint;
import id.behavio.bank.blueprint.ExternalAccountInquiryBlueprint;
import id.behavio.bank.blueprint.TransferIntrabankBlueprint;
import id.behavio.bank.platform.core.engine.SimRequest;
import id.behavio.bank.platform.core.engine.SimResponse;
import id.behavio.bank.domain.Account;
import id.behavio.bank.platform.core.domain.Partner;
import id.behavio.bank.domain.Transaction;
import id.behavio.bank.platform.core.port.ConfigRepository;
import id.behavio.bank.port.StateRepository;
import id.behavio.bank.platform.core.port.StoredResponse;
import id.behavio.bank.platform.core.rule.Scenario;
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
    private DefaultBehaviorEngine engine;

    @BeforeEach
    void setup() {
        state = new FakeState();
        config = new FakeConfig(new Partner(PARTNER_UUID, SIM, PARTNER_HEADER, null, null));
        engine = new DefaultBehaviorEngine(state, config);
    }

    private void seed(String accountNo, String balance) {
        seed(accountNo, balance, "Holder");
    }

    private void seed(String accountNo, String balance, String holderName) {
        state.accounts.put(key(accountNo),
                new Account(UUID.randomUUID(), SIM, PARTNER_UUID, accountNo, holderName, "IDR", new BigDecimal(balance)));
    }

    private SimRequest inquiry(String path, Map<String, Object> extraFields) {
        Map<String, String> headers = new HashMap<>();
        headers.put("X-PARTNER-ID", PARTNER_HEADER);
        Map<String, Object> fields = new HashMap<>(extraFields);
        fields.put("partnerReferenceNo", "PREF-INQ");
        return new SimRequest("POST", path, headers, fields, "{}");
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

    /**
     * Regresi: {@code beneficiaryAccountName} & {@code beneficiaryAccountNo} pernah
     * terender STRING KOSONG padahal keduanya WAJIB di ASPI service 15 — penyebabnya
     * {@code enrichAccountVars} tak mengenali {@code beneficiaryAccountNo}, sehingga
     * satu-satunya guna operasi ini (mengembalikan nama pemilik rekening) tak jalan.
     */
    @Test
    void account_inquiry_internal_mengembalikan_nama_pemilik_bukan_string_kosong() {
        config.scenario = AccountInquiryInternalBlueprint.normal();
        seed("9876543210", "0", "Budi Tujuan");

        SimResponse res = engine.handle(SIM,
                inquiry(AccountInquiryInternalBlueprint.PATH, Map.of("beneficiaryAccountNo", "9876543210")));

        assertEquals(200, res.httpStatus());
        assertEquals("2001500", res.responseCode());
        assertTrue(res.body().contains("\"beneficiaryAccountName\":\"Budi Tujuan\""),
                "nama pemilik harus terisi, body: " + res.body());
        assertTrue(res.body().contains("\"beneficiaryAccountNo\":\"9876543210\""),
                "nomor rekening harus terisi, body: " + res.body());
    }

    /**
     * Penjaga urutan di {@code enrichAccountVars}: pada transfer, {@code holderName}
     * harus tetap milik rekening SUMBER. Kalau lookup beneficiary dinaikkan ke atas,
     * transfer diam-diam mulai menyebut nama penerima sebagai pengirim.
     */
    @Test
    void transfer_holderName_tetap_milik_rekening_sumber_bukan_penerima() {
        config.scenario = TransferIntrabankBlueprint.normal();
        seed("111", "100000", "Andi Sumber");
        seed("222", "0", "Budi Tujuan");

        Map<String, Object> vars = new HashMap<>();
        engine.handle(SIM, transfer("EXT-HOLDER", "111", "222", "1000"));

        // Template transfer tak menampilkan holderName, jadi diuji lewat inquiry pada
        // request yang membawa sourceAccountNo DAN beneficiaryAccountNo sekaligus.
        config.scenario = AccountInquiryInternalBlueprint.normal();
        vars.put("sourceAccountNo", "111");
        vars.put("beneficiaryAccountNo", "222");
        SimResponse res = engine.handle(SIM, inquiry(AccountInquiryInternalBlueprint.PATH, vars));

        assertTrue(res.body().contains("\"beneficiaryAccountName\":\"Andi Sumber\""),
                "sourceAccountNo harus menang atas beneficiaryAccountNo, body: " + res.body());
    }

    /** External inquiry (service 16) — rekening ada di bank LAIN, tak pernah ada di state. */
    @Test
    void account_inquiry_external_balas_2001600_dengan_field_wajib_aspi() {
        config.scenario = ExternalAccountInquiryBlueprint.normal();

        SimResponse res = engine.handle(SIM, inquiry(ExternalAccountInquiryBlueprint.PATH,
                Map.of("beneficiaryBankCode", "014", "beneficiaryAccountNo", "8877665544")));

        assertEquals(200, res.httpStatus());
        assertEquals("2001600", res.responseCode());
        assertTrue(res.body().contains("\"beneficiaryAccountNo\":\"8877665544\""), res.body());
        assertTrue(res.body().contains("\"beneficiaryBankCode\":\"014\""), res.body());
        // WAJIB di ASPI — tak boleh kosong walau rekeningnya bukan milik bank ini.
        assertFalse(res.body().contains("\"beneficiaryAccountName\":\"\""), res.body());
        assertTrue(res.body().contains("\"beneficiaryBankName\""), res.body());
        // Service 16 TIDAK punya field ini (beda dari service 15).
        assertFalse(res.body().contains("beneficiaryAccountStatus"), res.body());
        assertFalse(res.body().contains("beneficiaryAccountType"), res.body());
    }

    @Test
    void normal_transfer_sukses_debit_credit_dan_catat_transaksi() {
        config.scenario = TransferIntrabankBlueprint.normal();
        seed("111", "100000");
        seed("222", "0");

        SimResponse res = engine.handle(SIM, transfer("EXT-1", "111", "222", "30000"));

        assertEquals(200, res.httpStatus());
        assertEquals("2001700", res.responseCode());
        assertTrue(res.body().contains("2001700"));
        assertTrue(res.body().contains("referenceNo"));
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("70000")));
        assertEquals(0, state.accounts.get(key("222")).balance().compareTo(new BigDecimal("30000")));
        assertEquals(1, state.transactions.size());
        assertEquals("SUCCESS", state.transactions.get(0).status().name());
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
        assertEquals("4031700", over.responseCode());
        assertEquals(0, state.accounts.get(key("111")).balance().compareTo(new BigDecimal("100000000")));

        SimResponse under = engine.handle(SIM, transfer("EXT-UNDER", "111", "222", "1000000"));
        assertEquals(200, under.httpStatus());
        assertEquals("2001700", under.responseCode());
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
        public List<Transaction> findTransactions(UUID sim, UUID partner, java.time.Instant from,
                                                  java.time.Instant to, int limit, int offset) {
            return transactions.stream()
                    .filter(t -> t.partnerId().equals(partner))
                    .skip(offset).limit(limit).toList();
        }
    }

    private static final class FakeConfig implements ConfigRepository {
        final Partner partner;
        Scenario scenario;
        FakeConfig(Partner partner) { this.partner = partner; }
        public Optional<Partner> findPartner(UUID sim, String partnerHeaderId) {
            return partner.partnerId().equals(partnerHeaderId) ? Optional.of(partner) : Optional.empty();
        }
        /**
         * Melayani path mana pun yang dipakai tes: yang diuji di sini pipeline engine,
         * bukan routing (routing punya registry sendiri). Sebelumnya path di-hardcode ke
         * transfer, sehingga operasi lain diam-diam balas 404 alih-alih menjalankan
         * scenario yang sedang dipasang.
         */
        public Optional<Scenario> findActiveScenario(UUID sim, String method, String path) {
            return Optional.ofNullable(scenario);
        }
        public id.behavio.bank.platform.core.domain.SignatureMode signatureMode(UUID sim) {
            return id.behavio.bank.platform.core.domain.SignatureMode.SIMULATED;
        }
    }

}
