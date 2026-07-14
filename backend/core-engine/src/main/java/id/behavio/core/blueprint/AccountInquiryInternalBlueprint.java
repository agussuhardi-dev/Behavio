package id.behavio.core.blueprint;

import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Scenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Blueprint SNAP BI untuk endpoint Internal Account Inquiry (service 15).
 * Satu scenario "Normal" — response mengikuti spec ASPI.
 */
public final class AccountInquiryInternalBlueprint {

    public static final String METHOD = "POST";
    public static final String PATH = "/v1.0/account-inquiry-internal";

    private AccountInquiryInternalBlueprint() {}

    public static Scenario normal() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("responseCode", "{{responseCode}}");
        body.put("responseMessage", "{{responseMessage}}");
        body.put("referenceNo", "{{referenceNo}}");
        body.put("partnerReferenceNo", "{{partnerReferenceNo}}");
        body.put("beneficiaryAccountName", "{{holderName}}");
        body.put("beneficiaryAccountNo", "{{accountNo}}");
        body.put("beneficiaryAccountStatus", "Rekening aktif");
        body.put("beneficiaryAccountType", "S");
        body.put("currency", "{{currency}}");

        return new Scenario("Normal", Collections.emptyList(),
                Outcome.of(List.of(), new ResponseSpec(200, "2001500", "Successful", body)));
    }
}
