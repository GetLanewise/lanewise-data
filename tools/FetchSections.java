import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Haalt alle trajectcontroles in Nederland uit OpenStreetMap (relaties met
 * enforcement=average_speed) en schrijft ze in het eigen formaat van de Flitsapp.
 *
 * Draaien (Java 17+, geen extra libraries nodig):
 *   java tools/FetchSections.java osm-nl-trajecten.json
 *
 * Per traject: begin- en eindpunten (from/to), limiet (+ eventuele tijdsafhankelijke limiet),
 * lengte, de wegdelen ertussen (lines) en de camera-punten (devices). De app gebruikt de
 * devices om die punten niet óók als losse flitser te melden.
 *
 * Veiligheid: het bestaande bestand wordt NIET overschreven als alle servers falen, als er
 * minder dan MIN_SECTIONS trajecten binnenkomen, of als het aantal meer dan MAX_DROP daalt.
 *
 * Testen zonder internet: geef een opgeslagen Overpass-XML mee als tweede argument.
 */
public class FetchSections {

    static final List<String> SERVERS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    );

    static final String QUERY = String.join("\n",
            "[out:xml][timeout:120];",
            "area[\"ISO3166-1\"=\"NL\"][\"admin_level\"=\"2\"]->.nl;",
            "rel[\"enforcement\"=\"average_speed\"](area.nl);",
            "out meta;",
            ">;",
            "out skel qt;");

    static final int MIN_SECTIONS = 40;
    static final double MAX_DROP = 0.30;
    static final int ATTEMPTS_PER_SERVER = 2;

    record LatLon(double lat, double lon) {}

    record Section(long id, String name, String ref, int maxSpeed, String conditional, Integer lengthM,
                   List<LatLon> from, List<LatLon> to, List<Long> devices, List<List<LatLon>> lines,
                   Long updatedAt) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Gebruik: java FetchSections.java <uitvoer.json> [invoer.xml]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        String xml = args.length > 1 ? Files.readString(Path.of(args[1])) : download();
        if (xml == null) fail("Alle Overpass-servers faalden. Bestaand bestand blijft staan.");

        List<Section> sections = parse(xml);
        System.out.println("Trajecten ontvangen: " + sections.size());
        if (sections.size() < MIN_SECTIONS) {
            fail("Te weinig trajecten (" + sections.size() + " < " + MIN_SECTIONS + "). Bestaand bestand blijft staan.");
        }
        int previous = previousCount(out);
        if (previous > 0 && sections.size() < previous * (1 - MAX_DROP)) {
            fail("Aantal daalt te sterk (" + previous + " -> " + sections.size() + "). Bestaand bestand blijft staan.");
        }

        String body = sectionsJson(sections);
        if (Files.exists(out)) {
            String old = Files.readString(out);
            int i = old.indexOf("\"sections\":");
            if (i >= 0 && old.substring(i).equals(body)) {
                System.out.println("Geen wijzigingen.");
                return;
            }
        }
        String json = "{\n"
                + "\"format\":1,\n"
                + "\"source\":\"osm\",\n"
                + "\"attribution\":\"© OpenStreetMap-bijdragers\",\n"
                + "\"license\":\"ODbL-1.0\",\n"
                + "\"generatedAt\":" + System.currentTimeMillis() + ",\n"
                + "\"count\":" + sections.size() + ",\n"
                + body;
        Path tmp = Path.of(out + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Geschreven: " + out + " (" + sections.size() + " trajecten)");
    }

    static String download() throws InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        String body = "data=" + URLEncoder.encode(QUERY, StandardCharsets.UTF_8);
        for (String server : SERVERS) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_SERVER; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(server))
                            .timeout(Duration.ofSeconds(180))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("User-Agent", "Flitsapp-data/1.0 (github.com/flitsapp-nl/flitsapp-data)")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                    if (resp.statusCode() == 200 && resp.body().contains("<osm")) return resp.body();
                    System.err.println(server + " poging " + attempt + ": HTTP " + resp.statusCode());
                } catch (Exception e) {
                    System.err.println(server + " poging " + attempt + ": " + e.getMessage());
                }
                Thread.sleep(10_000L * attempt);
            }
        }
        return null;
    }

    static List<Section> parse(String xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        Document doc = f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        Map<Long, LatLon> nodes = new HashMap<>();
        NodeList nl = doc.getElementsByTagName("node");
        for (int i = 0; i < nl.getLength(); i++) {
            Element n = (Element) nl.item(i);
            nodes.put(Long.parseLong(n.getAttribute("id")),
                    new LatLon(Double.parseDouble(n.getAttribute("lat")), Double.parseDouble(n.getAttribute("lon"))));
        }
        Map<Long, List<Long>> ways = new HashMap<>();
        NodeList wl = doc.getElementsByTagName("way");
        for (int i = 0; i < wl.getLength(); i++) {
            Element w = (Element) wl.item(i);
            List<Long> refs = new ArrayList<>();
            NodeList nds = w.getElementsByTagName("nd");
            for (int j = 0; j < nds.getLength(); j++) refs.add(Long.parseLong(((Element) nds.item(j)).getAttribute("ref")));
            ways.put(Long.parseLong(w.getAttribute("id")), refs);
        }

        List<Section> result = new ArrayList<>();
        NodeList rl = doc.getElementsByTagName("relation");
        for (int i = 0; i < rl.getLength(); i++) {
            Element r = (Element) rl.item(i);
            Map<String, String> tags = new LinkedHashMap<>();
            NodeList tl = r.getElementsByTagName("tag");
            for (int j = 0; j < tl.getLength(); j++) {
                Element t = (Element) tl.item(j);
                tags.put(t.getAttribute("k"), t.getAttribute("v"));
            }
            Integer max = parseSpeed(tags.get("maxspeed"));
            if (max == null) {
                System.err.println("Overgeslagen (geen maxspeed): relation " + r.getAttribute("id"));
                continue;
            }
            List<LatLon> from = new ArrayList<>(), to = new ArrayList<>();
            List<Long> devices = new ArrayList<>();
            List<List<LatLon>> lines = new ArrayList<>();
            NodeList ml = r.getElementsByTagName("member");
            for (int j = 0; j < ml.getLength(); j++) {
                Element m = (Element) ml.item(j);
                long ref = Long.parseLong(m.getAttribute("ref"));
                String type = m.getAttribute("type"), role = m.getAttribute("role");
                if (type.equals("node")) {
                    LatLon p = nodes.get(ref);
                    switch (role) {
                        case "from" -> { if (p != null) from.add(p); }
                        case "to" -> { if (p != null) to.add(p); }
                        case "device" -> devices.add(ref);
                        default -> { }
                    }
                } else if (type.equals("way") && role.equals("section")) {
                    List<LatLon> line = new ArrayList<>();
                    for (long nd : ways.getOrDefault(ref, List.of())) {
                        LatLon p = nodes.get(nd);
                        if (p != null) line.add(p);
                    }
                    if (line.size() >= 2) lines.add(line);
                }
            }
            if (from.isEmpty() || to.isEmpty()) {
                System.err.println("Overgeslagen (geen begin of eind): relation " + r.getAttribute("id"));
                continue;
            }
            result.add(new Section(
                    Long.parseLong(r.getAttribute("id")),
                    tags.getOrDefault("name", ""),
                    tags.getOrDefault("ref", ""),
                    max,
                    tags.get("maxspeed:conditional"),
                    parseLength(tags.get("length")),
                    from, to, devices, lines,
                    parseTime(r.getAttribute("timestamp"))));
        }
        result.sort((a, b) -> Long.compare(a.id(), b.id()));
        return result;
    }

    static final Pattern SPEED = Pattern.compile("^\\s*(\\d{2,3})");

    static Integer parseSpeed(String raw) {
        if (raw == null) return null;
        Matcher m = SPEED.matcher(raw);
        return m.find() ? Integer.parseInt(m.group(1)) : null;
    }

    static final Pattern LENGTH = Pattern.compile("^\\s*([0-9]+(?:[.,][0-9]+)?)\\s*(km|m)?\\s*$");

    /** "3275", "3275 m" of "3.2 km" → meters. Anders null (de app rekent dan zelf). */
    static Integer parseLength(String raw) {
        if (raw == null) return null;
        Matcher m = LENGTH.matcher(raw);
        if (!m.find()) return null;
        double v = Double.parseDouble(m.group(1).replace(',', '.'));
        if ("km".equals(m.group(2))) v *= 1000;
        return (int) Math.round(v);
    }

    static Long parseTime(String raw) {
        try {
            return raw == null || raw.isEmpty() ? null : Instant.parse(raw).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    static String pt(LatLon p) {
        return String.format(Locale.ROOT, "[%.6f,%.6f]", p.lat(), p.lon());
    }

    static String pts(List<LatLon> list) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(pt(list.get(i)));
        }
        return sb.append(']').toString();
    }

    static String str(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                default -> { if (c < 0x20) sb.append(String.format("\\u%04x", (int) c)); else sb.append(c); }
            }
        }
        return sb.append('"').toString();
    }

    /** Eén traject per regel: kleine, leesbare diffs in git. */
    static String sectionsJson(List<Section> sections) {
        StringBuilder sb = new StringBuilder("\"sections\":[\n");
        for (int i = 0; i < sections.size(); i++) {
            Section s = sections.get(i);
            sb.append("{\"id\":\"osm:relation/").append(s.id()).append('"');
            sb.append(",\"name\":").append(str(s.name()));
            sb.append(",\"ref\":").append(str(s.ref()));
            sb.append(",\"maxSpeed\":").append(s.maxSpeed());
            if (s.conditional() != null) sb.append(",\"maxSpeedConditional\":").append(str(s.conditional()));
            if (s.lengthM() != null) sb.append(",\"lengthM\":").append(s.lengthM());
            if (s.updatedAt() != null) sb.append(",\"updatedAt\":").append(s.updatedAt());
            sb.append(",\"from\":").append(pts(s.from()));
            sb.append(",\"to\":").append(pts(s.to()));
            sb.append(",\"devices\":[");
            for (int j = 0; j < s.devices().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append("\"osm:node/").append(s.devices().get(j)).append('"');
            }
            sb.append("],\"lines\":[");
            for (int j = 0; j < s.lines().size(); j++) {
                if (j > 0) sb.append(',');
                sb.append(pts(s.lines().get(j)));
            }
            sb.append("]}").append(i < sections.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("]\n}\n").toString();
    }

    static int previousCount(Path out) {
        try {
            String old = Files.readString(out);
            Matcher m = Pattern.compile("\"count\":(\\d+)").matcher(old);
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
