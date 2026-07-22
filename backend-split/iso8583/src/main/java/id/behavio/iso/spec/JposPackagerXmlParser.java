package id.behavio.iso.spec;

import id.behavio.iso.codec.FieldSpec;
import id.behavio.iso.codec.IsoCodecException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser berkas <b>jPOS packager XML</b> — format yang biasanya diserahkan bank.
 *
 * <pre>{@code
 * <isopackager>
 *   <isofield id="2" length="19" name="PAN" class="org.jpos.iso.IFA_LLNUM"/>
 * </isopackager>
 * }</pre>
 *
 * <p>Hanya <b>format</b>-nya yang didukung; XML diparse sendiri sehingga tak ada
 * dependensi jPOS (AGPL v3 terhindar) — lihat {@link PackagerClassMap}.
 */
public final class JposPackagerXmlParser {

    private JposPackagerXmlParser() {}

    /**
     * @return daftar {@link FieldSpec}, terurut menurut nomor DE.
     * @throws IsoCodecException bila XML tak valid, ada DE ganda, atau memakai kelas
     *                           packager yang belum didukung.
     */
    public static List<FieldSpec> parse(String xml) {
        if (xml == null || xml.isBlank()) {
            throw new IsoCodecException("Berkas packager XML kosong");
        }
        Document doc = parseSecurely(xml);

        NodeList nodes = doc.getElementsByTagName("isofield");
        if (nodes.getLength() == 0) {
            throw new IsoCodecException(
                    "Tak ada elemen <isofield> — apakah ini benar berkas jPOS packager?");
        }

        Map<Integer, FieldSpec> byDe = new LinkedHashMap<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element el = (Element) nodes.item(i);
            String idAttr = el.getAttribute("id");
            String className = el.getAttribute("class");

            int de = parseDe(idAttr, i);
            // DE0 = MTI dan DE1 = bitmap: keduanya ditangani codec, bukan field biasa.
            if (de == 0 || de == 1 || PackagerClassMap.isBitmap(className)) {
                continue;
            }
            if (byDe.containsKey(de)) {
                throw new IsoCodecException("DE " + de + " didefinisikan lebih dari sekali");
            }

            int length = parseLength(el.getAttribute("length"), de);
            String name = el.getAttribute("name");
            if (name == null || name.isBlank()) {
                name = "DE" + de;
            }
            PackagerClassMap.Semantics s = PackagerClassMap.require(className);
            byDe.put(de, new FieldSpec(de, name.trim(), s.type(), s.encoding(),
                    length, s.lengthPrefix()));
        }

        if (byDe.isEmpty()) {
            throw new IsoCodecException(
                    "Tak ada data element terpakai — hanya MTI/bitmap yang ditemukan");
        }
        List<FieldSpec> out = new ArrayList<>(byDe.values());
        out.sort((a, b) -> Integer.compare(a.de(), b.de()));
        return out;
    }

    private static int parseDe(String idAttr, int index) {
        try {
            return Integer.parseInt(idAttr.trim());
        } catch (RuntimeException e) {
            throw new IsoCodecException(
                    "Atribut id tidak valid pada <isofield> ke-" + (index + 1) + ": '" + idAttr + "'");
        }
    }

    private static int parseLength(String lengthAttr, int de) {
        try {
            int len = Integer.parseInt(lengthAttr.trim());
            if (len <= 0) {
                throw new IsoCodecException("DE " + de + ": length harus > 0, dapat " + len);
            }
            return len;
        } catch (NumberFormatException e) {
            throw new IsoCodecException("DE " + de + ": atribut length tidak valid: '" + lengthAttr + "'");
        }
    }

    /**
     * Parser XML dengan DTD/entitas eksternal DIMATIKAN. Berkas packager datang dari
     * unggahan pengguna, dan berkas jPOS lazim memuat {@code <!DOCTYPE … SYSTEM
     * "genericpackager.dtd">} — tanpa penguncian ini, unggahan bisa dipakai membaca
     * berkas server (XXE).
     */
    private static Document parseSecurely(String xml) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            f.setFeature("http://xml.org/sax/features/external-general-entities", false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            b.setEntityResolver((publicId, systemId) ->
                    new org.xml.sax.InputSource(new java.io.StringReader("")));
            return b.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        } catch (IsoCodecException e) {
            throw e;
        } catch (Exception e) {
            throw new IsoCodecException("Gagal membaca packager XML: " + e.getMessage(), e);
        }
    }
}
