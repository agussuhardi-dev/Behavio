package id.behavio.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.core.domain.TransactionStatus;
import id.behavio.core.rule.Action;
import id.behavio.core.rule.CompareOp;
import id.behavio.core.rule.Condition;
import id.behavio.core.rule.FaultSpec;
import id.behavio.core.rule.Operand;
import id.behavio.core.rule.Outcome;
import id.behavio.core.rule.ResponseSpec;
import id.behavio.core.rule.Rule;
import id.behavio.core.rule.Scenario;
import id.behavio.core.rule.WebhookSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Konversi dua arah JSON ↔ {@link Scenario}. Memungkinkan definisi scenario (kondisi
 * request + response + fault/webhook) di-edit dari dashboard (design.md §8). Format
 * cermin AST core agar round-trip presisi.
 */
public final class ScenarioCodec {

    private final ObjectMapper mapper = new ObjectMapper();

    // ---------------- JSON → Scenario ----------------

    public Scenario parse(String name, String json) {
        try {
            JsonNode root = mapper.readTree(json);
            List<Rule> rules = new ArrayList<>();
            JsonNode rulesNode = root.get("rules");
            if (rulesNode != null && rulesNode.isArray()) {
                for (JsonNode r : rulesNode) {
                    rules.add(new Rule(
                            text(r, "name", ""),
                            parseCondition(r.get("when")),
                            parseOutcome(r.get("then"))));
                }
            }
            Outcome fallback = parseOutcome(root.get("fallback"));
            return new Scenario(name, rules, fallback);
        } catch (Exception e) {
            throw new IllegalArgumentException("Definisi scenario JSON tidak valid: " + e.getMessage(), e);
        }
    }

    private Condition parseCondition(JsonNode n) {
        if (n == null || n.isNull()) return new Condition.Always();
        String kind = text(n, "kind", "always");
        return switch (kind) {
            case "compare" -> new Condition.Compare(
                    parseOperand(n.get("left")),
                    CompareOp.valueOf(text(n, "op", "EQ")),
                    parseOperand(n.get("right")));
            case "and" -> new Condition.And(parseConditions(n.get("children")));
            case "or" -> new Condition.Or(parseConditions(n.get("children")));
            default -> new Condition.Always();
        };
    }

    private List<Condition> parseConditions(JsonNode arr) {
        List<Condition> out = new ArrayList<>();
        if (arr != null && arr.isArray()) {
            for (JsonNode c : arr) out.add(parseCondition(c));
        }
        return out;
    }

    private Operand parseOperand(JsonNode n) {
        String kind = text(n, "kind", "field");
        return switch (kind) {
            case "accountBalance" -> new Operand.AccountBalance(text(n, "field", ""));
            case "num" -> new Operand.Num(new BigDecimal(text(n, "value", "0")));
            case "str" -> new Operand.Str(text(n, "value", ""));
            default -> new Operand.Field(text(n, "path", ""));
        };
    }

    private Outcome parseOutcome(JsonNode n) {
        if (n == null || n.isNull()) {
            return Outcome.of(new ResponseSpec(200, "2001800", "Successful", Map.of()));
        }
        List<Action> actions = new ArrayList<>();
        JsonNode actionsNode = n.get("actions");
        if (actionsNode != null && actionsNode.isArray()) {
            for (JsonNode a : actionsNode) actions.add(parseAction(a));
        }
        ResponseSpec response = parseResponse(n.get("response"));
        FaultSpec fault = parseFault(n.get("fault"));
        WebhookSpec webhook = parseWebhook(n.get("webhook"));
        return new Outcome(actions, response, fault, webhook);
    }

    private Action parseAction(JsonNode n) {
        String kind = text(n, "kind", "");
        return switch (kind) {
            case "credit" -> new Action.Credit(text(n, "accountNoField", ""), text(n, "amountField", ""));
            case "createTransaction" -> new Action.CreateTransaction(
                    TransactionStatus.valueOf(text(n, "status", "SUCCESS")));
            default -> new Action.Debit(text(n, "accountNoField", ""), text(n, "amountField", ""));
        };
    }

    @SuppressWarnings("unchecked")
    private ResponseSpec parseResponse(JsonNode n) {
        if (n == null || n.isNull()) {
            return new ResponseSpec(200, "2001800", "Successful", Map.of());
        }
        Map<String, Object> body = Map.of();
        JsonNode bodyNode = n.get("body");
        if (bodyNode != null && !bodyNode.isNull()) {
            body = mapper.convertValue(bodyNode, Map.class);
        }
        return new ResponseSpec(
                n.path("httpStatus").asInt(200),
                text(n, "responseCode", "2001800"),
                text(n, "responseMessage", ""),
                body);
    }

