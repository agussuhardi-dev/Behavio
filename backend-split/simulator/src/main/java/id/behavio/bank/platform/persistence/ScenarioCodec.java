package id.behavio.bank.platform.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.bank.platform.core.product.ActionCodec;
import id.behavio.bank.platform.core.rule.Action;
import id.behavio.bank.platform.core.rule.CompareOp;
import id.behavio.bank.platform.core.rule.Condition;
import id.behavio.bank.platform.core.rule.FaultSpec;
import id.behavio.bank.platform.core.rule.Operand;
import id.behavio.bank.platform.core.rule.Outcome;
import id.behavio.bank.platform.core.rule.ResponseSpec;
import id.behavio.bank.platform.core.rule.Rule;
import id.behavio.bank.platform.core.rule.Scenario;
import id.behavio.bank.platform.core.rule.WebhookSpec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Konversi dua arah JSON ↔ {@link Scenario}. Memungkinkan definisi scenario (kondisi
 * request + response + fault/webhook) di-edit dari dashboard (design.md §8). Format
 * cermin AST core agar round-trip presisi.
 *
 * Produk-agnostik: aksi (debit/credit/…) diserahkan ke {@link ActionCodec} milik produk,
 * sehingga codec ini tak perlu mengenal tipe aksi bank maupun QRIS.
 */
public final class ScenarioCodec {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ActionCodec actions;

    public ScenarioCodec(ActionCodec actions) {
        this.actions = actions == null ? ActionCodec.NONE : actions;
    }

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
        List<Action> parsed = new ArrayList<>();
        JsonNode actionsNode = n.get("actions");
        if (actionsNode != null && actionsNode.isArray()) {
            for (JsonNode a : actionsNode) parsed.add(parseAction(a));
        }
        ResponseSpec response = parseResponse(n.get("response"));
        FaultSpec fault = parseFault(n.get("fault"));
        WebhookSpec webhook = parseWebhook(n.get("webhook"));
        return new Outcome(parsed, response, fault, webhook);
    }

    /**
     * Sebelumnya kind tak dikenal diam-diam jatuh ke {@code Debit} — salah ketik di editor
     * dashboard bisa berujung memotong saldo. Kini ditolak eksplisit.
     */
    private Action parseAction(JsonNode n) {
        String kind = text(n, "kind", "");
        Map<String, String> attrs = new LinkedHashMap<>();
        n.fieldNames().forEachRemaining(field -> {
            if (!"kind".equals(field)) attrs.put(field, text(n, field, ""));
        });
        return actions.parse(kind, attrs).orElseThrow(() ->
                new IllegalArgumentException("Aksi '" + kind + "' tidak dikenal untuk produk ini"));
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

    /**
     * Definisi lama memakai {@code urlHeader} (era X-CALLBACK-URL, dibuang di §9.1).
     * Definisi itu masih ada di DB user, jadi field-nya diabaikan dan event jatuh ke
     * {@code ALL} — registrasi umum partner. Konfigurasi tersimpan tak boleh pecah hanya
     * karena mesinnya berubah; kalau pecah, user kehilangan editan yang mereka tulis
     * sendiri dan tak punya cara memulihkannya.
     */
    @SuppressWarnings("unchecked")
    private WebhookSpec parseWebhook(JsonNode n) {
        if (n == null || n.isNull()) return null;
        Map<String, Object> body = Map.of();
        JsonNode bodyNode = n.get("bodyTemplate");
        if (bodyNode != null && !bodyNode.isNull()) {
            body = mapper.convertValue(bodyNode, Map.class);
        }
        return new WebhookSpec(text(n, "event", WebhookSpec.EVENT_ALL),
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
            w.put("event", o.webhook().event());
            w.put("delayMillis", o.webhook().delayMillis());
            w.set("bodyTemplate", mapper.valueToTree(o.webhook().bodyTemplate()));
            n.set("webhook", w);
        }
        return n;
    }

    private JsonNode action(Action a) {
        ActionCodec.Encoded e = actions.encode(a).orElseThrow(() ->
                new IllegalStateException("Aksi " + a.getClass().getName() + " bukan milik produk ini"));
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", e.kind());
        e.attributes().forEach(n::put);
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
