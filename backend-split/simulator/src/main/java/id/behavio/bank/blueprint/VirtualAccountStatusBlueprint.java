package id.behavio.bank.blueprint;

import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Scenario;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint untuk endpoint Virtual Account — Inquiry Status.
 * Tiga scenario: Normal, Bank Down (503), Timeout (delay 5 detik).
 */
public final class VirtualAccountStatusBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/transfer-va/status";
    public static final String RC_SUCCESS = "2002600";
    public static final String RC_SERVICE_DOWN = "5030000";

    private VirtualAccountStatusBlueprint() {}

    /** Contoh request untuk export OpenAPI (design.md §15.5, Lampiran A2.1 service 26). */
    public static Map<String, Object> requestExample() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("partnerServiceId", "  088899");
        body.put("customerNo", "12345678901234567890");
        body.put("virtualAccountNo", "  08889912345678901234567890");
        body.put("inquiryRequestId", "2026071500000000000006");
        body.put("additionalInfo", Map.of());
        return body;
    }

    public static Scenario normal() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partnerServiceId", "{{partnerServiceId}}");
        data.put("customerNo", "{{customerNo}}");
        data.put("virtualAccountNo", "{{virtualAccountNo}}");
        data.put("virtualAccountName", "{{virtualAccountName}}");
        data.put("virtualAccountEmail", "{{virtualAccountEmail}}");
        data.put("virtualAccountPhone", "{{virtualAccountPhone}}");
        // ASPI service 26 menandai inquiryRequestId Mandatory ("From Inquiry Request").
        data.put("inquiryRequestId", "{{inquiryRequestId}}");
        data.put("totalAmount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        data.put("virtualAccountTrxType", "{{virtualAccountTrxType}}");
        data.put("expiredDate", "{{expiredDate}}");
        data.put("virtualAccountStatus", "{{vaStatus}}");
        data.put("trxId", "{{trxId}}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("virtualAccountData", data);
        return new Scenario("Normal", List.of(),
                Outcome.of(new ResponseSpec(200, RC_SUCCESS, "Successful", body)));
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
                Outcome.withFault(List.of(),
                        new ResponseSpec(200, RC_SUCCESS, "Successful", normalBody()),
                        FaultSpec.delayAfter(delayMillis)));
    }

    private static Map<String, Object> normalBody() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("partnerServiceId", "{{partnerServiceId}}");
        data.put("customerNo", "{{customerNo}}");
        data.put("virtualAccountNo", "{{virtualAccountNo}}");
        data.put("virtualAccountName", "{{virtualAccountName}}");
        data.put("virtualAccountEmail", "{{virtualAccountEmail}}");
        data.put("virtualAccountPhone", "{{virtualAccountPhone}}");
        // ASPI service 26 menandai inquiryRequestId Mandatory ("From Inquiry Request").
        data.put("inquiryRequestId", "{{inquiryRequestId}}");
        data.put("totalAmount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        data.put("virtualAccountTrxType", "{{virtualAccountTrxType}}");
        data.put("expiredDate", "{{expiredDate}}");
        data.put("virtualAccountStatus", "{{vaStatus}}");
        data.put("trxId", "{{trxId}}");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("virtualAccountData", data);
        return body;
    }

    private static ResponseSpec errorResponse(int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        return new ResponseSpec(status, code, message, body);
    }
}