package id.behavio.bank.engine;

import id.behavio.bank.domain.Account;
import id.behavio.bank.platform.core.engine.BehaviorEngine;
import id.behavio.bank.platform.core.engine.ConditionEvaluator;
import id.behavio.bank.platform.core.engine.EvalContext;
import id.behavio.bank.platform.core.engine.Json;
import id.behavio.bank.platform.core.engine.ResponseRenderer;
import id.behavio.bank.platform.core.engine.SimRequest;
import id.behavio.bank.platform.core.engine.SimResponse;
import id.behavio.bank.domain.InsufficientFundsException;
import id.behavio.bank.platform.core.domain.Partner;
import id.behavio.bank.platform.core.domain.SignatureMode;
import id.behavio.bank.domain.Transaction;
import id.behavio.bank.domain.TransactionStatus;
import id.behavio.bank.platform.core.port.AccessTokenStore;
import id.behavio.bank.platform.core.port.ConfigRepository;
import id.behavio.bank.platform.core.port.SignatureVerifier;
import id.behavio.bank.port.StateRepository;
import id.behavio.bank.platform.core.port.StoredResponse;
import id.behavio.bank.platform.core.port.WebhookSender;
import id.behavio.bank.platform.core.port.WebhookSubscriptions;
import id.behavio.bank.rule.BankAction;
import id.behavio.bank.platform.core.rule.Action;
import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Rule;
import id.behavio.bank.platform.core.rule.Scenario;
import id.behavio.bank.platform.core.rule.WebhookSpec;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.ZoneId;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Implementasi pipeline Behavior Engine (design.md §4). Murni — semua I/O via port.
 * Adapter web WAJIB memanggil {@link #handle} di dalam satu DB transaction agar
 * langkah aksi (debit + create txn + idempotensi) atomik (§4.1).
 */
public final class DefaultBehaviorEngine implements BehaviorEngine {

    /** Nama koleksi {@code @each} yang bisa disediakan engine bank (lihat enrichCollections). */
    private static final String VAR_TRANSACTIONS = "transactions";

    private static final String H_PARTNER = "X-PARTNER-ID";
    private static final String H_EXTERNAL = "X-EXTERNAL-ID";
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    private static final String H_SIGNATURE = "X-SIGNATURE";
    private static final String H_TIMESTAMP = "X-TIMESTAMP";
    private static final String H_AUTH = "Authorization";

    private final StateRepository state;
    private final ConfigRepository config;
    private final SignatureVerifier signatureVerifier; // nullable → mode STRICT tak dicek
    private final WebhookSender webhookSender;          // nullable → webhook dilewati
    private final WebhookSubscriptions subscriptions;   // nullable → webhook dilewati (§9.1)
    private final AccessTokenStore accessTokenStore;    // nullable → expiry token tak dicek
    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final ResponseRenderer renderer = new ResponseRenderer();
    private final Supplier<String> referenceNoGen;
    private final Clock clock;

    /**
     * Timestamp SNAP: ISO-8601 ber-offset presisi detik (mis. 2025-10-02T05:51:05+07:00).
     * ISO_OFFSET_DATE_TIME + Clock.system(SNAP_ZONE) menghasilkan "…Z" berikut nanodetik —
     * di luar format SNAP (25 char). Zona WIB dipakai sebagai default simulator.
     */
    private static final DateTimeFormatter SNAP_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneId SNAP_ZONE = ZoneId.of("Asia/Jakarta");

    public DefaultBehaviorEngine(StateRepository state, ConfigRepository config) {
        this(state, config, null, null, null, null, defaultRefGen(), Clock.system(SNAP_ZONE));
    }

    public DefaultBehaviorEngine(StateRepository state, ConfigRepository config,
                                 SignatureVerifier signatureVerifier, WebhookSender webhookSender) {
        this(state, config, signatureVerifier, webhookSender, null, null,
                defaultRefGen(), Clock.system(SNAP_ZONE));
    }

    public DefaultBehaviorEngine(StateRepository state, ConfigRepository config,
                                 SignatureVerifier signatureVerifier, WebhookSender webhookSender,
                                 WebhookSubscriptions subscriptions, AccessTokenStore accessTokenStore) {
        this(state, config, signatureVerifier, webhookSender, subscriptions, accessTokenStore,
                defaultRefGen(), Clock.system(SNAP_ZONE));
    }

    public DefaultBehaviorEngine(StateRepository state, ConfigRepository config,
                                 SignatureVerifier signatureVerifier, WebhookSender webhookSender,
                                 WebhookSubscriptions subscriptions, AccessTokenStore accessTokenStore,
                                 Supplier<String> referenceNoGen, Clock clock) {
        this.state = state;
        this.config = config;
        this.signatureVerifier = signatureVerifier;
        this.webhookSender = webhookSender;
        this.subscriptions = subscriptions;
        this.accessTokenStore = accessTokenStore;
        this.referenceNoGen = referenceNoGen;
        this.clock = clock;
    }

    private static Supplier<String> defaultRefGen() {
        return () -> "BHV" + System.currentTimeMillis()
                + String.format("%04d", (int) (Math.random() * 10000));
    }

    /**
     * Engine TIDAK lagi meng-emit RequestEvent sendiri: sejak pemisahan produk, seluruh
     * operasi (transfer, VA, QRIS, access-token) di-emit satu jalur oleh
     * {@code SimulatorServerManager} setelah respons ditulis. Dulu hanya transfer yang
     * emit dari sini — di DALAM transaksi bisnis — sementara operasi lain emit dari
     * lapisan web, sehingga request yang transaksinya rollback tak pernah muncul di
     * Live View justru saat paling ingin dilihat.
     */
    @Override
    public SimResponse handle(UUID simulatorId, SimRequest request) {
        return process(simulatorId, request);
    }

    private SimResponse process(UUID simulatorId, SimRequest request) {
        // 1. Partner (isolasi tenant) dari header
        String partnerHeader = request.header(H_PARTNER);
        if (partnerHeader == null || partnerHeader.isBlank()) {
            return error(400, "4001702", "Invalid Mandatory Field X-PARTNER-ID");
        }
        Optional<Partner> partnerOpt = config.findPartner(simulatorId, partnerHeader);
        if (partnerOpt.isEmpty()) {
            return error(401, "4011700", "Unauthorized. Unknown partner");
        }
        Partner partner = partnerOpt.get();

        // 1b. Mode STRICT: token Bearer harus valid & belum expired, DAN signature
        // HMAC-SHA512 transaksional harus cocok. Signature saja tidak cukup — token
        // acak/kedaluwarsa yang konsisten dipakai klien tetap bisa hasilkan HMAC
        // valid (HMAC hanya menjamin integritas data, bukan keabsahan token).
        if (signatureVerifier != null && config.signatureMode(simulatorId) == SignatureMode.STRICT) {
            if (accessTokenStore != null && !accessTokenStore.isValid(simulatorId, partner.id(), bearerToken(request))) {
                return error(401, "4017301", "Unauthorized. Token invalid or expired");
            }
            if (!signatureValid(request, partner)) {
                return error(401, "4017300", "Unauthorized. Invalid Signature");
            }
        }

        // 2. Idempotensi (SNAP X-EXTERNAL-ID) — balas respons tersimpan bila ada
        String externalId = request.header(H_EXTERNAL);
        if (externalId != null && !externalId.isBlank()) {
            Optional<StoredResponse> stored = state.findStoredResponse(simulatorId, partner.id(), externalId);
            if (stored.isPresent()) {
                StoredResponse s = stored.get();
                return new SimResponse(s.httpStatus(), s.responseCode(), JSON_HEADERS, s.body());
            }
        }

        // 3. Scenario aktif untuk endpoint
        Optional<Scenario> scenarioOpt = config.findActiveScenario(simulatorId, request.method(), request.path());
        if (scenarioOpt.isEmpty()) {
            return error(404, "4040001", "No active scenario for endpoint");
        }
        Scenario scenario = scenarioOpt.get();

        // 4. Context + rule first-match
        EvalContext ctx = new EvalContext(request.fields(),
                accNo -> state.findAccount(simulatorId, partner.id(), accNo).map(Account::balance).orElse(null));
        Outcome outcome = pickOutcome(scenario, ctx);

        // 5. Eksekusi aksi + render response (atomik di batas adapter).
        //    Fault titik A (BEFORE_ACTIONS) → aksi dilewati (saldo utuh);
        //    titik B (AFTER_ACTIONS) → aksi jalan lalu efek fisik pasca-commit.
        FaultSpec fault = outcome.fault();
        boolean runActions = fault == null || fault.point() == FaultSpec.Point.AFTER_ACTIONS;
        String referenceNo = referenceNoGen.get();
        SimResponse response;
        boolean actionsSucceeded = false;
        try {
            if (runActions) {
                applyActions(simulatorId, partner.id(), outcome, request, referenceNo);
            }
            actionsSucceeded = true;
            response = renderResponse(outcome.response(), request, referenceNo, simulatorId, partner.id());
        } catch (InsufficientFundsException e) {
            response = error(400, "4001714", "Insufficient Funds");
        } catch (AccountNotFoundException e) {
            response = error(404, "4041712", "Invalid Account. " + e.getMessage());
        }

        // 6. Simpan untuk idempotensi (respons BERSIH, tanpa directive fisik →
        //    saat replay klien menerima respons yang seharusnya, mis. setelah drop).
        if (externalId != null && !externalId.isBlank()) {
            state.recordExternalId(simulatorId, partner.id(), externalId,
                    new StoredResponse(response.httpStatus(), response.responseCode(), response.body()));
        }

        // 7. Jadwalkan webhook async (enqueue outbox dalam transaksi ini)
        if (actionsSucceeded && outcome.webhook() != null && webhookSender != null) {
            scheduleWebhook(simulatorId, partner.id(), outcome.webhook(), request, referenceNo);
        }

        // 8. Lampirkan efek fisik fault (diterapkan adapter web pasca-commit)
        if (fault != null && fault.hasPhysicalEffect()) {
            response = response.withFault(
                    new SimResponse.Fault(fault.delayMillis(), fault.drop(), fault.corrupt()));
        }
        return response;
    }

    /**
     * URL tujuan di-resolve dari registrasi partner (design.md §9.1), bukan dari header
     * request. Partner yang tak mendaftarkan URL → webhook dilewati diam-diam, sama
     * seperti bank sungguhan yang tak punya alamat notifikasi untuk dituju.
     */
    private void scheduleWebhook(UUID simulatorId, UUID partnerId, WebhookSpec spec,
                                 SimRequest request, String referenceNo) {
        if (subscriptions == null) {
            return; // engine dirakit tanpa registrasi (mis. unit test) → webhook dilewati
        }
        Optional<String> url = subscriptions.resolveUrl(simulatorId, partnerId, spec.event());
        if (url.isEmpty()) {
            return; // partner belum mendaftarkan URL untuk event ini
        }
        Map<String, Object> vars = new HashMap<>(request.fields());
        vars.put("referenceNo", referenceNo);
        Object amount = request.fields().get("amount");
        vars.put("amountValue", amount == null ? "" : amount.toString());
        vars.putIfAbsent("currency", "IDR");
        vars.put("transactionDate", OffsetDateTime.now(clock).format(SNAP_TS));
        String body = renderer.render(spec.bodyTemplate(), vars);
        webhookSender.schedule(simulatorId, url.get(), JSON_HEADERS, body, Duration.ofMillis(spec.delayMillis()));
    }

    private static String bearerToken(SimRequest request) {
        String auth = request.header(H_AUTH);
        return auth == null ? "" : auth.replaceFirst("(?i)^Bearer\\s+", "");
    }

    private boolean signatureValid(SimRequest request, Partner partner) {
        String signature = request.header(H_SIGNATURE);
        String timestamp = request.header(H_TIMESTAMP);
        if (signature == null || timestamp == null || partner.clientSecret() == null) {
            return false;
        }
        String token = bearerToken(request);
        return signatureVerifier.verifySymmetric(request.method(), request.path(), token,
                request.rawBody(), timestamp, signature, partner.clientSecret());
    }

    private Outcome pickOutcome(Scenario scenario, EvalContext ctx) {
        for (Rule rule : scenario.rules()) {
            if (evaluator.evaluate(rule.when(), ctx)) {
                return rule.then();
            }
        }
        return scenario.fallback();
    }

    private void applyActions(UUID simulatorId, UUID partnerId, Outcome outcome,
                              SimRequest request, String referenceNo) {
        for (Action action : outcome.actions()) {
            switch (action) {
                case BankAction.Debit d -> {
                    Account acc = requireAccount(simulatorId, partnerId, str(request, d.accountNoField()));
                    acc.debit(num(request, d.amountField()));
                    state.saveAccount(acc);
                }
                case BankAction.Credit c -> {
                    // intrabank: rekening tujuan internal bila ada; abaikan bila di luar bank
                    state.findAccount(simulatorId, partnerId, str(request, c.accountNoField()))
                            .ifPresent(acc -> {
                                acc.credit(num(request, c.amountField()));
                                state.saveAccount(acc);
                            });
                }
                case BankAction.CreateTransaction ct -> state.saveTransaction(new Transaction(
                        UUID.randomUUID(), simulatorId, partnerId,
                        referenceNo, str(request, "partnerReferenceNo"),
                        str(request, "sourceAccountNo"), str(request, "beneficiaryAccountNo"),
                        num(request, "amount"), strOr(request, "currency", "IDR"),
                        ct.status(), Instant.now(clock)));
                // Action kini penanda non-sealed (kosakata aksi milik produk), jadi switch
                // ini tak lagi ekshaustif secara tipe. Aksi milik produk lain tak boleh
                // diam-diam dilewati — itu artinya scenario salah kamar.
                default -> throw new IllegalStateException(
                        "Aksi bukan milik produk bank: " + action.getClass().getName());
            }
        }
    }

    private SimResponse renderResponse(ResponseSpec spec, SimRequest request, String referenceNo,
                                        UUID simulatorId, UUID partnerId) {
        Map<String, Object> vars = new HashMap<>(request.fields());
        vars.put("referenceNo", referenceNo);
        vars.put("responseCode", spec.responseCode());
        vars.put("responseMessage", spec.responseMessage());
        Object amount = request.fields().get("amount");
        vars.put("amountValue", amount == null ? "" : amount.toString());
        vars.putIfAbsent("currency", "IDR");
        vars.put("transactionDate", OffsetDateTime.now(clock).format(SNAP_TS));
        enrichAccountVars(simulatorId, partnerId, vars);
        enrichCollections(simulatorId, partnerId, spec, request, vars);
        String body = renderer.render(spec.bodyTemplate(), vars);
        return new SimResponse(spec.httpStatus(), spec.responseCode(), JSON_HEADERS, body);
    }

    /**
     * Sediakan koleksi yang DIMINTA template lewat {@code @each}. Templatelah yang
     * mendeklarasikan kebutuhannya, jadi engine tak perlu menebak dari path/operasi —
     * penting karena path endpoint boleh diganti user (design.md §7). Template yang tak
     * meminta apa pun = nol query.
     */
    private void enrichCollections(UUID simulatorId, UUID partnerId, ResponseSpec spec,
                                   SimRequest request, Map<String, Object> vars) {
        Set<String> needed = ResponseRenderer.requiredCollections(spec.bodyTemplate());
        if (needed.contains(VAR_TRANSACTIONS) && !vars.containsKey(VAR_TRANSACTIONS)) {
            vars.put(VAR_TRANSACTIONS, transactionRows(simulatorId, partnerId, request));
        }
    }

    /**
     * Riwayat transaksi (service 12) sebagai baris siap-render. Rentang & paging diambil
     * dari request; bila {@code fromDateTime}/{@code toDateTime} tak dikirim, dipakai
     * 30 hari terakhir — sama seperti bank sungguhan yang punya rentang bawaan.
     */
    private List<Map<String, Object>> transactionRows(UUID simulatorId, UUID partnerId, SimRequest request) {
        int pageSize = intOr(request, "pageSize", 10);
        if (pageSize <= 0) pageSize = 10;
        pageSize = Math.min(50, pageSize);
        int pageNumber = Math.max(0, intOr(request, "pageNumber", 0));

        Instant now = Instant.now(clock);
        Instant from = parseInstant(str(request, "fromDateTime"), now.minus(30, ChronoUnit.DAYS));
        Instant to = parseInstant(str(request, "toDateTime"), now);

        List<Transaction> found = state.findTransactions(
                simulatorId, partnerId, from, to, pageSize, pageNumber * pageSize);

        List<Map<String, Object>> rows = new ArrayList<>(found.size());
        for (Transaction txn : found) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dateTime", txn.createdAt() == null ? ""
                    : OffsetDateTime.ofInstant(txn.createdAt(), SNAP_ZONE).format(SNAP_TS));
            row.put("amountValue", txn.amount() == null ? "0" : txn.amount().toPlainString());
            row.put("currency", txn.currency() == null ? "IDR" : txn.currency());
            row.put("remark", "Transfer " + txn.referenceNo());
            row.put("referenceNo", txn.referenceNo());
            row.put("partnerReferenceNo", strOrEmpty(txn.partnerReferenceNo()));
            row.put("sourceAccountNo", strOrEmpty(txn.sourceAccountNo()));
            row.put("beneficiaryAccountNo", strOrEmpty(txn.beneficiaryAccountNo()));
            // Status transaksi GAGAL dilaporkan CANCELLED (kosakata SNAP service 12);
            // selain itu SUCCESS. Tak boleh melaporkan status yang bukan status aslinya.
            boolean failed = txn.status() != null && "FAILED".equals(txn.status().name());
            row.put("txnStatus", failed ? "CANCELLED" : "SUCCESS");
            row.put("txnType", "PAYMENT");
            rows.add(row);
        }
        return rows;
    }

    private Instant parseInstant(String value, Instant fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException e) {
            return fallback;
        }
    }

    private static int intOr(SimRequest request, String field, int fallback) {
        Object v = request.fields().get(field);
        if (v instanceof Number n) return n.intValue();
        if (v == null) return fallback;
        try {
            return Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String strOrEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Isi {@code holderName}/{@code accountNo} dari state agar template response bisa
     * menyebut nama pemilik rekening.
     *
     * Urutan menentukan siapa pemilik {@code holderName} saat sebuah request menyebut
     * beberapa rekening sekaligus, dan {@code putIfAbsent} membuat yang PERTAMA menang:
     * {@code accountNo} (balance-inquiry) → {@code sourceAccountNo} (transfer, pengirim)
     * → {@code beneficiaryAccountNo} (inquiry, penerima). Beneficiary sengaja TERAKHIR:
     * pada transfer, {@code holderName} harus tetap milik rekening SUMBER.
     *
     * Lookup beneficiary dulu tak ada, sehingga {@code account-inquiry-internal} —
     * yang requestnya hanya mengirim {@code beneficiaryAccountNo} — merender
     * {@code beneficiaryAccountName} sebagai string kosong, padahal field itu WAJIB di
     * ASPI (service 15). Seluruh guna operasi itu justru mengembalikan nama pemilik.
     */
    private void enrichAccountVars(UUID simulatorId, UUID partnerId, Map<String, Object> vars) {
        String accountNo = (String) vars.get("accountNo");
        if (accountNo != null && !accountNo.isBlank()) {
            state.findAccount(simulatorId, partnerId, accountNo).ifPresent(acc -> {
                vars.putIfAbsent("holderName", acc.holderName());
                vars.putIfAbsent("accountNo", acc.accountNo());
                // Saldo HANYA bisa datang dari state — request balance-inquiry (service 11)
                // tak punya field `amount`. Blueprint dulu memakai {{amountValue}}, yang
                // diikat dari request, sehingga saldo SELALU dirender "" walau rekening
                // berisi: satu-satunya endpoint yang gunanya melaporkan saldo tak pernah
                // bisa melaporkannya. Var terpisah, bukan menumpang `amountValue`, supaya
                // "nominal yang diminta request" dan "saldo rekening" tak pernah tertukar.
                vars.putIfAbsent("balanceValue", acc.balance() == null
                        ? "0" : acc.balance().toPlainString());
                vars.putIfAbsent("balanceCurrency", acc.currency());
            });
        }
        String source = (String) vars.get("sourceAccountNo");
        if (source != null && !source.isBlank() && !source.equals(accountNo)) {
            state.findAccount(simulatorId, partnerId, source).ifPresent(acc -> {
                vars.putIfAbsent("holderName", acc.holderName());
            });
        }
        String beneficiary = (String) vars.get("beneficiaryAccountNo");
        if (beneficiary != null && !beneficiary.isBlank()) {
            state.findAccount(simulatorId, partnerId, beneficiary).ifPresent(acc -> {
                vars.putIfAbsent("holderName", acc.holderName());
            });
        }
    }

    private Account requireAccount(UUID simulatorId, UUID partnerId, String accountNo) {
        return state.findAccount(simulatorId, partnerId, accountNo)
                .orElseThrow(() -> new AccountNotFoundException(accountNo));
    }

    private SimResponse error(int status, String code, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("responseCode", code);
        body.put("responseMessage", message);
        return new SimResponse(status, code, JSON_HEADERS, Json.write(body));
    }

    private static String str(SimRequest r, String field) {
        Object v = r.fields().get(field);
        return v == null ? null : v.toString();
    }

    private static String strOr(SimRequest r, String field, String def) {
        String v = str(r, field);
        return v == null ? def : v;
    }

    private static BigDecimal num(SimRequest r, String field) {
        Object v = r.fields().get(field);
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof String s && !s.isBlank()) return new BigDecimal(s.trim());
        throw new IllegalArgumentException("field numerik tak valid: " + field);
    }

    /** Rekening yang dipakai aksi tidak ditemukan (state). */
    private static final class AccountNotFoundException extends RuntimeException {
        AccountNotFoundException(String accountNo) { super(accountNo); }
    }
}
