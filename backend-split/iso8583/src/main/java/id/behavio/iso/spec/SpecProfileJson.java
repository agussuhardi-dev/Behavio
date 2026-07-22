package id.behavio.iso.spec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import id.behavio.iso.codec.Encoding;
import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.FieldType;
import id.behavio.iso.codec.IsoCodecException;

import java.util.ArrayList;
import java.util.List;

/**
 * Baca/tulis profil spec dalam JSON — format alternatif selain packager XML, untuk
 * disunting tangan atau dibuat dari nol ({@code docs/iso8583-plan.md} §2).
 *
 * <p>Juga bentuk penyimpanan di kolom {@code jsonb}, sehingga profil hasil unggahan XML
 * pun disimpan sebagai JSON — satu bentuk kanonik, apa pun asal berkasnya.
 */
public final class SpecProfileJson {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SpecProfileJson() {}

    public static SpecProfile read(String json) {
        if (json == null || json.isBlank()) {
            throw new IsoCodecException("Profil JSON kosong");
        }
        JsonNode n;
        try {
            n = MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IsoCodecException("JSON profil tidak valid: " + e.getMessage(), e);
        }
        if (!n.isObject()) {
            throw new IsoCodecException("Profil JSON harus berupa objek");
        }

        List<FieldSpec> fields = new ArrayList<>();
        JsonNode fs = n.get("fields");
        if (fs != null && fs.isArray()) {
            for (JsonNode f : fs) {
                fields.add(readField(f));
            }
        }

        List<OperationRoute> ops = new ArrayList<>();
        JsonNode os = n.get("operations");
        if (os != null && os.isArray()) {
            for (JsonNode o : os) {
                ops.add(new OperationRoute(text(o, "name"), text(o, "mti"),
                        text(o, "processingCode")));
            }
        }

        return new SpecProfile(text(n, "name"), text(n, "version"), text(n, "extends"),
                readTransport(n.get("transport")), fields, ops);
    }

    public static String write(SpecProfile p) {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("name", p.name());
        root.put("version", p.version());
        if (p.parent() != null) {
            root.put("extends", p.parent());
        }
        if (p.transport() != null) {
            ObjectNode t = root.putObject("transport");
            t.put("lengthPrefixBytes", p.transport().lengthPrefixBytes());
            t.put("lengthPrefixEncoding", p.transport().lengthPrefixEncoding().name());
            t.put("charset", p.transport().charset().name());
            t.put("bitmap", p.transport().bitmap().name());
        }
        ArrayNode fs = root.putArray("fields");
        for (FieldSpec f : p.fields()) {
            ObjectNode o = fs.addObject();
            o.put("de", f.de());
            o.put("name", f.name());
            o.put("type", f.type().code());
            o.put("encoding", f.encoding().name());
            o.put("length", f.length());
            o.put("lengthPrefix", f.lengthPrefix());
        }
        ArrayNode os = root.putArray("operations");
        for (OperationRoute r : p.operations()) {
            ObjectNode o = os.addObject();
            o.put("name", r.name());
            o.put("mti", r.mti());
            if (r.processingCode() != null) {
                o.put("processingCode", r.processingCode());
            }
        }
        return root.toString();
    }

    private static FieldSpec readField(JsonNode f) {
        if (!f.hasNonNull("de")) {
            throw new IsoCodecException("fields[]: 'de' wajib diisi");
        }
        int de = f.get("de").asInt();
        String name = text(f, "name");
        if (name == null || name.isBlank()) {
            name = "DE" + de;
        }
        return new FieldSpec(de, name,
                fieldType(text(f, "type"), de),
                encoding(text(f, "encoding")),
                f.hasNonNull("length") ? f.get("length").asInt() : 0,
                f.hasNonNull("lengthPrefix") ? f.get("lengthPrefix").asInt() : 0);
    }

    private static FieldType fieldType(String code, int de) {
        if (code == null || code.isBlank()) {
            return FieldType.ANS;
        }
        for (FieldType t : FieldType.values()) {
            if (t.code().equalsIgnoreCase(code.trim())) {
                return t;
            }
        }
        throw new IsoCodecException("DE " + de + ": tipe '" + code
                + "' tidak dikenal (n/a/an/ans/b/z)");
    }

    private static Encoding encoding(String v) {
        if (v == null || v.isBlank()) {
            return Encoding.ASCII;
        }
        try {
            return Encoding.valueOf(v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IsoCodecException("encoding '" + v + "' tidak dikenal "
                    + "(ASCII/BCD/BINARY/EBCDIC)");
        }
    }

    private static TransportSpec readTransport(JsonNode t) {
        if (t == null || t.isNull()) {
            return null;   // warisi induk / default — lihat SpecProfileResolver
        }
        TransportSpec d = TransportSpec.defaults();
        return new TransportSpec(
                t.hasNonNull("lengthPrefixBytes") ? t.get("lengthPrefixBytes").asInt() : d.lengthPrefixBytes(),
                enumOf(TransportSpec.LengthPrefixEncoding.class, text(t, "lengthPrefixEncoding"), d.lengthPrefixEncoding()),
                enumOf(TransportSpec.CharsetKind.class, text(t, "charset"), d.charset()),
                enumOf(TransportSpec.BitmapEncoding.class, text(t, "bitmap"), d.bitmap()));
    }

    private static <E extends Enum<E>> E enumOf(Class<E> type, String v, E fallback) {
        if (v == null || v.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, v.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IsoCodecException("transport: nilai '" + v + "' tidak dikenal untuk "
                    + type.getSimpleName() + " — pilihan: "
                    + java.util.Arrays.toString(type.getEnumConstants()));
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
