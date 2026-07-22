package id.behavio.iso.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.iso.codec.IsoCodecException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Baca/tulis definisi scenario sebagai JSON — bentuk yang dilihat & disunting user lewat
 * "Edit Response".
 *
 * <pre>{@code
 * { "name": "Saldo Tidak Cukup",
 *   "response": { "39": "51", "54": "1002360C000000000000" },
 *   "replace": false,
 *   "fault": { "delayMillis": 0, "noResponse": false, "drop": false, "corrupt": false } }
 * }</pre>
 */
public final class IsoScenarioCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private IsoScenarioCodec() {}

    public static String write(IsoScenario s) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", s.name());
        ObjectNode resp = root.putObject("response");
        s.responseOverrides().forEach((de, v) -> resp.put(String.valueOf(de), v));
        root.put("replace", s.replace());
        ObjectNode f = root.putObject("fault");
        f.put("delayMillis", s.fault().delayMillis());
        f.put("noResponse", s.fault().noResponse());
        f.put("drop", s.fault().drop());
        f.put("corrupt", s.fault().corrupt());
        return root.toString();
    }

    public static IsoScenario read(String json) {
        if (json == null || json.isBlank()) {
            throw new IsoCodecException("Definisi scenario kosong");
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IsoCodecException("Definisi scenario bukan JSON valid: " + e.getMessage(), e);
        }

        Map<Integer, String> overrides = new LinkedHashMap<>();
        JsonNode resp = n.get("response");
        if (resp != null && resp.isObject()) {
            resp.properties().forEach(e -> {
                int de;
                try {
                    de = Integer.parseInt(e.getKey().trim());
                } catch (NumberFormatException ex) {
                    throw new IsoCodecException("Kunci 'response' harus nomor DE, dapat: '"
                            + e.getKey() + "'");
                }
                if (de < 2 || de > 128) {
                    throw new IsoCodecException("DE di luar 2..128 pada 'response': " + de);
                }
                overrides.put(de, e.getValue().asText());
            });
        }

        JsonNode f = n.get("fault");
        IsoFault fault = (f == null || !f.isObject()) ? IsoFault.none() : new IsoFault(
                f.path("delayMillis").asLong(0),
                f.path("noResponse").asBoolean(false),
                f.path("drop").asBoolean(false),
                f.path("corrupt").asBoolean(false));

        String name = n.path("name").asText("Normal");
        return new IsoScenario(name, overrides, n.path("replace").asBoolean(false), fault);
    }
}
