package id.behavio.core.engine;

import id.behavio.core.domain.Account;
import id.behavio.core.domain.InsufficientFundsException;
import id.behavio.core.domain.Partner;
import id.behavio.core.domain.SignatureMode;
import id.behavio.core.domain.Transaction;
import id.behavio.core.domain.TransactionStatus;
import id.behavio.core.port.ConfigRepository;
import id.behavio.core.port.EventPublisher;
import id.behavio.core.port.SignatureVerifier;
import id.behavio.core.port.StateRepository;
import id.behavio.core.port.StoredResponse;
import id.behavio.core.port.WebhookSender;
import id.behavio.core.rule.Action;
import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Rule;
import id.behavio.core.rule.Scenario;
import id.behavio.core.rule.WebhookSpec;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Implementasi pipeline Behavior Engine (design.md §4). Murni — semua I/O via port.
 * Adapter web WAJIB memanggil {@link #handle} di dalam satu DB transaction agar
 * langkah aksi (debit + create txn + idempotensi) atomik (§4.1).
 */
public final class DefaultBehaviorEngine implements BehaviorEngine {

    private static final String H_PARTNER = "X-PARTNER-ID";
    private static final String H_EXTERNAL = "X-EXTERNAL-ID";
    private static final Map<String, String> JSON_HEADERS = Map.of("Content-Type", "application/json");

    private static final String H_SIGNATURE = "X-SIGNATURE";
    private static final String H_TIMESTAMP = "X-TIMESTAMP";
    private static final String H_AUTH = "Authorization";

    private final StateRepository state;
    private final ConfigRepository config;
    private final EventPublisher events;
    private final SignatureVerifier signatureVerifier; // nullable → mode STRICT tak dicek
    private final WebhookSender webhookSender;          // nullable → webhook dilewati
    private final ConditionEvaluator evaluator = new ConditionEvaluator();
    private final ResponseRenderer renderer = new ResponseRenderer();
    private final Supplier<String> referenceNoGen;
    private final Clock clock;

    public DefaultBehaviorEngine(StateRepository state, ConfigRepository config, EventPublisher events) {
        this(state, config, events, null, null, defaultRefGen(), Clock.systemUTC());
    }

    public DefaultBehaviorEngine(StateRepository state, ConfigRepository config, EventPublisher events,
                                 SignatureVerifier signatureVerifier, WebhookSender webhookSender) {
        this(state, config, events, signatureVerifier, webhookSender, defaultRefGen(), Clock.systemUTC());
    }

    public DefaultBehaviorEngine(StateRepository state, ConfigRepository config, EventPublisher events,
                                 SignatureVerifier signatureVerifier, WebhookSender webhookSender,
                                 Supplier<String> referenceNoGen, Clock clock) {
        this.state = state;
        this.config = config;
        this.events = events;
        this.signatureVerifier = signatureVerifier;
        this.webhookSender = webhookSender;
        this.referenceNoGen = referenceNoGen;
        this.clock = clock;
    }

    private static Supplier<String> defaultRefGen() {
        return () -> "BHV" + System.currentTimeMillis()
                + String.format("%04d", (int) (Math.random() * 10000));
    }

    @Override
    public SimResponse handle(UUID simulatorId, SimRequest request) {
        long start = System.nanoTime();
        SimResponse response = process(simulatorId, request);
        long durationMs = (System.nanoTime() - start) / 1_000_000;
        events.publishRequestEvent(new EventPublisher.RequestEvent(
                simulatorId.toString(), request.method(), request.path(),
                response.httpStatus(), response.responseCode(), durationMs));
        return response;
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

        // 1b. Signature (mode STRICT): verifikasi HMAC-SHA512 transaksional
        if (signatureVerifier != null
                && config.signatureMode(simulatorId) == SignatureMode.STRICT
                && !signatureValid(request, partner)) {
            return error(401, "4017300", "Unauthorized. Invalid Signature");
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
            response = renderResponse(outcome.response(), request, referenceNo);
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
            scheduleWebhook(simulatorId, outcome.webhook(), request, referenceNo);
        }

        // 8. Lampirkan efek fisik fault (diterapkan adapter web pasca-commit)
        if (fault != null && fault.hasPhysicalEffect()) {
            response = response.withFault(
                    new SimResponse.Fault(fault.delayMillis(), fault.drop(), fault.corrupt()));
        }
        return response;
    }

    private void scheduleWebhook(UUID simulatorId, WebhookSpec spec, SimRequest request, String referenceNo) {
        String url = request.header(spec.urlHeader());
        if (url == null || url.isBlank()) {
            return; // tak ada callback URL → lewati (log di adapter)
        }
        Map<String, Object> vars = new HashMap<>(request.fields());
        vars.put("referenceNo", referenceNo);
        Object amount = request.fields().get("amount");
        vars.put("amountValue", amount == null ? "" : amount.toString());
        vars.putIfAbsent("currency", "IDR");
        vars.put("transactionDate", OffsetDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        String body = renderer.render(spec.bodyTemplate(), vars);
        webhookSender.schedule(simulatorId, url, JSON_HEADERS, body, Duration.ofMillis(spec.delayMillis()));
    }

    private boolean signatureValid(SimRequest request, Partner partner) {
        String signature = request.header(H_SIGNATURE);
        String timestamp = request.header(H_TIMESTAMP);
        if (signature == null || timestamp == null || partner.clientSecret() == null) {
            return false;
        }
        String auth = request.header(H_AUTH);
        String token = auth == null ? "" : auth.replaceFirst("(?i)^Bearer\\s+", "");
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
                case Action.Debit d -> {
                    Account acc = requireAccount(simulatorId, partnerId, str(request, d.accountNoField()));
                    acc.debit(num(request, d.amountField()));
                    state.saveAccount(acc);
                }
                case Action.Credit c -> {
                    // intrabank: rekening tujuan internal bila ada; abaikan bila di luar bank
                    state.findAccount(simulatorId, partnerId, str(request, c.accountNoField()))
                            .ifPresent(acc -> {
                                acc.credit(num(request, c.amountField()));
                                state.saveAccount(acc);
                            });
                }
                case Action.CreateTransaction ct -> state.saveTransaction(new Transaction(
                        UUID.randomUUID(), simulatorId, partnerId,
                        referenceNo, str(request, "partnerReferenceNo"),
                        str(request, "sourceAccountNo"), str(request, "beneficiaryAccountNo"),
                        num(request, "amount"), strOr(request, "currency", "IDR"),
                        ct.status(), Instant.now(clock)));
            }
        }
    }

    private SimResponse renderResponse(ResponseSpec spec, SimRequest request, String referenceNo) {
        Map<String, Object> vars = new HashMap<>(request.fields());
        vars.put("referenceNo", referenceNo);
        vars.put("responseCode", spec.responseCode());
        vars.put("responseMessage", spec.responseMessage());
        Object amount = request.fields().get("amount");
        vars.put("amountValue", amount == null ? "" : amount.toString());
        vars.putIfAbsent("currency", "IDR");
        vars.put("transactionDate", OffsetDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        String body = renderer.render(spec.bodyTemplate(), vars);
        return new SimResponse(spec.httpStatus(), spec.responseCode(), JSON_HEADERS, body);
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
