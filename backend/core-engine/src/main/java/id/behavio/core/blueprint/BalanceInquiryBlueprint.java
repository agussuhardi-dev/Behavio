package id.behavio.core.blueprint;

import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk endpoint Balance Inquiry (service 11).
 * Satu scenario "Normal" — response mengikuti spec ASPI.
 */
public final class BalanceInquiryBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/balance-inquiry";

    private BalanceInquiryBlueprint() {}

    public static Scenario normal() {
        Map<String, Object> accountInfo = new LinkedHashMap<>();
        accountInfo.put("balanceType", "Cash");
        accountInfo.put("amount", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        accountInfo.put("floatAmount", Map.of("value", "0", "currency", "{{currency}}"));
        accountInfo.put("holdAmount", Map.of("value", "0", "currency", "{{currency}}"));
        accountInfo.put("availableBalance", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        accountInfo.put("ledgerBalance", Map.of("value", "{{amountValue}}", "currency", "{{currency}}"));
        accountInfo.put("currentMultilateralLimit", Map.of("value", "0", "currency", "{{currency}}"));
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

        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2001100", "Successful", body)));
    }
}
