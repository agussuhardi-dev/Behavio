package id.behavio.bank.blueprint;

import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk endpoint Internal Account Inquiry (service 15).
 * Tiga scenario: Normal, Bank Down (503), Timeout (delay 5 detik).
 */
public final class AccountInquiryInternalBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/account-inquiry-internal";
    public static final String RC_SUCCESS = "2001500";
    public static final String RC_SERVICE_DOWN = "5030000";

    private AccountInquiryInternalBlueprint() {}

    /**
     * Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A.3). Rekening
     * merujuk data seed agar contoh langsung sukses dari Postman.
     */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerReferenceNo", "2026071500000000000002");
        body.put("beneficiaryAccountNo", "9876543210");
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("beneficiaryAccountName", "{{holderName}}");
        // {{beneficiaryAccountNo}}, BUKAN {{accountNo}}: request service 15 tak pernah
        // mengirim field bernama accountNo, jadi template lama selalu merender string
        // kosong untuk field yang WAJIB di ASPI.
        body.put("beneficiaryAccountNo", "{{beneficiaryAccountNo}}");
        body.put("beneficiaryAccountStatus", "Rekening aktif");
        body.put("beneficiaryAccountType", "S");
        body.put("currency", "{{currency}}");

        return new ResponseSpec(200, RC_SUCCESS, "Successful", body);
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}