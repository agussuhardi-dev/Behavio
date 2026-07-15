package id.behavio.bank.blueprint;

import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk endpoint Balance Inquiry (service 11).
 * Tiga scenario: Normal, Bank Down (503), Timeout (delay 5 detik).
 */
public final class BalanceInquiryBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/balance-inquiry";
    public static final String RC_SUCCESS = "2001100";
    public static final String RC_SERVICE_DOWN = "5030000";

    private BalanceInquiryBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5). {@code accountNo} merujuk
     * rekening seed bersaldo agar contoh langsung sukses dari Postman.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerReferenceNo", "2026071500000000000003");
        body.put("accountNo", "1234567890");
        body.put("balanceTypes", List.of("Cash"));
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), normalResponse()));
    }

    public static Scenario bankDown() {
        return new Scenario("Bank Down", List.of(),
                Outcome.of(errorResponse(503, RC_SERVICE_DOWN, "Service Unavailable")));
    }

    public static Scenario timeout() {
        return timeout(5000);
    }

    public static Scenario timeout(long delayMillis) {
        return new Scenario("Timeout", List.of(),
                Outcome.withFault(List.of(), normalResponse(), FaultSpec.delayAfter(delayMillis)));
    }

    private static ResponseSpec normalResponse() {
        // {{balanceValue}}/{{balanceCurrency}} diikat dari rekening di state (engine
        // enrichAccountVars), BUKAN dari request — request service 11 tak punya `amount`.
        // Memakai {{amountValue}} di sini adalah bug lama: saldo selalu terender "".
        Map<String, Object> accountInfo = new LinkedHashMap<>();
        accountInfo.put("balanceType", "Cash");
        accountInfo.put("amount", Map.of("value", "{{balanceValue}}", "currency", "{{balanceCurrency}}"));
        accountInfo.put("floatAmount", Map.of("value", "0", "currency", "{{balanceCurrency}}"));
        accountInfo.put("holdAmount", Map.of("value", "0", "currency", "{{balanceCurrency}}"));
        accountInfo.put("availableBalance", Map.of("value", "{{balanceValue}}", "currency", "{{balanceCurrency}}"));
        accountInfo.put("ledgerBalance", Map.of("value", "{{balanceValue}}", "currency", "{{balanceCurrency}}"));
        accountInfo.put("currentMultilateralLimit", Map.of("value", "0", "currency", "{{balanceCurrency}}"));
        accountInfo.put("registrationStatusCode", "0001");
        accountInfo.put("status", "0001");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("accountNo", "{{accountNo}}");
        body.put("name", "{{holderName}}");
        body.put("accountInfos", List.of(accountInfo));

        return new ResponseSpec(200, RC_SUCCESS, "Successful", body);
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}
