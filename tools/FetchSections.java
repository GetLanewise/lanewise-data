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
 * minder dan MIN_SECTIONS trajecten binnenkomen, als het aantal meer dan MAX_DROP daalt, of als
 * meer dan MAX_INVALID van de trajecten de datacontrole niet doorstaat.
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
            "out body qt;"); // body: ook de tags van de wegdelen (tunnel)

    static final int MIN_SECTIONS = 40;
    static final double MAX_DROP = 0.30;
    static final int ATTEMPTS_PER_SERVER = 2;

    record LatLon(double lat, double lon) {}

    record Section(long id, String name, String ref, int maxSpeed, String conditional, Integer lengthM,
                   List<LatLon> from, List<LatLon> to, List<Long> devices, List<List<LatLon>> lines,
                   Long updatedAt, List<Integer> tunnels) {}

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
        List<String> report = new ArrayList<>();
        sections = check(sections, report);
        writeSummary(report);
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
        java.util.Set<Long> tunnelWays = new java.util.HashSet<>();
        NodeList wl = doc.getElementsByTagName("way");
        for (int i = 0; i < wl.getLength(); i++) {
            Element w = (Element) wl.item(i);
            List<Long> refs = new ArrayList<>();
            NodeList nds = w.getElementsByTagName("nd");
            for (int j = 0; j < nds.getLength(); j++) refs.add(Long.parseLong(((Element) nds.item(j)).getAttribute("ref")));
            ways.put(Long.parseLong(w.getAttribute("id")), refs);
            NodeList wt = w.getElementsByTagName("tag");
            for (int j = 0; j < wt.getLength(); j++) {
                Element t = (Element) wt.item(j);
                if (t.getAttribute("k").equals("tunnel") && !t.getAttribute("v").equals("no")) tunnelWays.add(Long.parseLong(w.getAttribute("id")));
            }
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
            List<Integer> tunnels = new ArrayList<>();
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
                    if (line.size() >= 2) {
                        if (tunnelWays.contains(ref)) tunnels.add(lines.size());
                        lines.add(line);
                    }
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
                    parseTime(r.getAttribute("timestamp")), tunnels));
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
            sb.append("]");
            if (!s.tunnels().isEmpty()) {
                sb.append(",\"tunnels\":[");
                for (int j = 0; j < s.tunnels().size(); j++) { if (j > 0) sb.append(','); sb.append(s.tunnels().get(j)); }
                sb.append(']');
            }
            sb.append("}").append(i < sections.size() - 1 ? ",\n" : "\n");
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

    // ---------------- Datacontrole ----------------

    static final double LAT_MIN = 50.70, LAT_MAX = 53.60, LON_MIN = 3.30, LON_MAX = 7.25;
    static final double MIN_LENGTH_M = 300, MAX_LENGTH_M = 60_000;
    /** Gaat er meer dan dit deel mis, dan is de bron waarschijnlijk stuk: niets publiceren. */
    static final double MAX_INVALID = 0.10;

    /**
     * Controleert de trajecten vóór publicatie. Een traject dat niet klopt (onzinnige limiet, geen
     * wegdelen, onmogelijke lengte, buiten NL) wordt weggelaten en gemeld. Een opgegeven lengte die
     * flink afwijkt van de getekende weg wordt alleen gemeld (de app vergelijkt zelf al met GPS).
     */
    static List<Section> check(List<Section> in, List<String> report) {
        List<Section> out = new ArrayList<>();
        int removed = 0;
        for (Section s : in) {
            String why = null;
            double drawn = 0;
            for (List<LatLon> line : s.lines())
                for (int i = 1; i < line.size(); i++) drawn += distanceM(line.get(i - 1), line.get(i));
            if (s.maxSpeed() < 15 || s.maxSpeed() > 130 || s.maxSpeed() % 5 != 0) why = "onzinnige limiet " + s.maxSpeed();
            else if (s.lines().isEmpty()) why = "geen wegdelen";
            else if (drawn < MIN_LENGTH_M || drawn > MAX_LENGTH_M) why = "onmogelijke lengte " + Math.round(drawn) + " m";
            else {
                for (LatLon p : concat(s.from(), s.to()))
                    if (!(p.lat() >= LAT_MIN && p.lat() <= LAT_MAX && p.lon() >= LON_MIN && p.lon() <= LON_MAX)) why = "buiten NL";
            }
            if (why != null) {
                removed++;
                report.add("weggelaten: relation/" + s.id() + " " + s.name() + " (" + why + ")");
                continue;
            }
            if (s.lengthM() != null && Math.abs(s.lengthM() - drawn) > 0.2 * drawn) {
                report.add(String.format(Locale.ROOT, "let op: relation/%d %s, opgegeven lengte %d m maar getekend %d m",
                        s.id(), s.name(), s.lengthM(), Math.round(drawn)));
            }
            out.add(s);
        }
        report.add(0, "Controle trajecten: " + in.size() + " binnen, " + removed + " weggelaten -> " + out.size() + " over");
        if (in.size() > 0 && removed > in.size() * MAX_INVALID) {
            writeSummary(report);
            fail("Meer dan " + Math.round(MAX_INVALID * 100) + "% van de trajecten klopt niet, bron lijkt stuk. Bestaand bestand blijft staan.");
        }
        return out;
    }

    static List<LatLon> concat(List<LatLon> a, List<LatLon> b) {
        List<LatLon> r = new ArrayList<>(a);
        r.addAll(b);
        return r;
    }

    static double distanceM(LatLon p, LatLon q) {
        double dLat = Math.toRadians(q.lat() - p.lat()), dLon = Math.toRadians(q.lon() - p.lon());
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(p.lat())) * Math.cos(Math.toRadians(q.lat())) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Verslag naar de console en, op GitHub, naar de samenvatting van de run. */
    static void writeSummary(List<String> report) {
        report.stream().limit(40).forEach(System.out::println);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary == null) return;
        try {
            StringBuilder sb = new StringBuilder("### Trajectcontroles\n\n");
            report.stream().limit(40).forEach(l -> sb.append("- ").append(l).append('\n'));
            Files.writeString(Path.of(summary), sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) { }
    }

    static void fail(String message) {
        System.err.println(message);
        System.exit(1);
    }
}