    private FaultSpec parseFault(JsonNode n) {
        if (n == null || n.isNull()) return null;
        FaultSpec.Point point = FaultSpec.Point.valueOf(text(n, "point", "AFTER_ACTIONS"));
        return new FaultSpec(point,
                n.path("delayMillis").asLong(0),
                n.path("drop").asBoolean(false),
                n.path("corrupt").asBoolean(false));
    }

    @SuppressWarnings("unchecked")
    private WebhookSpec parseWebhook(JsonNode n) {
        if (n == null || n.isNull()) return null;
        Map<String, Object> body = Map.of();
        JsonNode bodyNode = n.get("bodyTemplate");
        if (bodyNode != null && !bodyNode.isNull()) {
            body = mapper.convertValue(bodyNode, Map.class);
        }
        return new WebhookSpec(text(n, "urlHeader", "X-CALLBACK-URL"),
                n.path("delayMillis").asLong(0), body);
    }

    // ---------------- Scenario → JSON ----------------

    public String serialize(Scenario scenario) {
        try {
            ObjectNode root = mapper.createObjectNode();
            ArrayNode rules = root.putArray("rules");
            for (Rule r : scenario.rules()) {
                ObjectNode rn = rules.addObject();
                rn.put("name", r.name());
                rn.set("when", condition(r.when()));
                rn.set("then", outcome(r.then()));
            }
            root.set("fallback", outcome(scenario.fallback()));
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception e) {
            throw new IllegalStateException("Gagal serialisasi scenario", e);
        }
    }

    private JsonNode condition(Condition c) {
        ObjectNode n = mapper.createObjectNode();
        switch (c) {
            case Condition.Compare cmp -> {
                n.put("kind", "compare");
                n.set("left", operand(cmp.left()));
                n.put("op", cmp.op().name());
                n.set("right", operand(cmp.right()));
            }
            case Condition.And and -> {
                n.put("kind", "and");
                ArrayNode ch = n.putArray("children");
                and.children().forEach(x -> ch.add(condition(x)));
            }
            case Condition.Or or -> {
                n.put("kind", "or");
                ArrayNode ch = n.putArray("children");
                or.children().forEach(x -> ch.add(condition(x)));
            }
            case Condition.Always ignored -> n.put("kind", "always");
        }
        return n;
    }

    private JsonNode operand(Operand o) {
        ObjectNode n = mapper.createObjectNode();
        switch (o) {
            case Operand.Field f -> { n.put("kind", "field"); n.put("path", f.path()); }
            case Operand.AccountBalance a -> { n.put("kind", "accountBalance"); n.put("field", a.accountNoField()); }
            case Operand.Num num -> { n.put("kind", "num"); n.put("value", num.value().toPlainString()); }
            case Operand.Str s -> { n.put("kind", "str"); n.put("value", s.value()); }
        }
        return n;
    }

    private JsonNode outcome(Outcome o) {
        ObjectNode n = mapper.createObjectNode();
        ArrayNode actions = n.putArray("actions");
        for (Action a : o.actions()) actions.add(action(a));
        n.set("response", response(o.response()));
        if (o.fault() != null) {
            ObjectNode f = mapper.createObjectNode();
            f.put("point", o.fault().point().name());
            f.put("delayMillis", o.fault().delayMillis());
            f.put("drop", o.fault().drop());
            f.put("corrupt", o.fault().corrupt());
            n.set("fault", f);
        }
        if (o.webhook() != null) {
            ObjectNode w = mapper.createObjectNode();
            w.put("urlHeader", o.webhook().urlHeader());
            w.put("delayMillis", o.webhook().delayMillis());
            w.set("bodyTemplate", mapper.valueToTree(o.webhook().bodyTemplate()));
            n.set("webhook", w);
        }
        return n;
    }

    private JsonNode action(Action a) {
        ObjectNode n = mapper.createObjectNode();
        switch (a) {
            case Action.Debit d -> { n.put("kind", "debit"); n.put("accountNoField", d.accountNoField()); n.put("amountField", d.amountField()); }
            case Action.Credit c -> { n.put("kind", "credit"); n.put("accountNoField", c.accountNoField()); n.put("amountField", c.amountField()); }
            case Action.CreateTransaction ct -> { n.put("kind", "createTransaction"); n.put("status", ct.status().name()); }
        }
        return n;
    }

    private JsonNode response(ResponseSpec r) {
        ObjectNode n = mapper.createObjectNode();
        n.put("httpStatus", r.httpStatus());
        n.put("responseCode", r.responseCode());
        n.put("responseMessage", r.responseMessage());
        n.set("body", mapper.valueToTree(r.bodyTemplate()));
        return n;
    }

    private static String text(JsonNode n, String field, String def) {
        if (n == null) return def;
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? def : v.asText();
    }
}
