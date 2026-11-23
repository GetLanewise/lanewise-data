import java.io.IOException;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Haalt alle vaste flitsers in Nederland uit OpenStreetMap (via Overpass) en schrijft ze
 * in het eigen formaat van de Flitsapp naar een JSON-bestand.
 *
 * Draaien (Java 17+, geen extra libraries nodig):
 *   java tools/FetchCameras.java osm-nl.json
 *
 * Veiligheid: het bestaande bestand wordt NIET overschreven als
 *  - alle Overpass-servers falen,
 *  - er minder dan MIN_CAMERAS flitsers binnenkomen,
 *  - het aantal meer dan MAX_DROP lager is dan in het vorige bestand,
 *  - of meer dan MAX_INVALID van de flitsers de datacontrole niet doorstaat (buiten NL, dubbel,
 *    onzinnige limiet). Losse fouten worden verbeterd of weggelaten en gemeld in het verslag.
 * Zo krijgen gebruikers nooit een lege of halve database door een storing.
 *
 * Testen zonder internet: geef een opgeslagen Overpass-CSV mee als tweede argument.
 *   java tools/FetchCameras.java uit.json voorbeeld.csv
 */
public class FetchCameras {

    static final List<String> SERVERS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    );

    /** Tab-gescheiden uitvoer: geen JSON-library nodig. */
    static final String QUERY = String.join("\n",
            "[out:csv(::id,::lat,::lon,::timestamp,maxspeed,direction;true;\"\\t\")][timeout:120];",
            "area[\"ISO3166-1\"=\"NL\"][\"admin_level\"=\"2\"]->.nl;",
            "node[\"highway\"=\"speed_camera\"](area.nl);",
            "out meta;");

    static final int MIN_CAMERAS = 300;
    static final double MAX_DROP = 0.30;
    static final int ATTEMPTS_PER_SERVER = 2;

    record Camera(long id, double lat, double lon, Integer maxSpeed, Double direction, Long updatedAt) {}

    static final java.util.HashMap<Long, Enforcement.Info> ENFORCEMENT = new java.util.HashMap<>();
    /** Flitsers waarvan de limiet uit de handhavingsrelatie komt (niet van de flitser zelf). */
    static final java.util.HashSet<Long> REL_SPEED = new java.util.HashSet<>();

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Gebruik: java FetchCameras.java <uitvoer.json> [invoer.csv]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        String csv = args.length >= 2 ? Files.readString(Path.of(args[1])) : download();

        List<Camera> cameras = parse(csv);
        // Handhavingsrelaties: roodlicht herkennen, limiet van de relatie overnemen, opgeheven apparaten weg.
        // Mislukt het ophalen, dan gaat alles door zoals voorheen (alles "SPEED").
        ENFORCEMENT.putAll(args.length >= 4 ? Enforcement.parse(Files.readString(Path.of(args[3]))) : Enforcement.fetch(args.length >= 2));
        int abandoned = 0, relSpeed = 0, red = 0;
        List<Camera> kept = new ArrayList<>();
        for (Camera c : cameras) {
            Enforcement.Info info = ENFORCEMENT.get(c.id());
            if (info != null && info.onlyAbandoned()) { abandoned++; continue; }
            if (info != null && c.maxSpeed() == null && info.maxSpeed() != null) {
                c = new Camera(c.id(), c.lat(), c.lon(), info.maxSpeed(), c.direction(), c.updatedAt());
                REL_SPEED.add(c.id());
                relSpeed++;
            }
            if (info != null && !Enforcement.type(info).equals("SPEED")) red++;
            kept.add(c);
        }
        cameras = kept;
        System.out.println("Handhavingsrelaties: " + ENFORCEMENT.size() + " apparaten, " + abandoned + " opgeheven weggelaten, "
                + relSpeed + " limieten uit de relatie, " + red + " roodlichtcamera's");
        // Ontbrekende maximumsnelheid aanvullen met die van de weg waar de flitser op staat.
        // Mislukt dat, dan gaan de flitsers gewoon zonder aanvulling door.
        Map<Long, Integer> roadSpeeds = args.length >= 3
                ? RoadSpeeds.estimate(cameras, Files.readString(Path.of(args[2])))
                : RoadSpeeds.fetchAndEstimate(cameras, args.length >= 2);
        System.out.println("Ontvangen: " + cameras.size() + " flitsers");
        roadSpeeds = new java.util.HashMap<>(roadSpeeds);
        List<String> report = new ArrayList<>();
        cameras = check(cameras, roadSpeeds, report);
        writeSummary(report);

        if (cameras.size() < MIN_CAMERAS) {
            fail("Te weinig flitsers (" + cameras.size() + " < " + MIN_CAMERAS + "), oud bestand blijft staan");
        }
        int previous = previousCount(out);
        if (previous > 0 && cameras.size() < previous * (1 - MAX_DROP)) {
            fail("Aantal daalde van " + previous + " naar " + cameras.size() + " (meer dan "
                    + Math.round(MAX_DROP * 100) + "%), oud bestand blijft staan");
        }

        cameras.sort((a, b) -> Long.compare(a.id(), b.id()));
        String camerasJson = camerasJson(cameras, roadSpeeds);
        if (Files.exists(out) && Files.readString(out).contains(camerasJson)) {
            System.out.println("Geen wijzigingen, bestand blijft gelijk");
            return;
        }
        String json = "{\n"
                + "\"format\":1,\n"
                + "\"source\":\"osm\",\n"
                + "\"attribution\":\"© OpenStreetMap-bijdragers\",\n"
                + "\"license\":\"ODbL-1.0\",\n"
                + "\"generatedAt\":" + System.currentTimeMillis() + ",\n"
                + "\"count\":" + cameras.size() + ",\n"
                + "\"cameras\":" + camerasJson + "\n"
                + "}\n";
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Geschreven: " + out + " (" + cameras.size() + " flitsers, was " + previous + ")");
    }

    static String download() throws InterruptedException {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        String body = "data=" + URLEncoder.encode(QUERY, StandardCharsets.UTF_8);
        List<String> errors = new ArrayList<>();
        for (String server : SERVERS) {
            for (int attempt = 1; attempt <= ATTEMPTS_PER_SERVER; attempt++) {
                try {
                    HttpRequest req = HttpRequest.newBuilder(URI.create(server))
                            .timeout(Duration.ofSeconds(180))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .header("User-Agent", "flitsapp-data/1 (dagelijkse update)")
                            .POST(HttpRequest.BodyPublishers.ofString(body))
                            .build();
                    HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (res.statusCode() == 200 && res.body().startsWith("@id")) {
                        System.out.println("Opgehaald van " + server);
                        return res.body();
                    }
                    errors.add(server + " poging " + attempt + ": HTTP " + res.statusCode());
                } catch (IOException e) {
                    errors.add(server + " poging " + attempt + ": " + e.getMessage());
                }
                Thread.sleep(attempt * 20_000L);
            }
        }
        fail("Alle Overpass-servers faalden:\n  " + String.join("\n  ", errors));
        return null; // onbereikbaar
    }

    static List<Camera> parse(String csv) {
        List<Camera> result = new ArrayList<>();
        String[] lines = csv.split("\r?\n");
        for (int i = 1; i < lines.length; i++) { // regel 0 = kopregel
            if (lines[i].isBlank()) continue;
            String[] f = lines[i].split("\t", -1);
            if (f.length < 3) continue;
            try {
                result.add(new Camera(
                        Long.parseLong(f[0].trim()),
                        Double.parseDouble(f[1].trim()),
                        Double.parseDouble(f[2].trim()),
                        f.length > 4 ? parseMaxSpeed(f[4]) : null,
                        f.length > 5 ? parseDirection(f[5]) : null,
                        f.length > 3 ? parseTime(f[3]) : null));
            } catch (NumberFormatException e) {
                System.err.println("Regel overgeslagen: " + lines[i]);
            }
        }
        return result;
    }

    static final Pattern SPEED = Pattern.compile("^\\s*(\\d{2,3})");

    static Integer parseMaxSpeed(String raw) {
        Matcher m = SPEED.matcher(raw);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    static final Map<String, Double> CARDINALS = Map.of(
            "N", 0.0, "NE", 45.0, "E", 90.0, "SE", 135.0,
            "S", 180.0, "SW", 225.0, "W", 270.0, "NW", 315.0);

    static Double parseDirection(String raw) {
        String s = raw.trim();
        if (s.isEmpty()) return null;
        try {
            double d = Double.parseDouble(s);
            return ((d % 360) + 360) % 360;
        } catch (NumberFormatException e) {
            return CARDINALS.get(s.toUpperCase(Locale.ROOT));
        }
    }

    static Long parseTime(String raw) {
        try {
            return raw.isBlank() ? null : Instant.parse(raw.trim()).toEpochMilli();
        } catch (Exception e) {
            return null;
        }
    }

    /** Eén flitser per regel: zo zijn de dagelijkse wijzigingen in git goed leesbaar. */
    static String camerasJson(List<Camera> cameras) { return camerasJson(cameras, Map.of()); }

    static String camerasJson(List<Camera> cameras, Map<Long, Integer> roadSpeeds) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < cameras.size(); i++) {
            Camera c = cameras.get(i);
            sb.append("{\"id\":\"osm:node/").append(c.id()).append('"')
              .append(",\"lat\":").append(c.lat())
              .append(",\"lon\":").append(c.lon())
              .append(",\"type\":\"").append(ENFORCEMENT.containsKey(c.id()) ? Enforcement.type(ENFORCEMENT.get(c.id())) : "SPEED").append('"');
            if (c.maxSpeed() != null) sb.append(",\"maxSpeed\":").append(c.maxSpeed()).append(REL_SPEED.contains(c.id()) ? ",\"maxSpeedSrc\":\"rel\"" : "");
            else if (roadSpeeds.containsKey(c.id())) sb.append(",\"maxSpeed\":").append(roadSpeeds.get(c.id())).append(",\"maxSpeedSrc\":\"road\"");
            if (c.direction() != null) sb.append(",\"direction\":").append(c.direction());
            RoadSpeeds.Axis ax = RoadSpeeds.AXES.get(c.id());
            if (ax != null) {
                sb.append(String.format(Locale.ROOT, ",\"roadAxis\":%.1f", ax.axis()));
                if (ax.travel() != null) sb.append(String.format(Locale.ROOT, ",\"travel\":%.1f", ax.travel()));
            }
            if (c.updatedAt() != null) sb.append(",\"updatedAt\":").append(c.updatedAt());
            sb.append('}').append(i < cameras.size() - 1 ? ",\n" : "\n");
        }
        return sb.append(']').toString();
    }

    static int previousCount(Path out) {
        try {
            if (!Files.exists(out)) return 0;
            Matcher m = Pattern.compile("\"count\":(\\d+)").matcher(Files.readString(out));
            return m.find() ? Integer.parseInt(m.group(1)) : 0;
        } catch (IOException e) {
            return 0;
        }
    }


    // ---------------- Datacontrole ----------------

    /** Ruim om Nederland heen (incl. Waddeneilanden, Zuid-Limburg en Zeeuws-Vlaanderen). */
    static final double LAT_MIN = 50.70, LAT_MAX = 53.60, LON_MIN = 3.30, LON_MAX = 7.25;
    /** Gaat er meer dan dit deel mis, dan is de bron waarschijnlijk stuk: niets publiceren. */
    static final double MAX_INVALID = 0.05;
    /** Twee flitsers op (vrijwel) exact dezelfde plek met dezelfde (of geen) richting = dubbel ingevoerd.
     *  Bewust klein: paren op 2-3 m zijn vaak echte losse palen (per rijstrook of richting); die
     *  laten we staan, de app voegt ze bij het waarschuwen toch samen. */
    static final double DUPLICATE_M = 0.5;

    /** Bestaande Nederlandse limieten (km/u). Alles daarbuiten is een invoerfout in OSM. */
    static boolean validSpeed(Integer kmh) {
        return kmh != null && kmh >= 15 && kmh <= 130 && kmh % 5 == 0;
    }

    /**
     * Controleert de flitsers vóór publicatie. Verbetert wat te verbeteren is (onzinnige limiet weg,
     * dubbele flitser weg), verwijdert wat niet klopt (buiten NL) en stopt als te veel niet klopt.
     */
    static List<Camera> check(List<Camera> in, Map<Long, Integer> roadSpeeds, List<String> report) {
        List<Camera> out = new ArrayList<>();
        int outside = 0, badSpeed = 0, badRoad = 0;
        for (Camera c : in) {
            if (!(c.lat() >= LAT_MIN && c.lat() <= LAT_MAX && c.lon() >= LON_MIN && c.lon() <= LON_MAX)) {
                outside++;
                report.add("buiten NL weggelaten: node/" + c.id());
                continue;
            }
            Camera fixed = c;
            if (c.maxSpeed() != null && !validSpeed(c.maxSpeed())) {
                badSpeed++;
                report.add("onzinnige maxspeed " + c.maxSpeed() + " weggelaten: node/" + c.id());
                fixed = new Camera(c.id(), c.lat(), c.lon(), null, c.direction(), c.updatedAt());
            }
            out.add(fixed);
        }
        for (var it = roadSpeeds.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            if (!validSpeed(e.getValue())) { badRoad++; it.remove(); }
        }
        // Dubbel ingevoerd: zelfde plek en zelfde richting. Twee richtingen op één paal blijven allebei.
        out.sort((a, b) -> Long.compare(a.id(), b.id()));
        List<Camera> unique = new ArrayList<>();
        int duplicates = 0;
        for (Camera c : out) {
            boolean dup = false;
            for (Camera u : unique) {
                if (distanceM(c.lat(), c.lon(), u.lat(), u.lon()) < DUPLICATE_M && sameDirection(c.direction(), u.direction())) {
                    dup = true;
                    report.add("dubbel weggelaten: node/" + c.id() + " (zelfde als node/" + u.id() + ")");
                    break;
                }
            }
            if (dup) duplicates++; else unique.add(c);
        }
        int invalid = outside + duplicates;
        report.add(0, String.format(Locale.ROOT,
                "Controle flitsers: %d binnen, %d buiten NL, %d dubbel, %d onzinnige limiet, %d onzinnige weg-limiet -> %d over",
                in.size(), outside, duplicates, badSpeed, badRoad, unique.size()));
        if (in.size() > 0 && (invalid + badSpeed) > in.size() * MAX_INVALID) {
            writeSummary(report);
            fail("Meer dan " + Math.round(MAX_INVALID * 100) + "% van de flitsers klopt niet, bron lijkt stuk. Oud bestand blijft staan.");
        }
        return unique;
    }

    static boolean sameDirection(Double a, Double b) {
        if (a == null || b == null) return a == null && b == null;
        double d = Math.abs(a - b) % 360;
        return Math.min(d, 360 - d) < 30;
    }

    static double distanceM(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1), dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 6_371_000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    /** Verslag naar de console en, op GitHub, naar de samenvatting van de run. */
    static void writeSummary(List<String> report) {
        report.stream().limit(40).forEach(System.out::println);
        if (report.size() > 40) System.out.println("... en nog " + (report.size() - 40));
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary == null) return;
        try {
            StringBuilder sb = new StringBuilder("### Flitsers\n\n");
            report.stream().limit(40).forEach(l -> sb.append("- ").append(l).append('\n'));
            Files.writeString(Path.of(summary), sb.toString(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) { }
    }

    static void fail(String message) {
        System.err.println("FOUT: " + message);
        System.exit(1);
    }

    /**
     * Schat de maximumsnelheid van flitsers zonder maxspeed-label: de limiet van de weg waar de
     * flitser op staat (OSM). Liggen er binnen een paar meter meerdere wegen met verschillende
     * limiet (kruising, afrit), dan de LAAGSTE. In de uitvoer gemarkeerd met "maxSpeedSrc":"road".
     */
    /**
     * Handhavingsrelaties (type=enforcement) uit OSM: welke flitser is een roodlichtcamera, welke limiet
     * geldt volgens de relatie, en welke apparaten horen bij een opgeheven controle (enforcement=no).
     */
    static class Enforcement {
        static final String QUERY = String.join("\n",
                "[out:xml][timeout:120];",
                "area[\"ISO3166-1\"=\"NL\"][\"admin_level\"=\"2\"]->.nl;",
                "rel[\"type\"=\"enforcement\"](area.nl);",
                "out body;");

        /** Per apparaat (node-id): soorten handhaving, laagste limiet uit de relaties, en of alles opgeheven is. */
        record Info(java.util.Set<String> kinds, Integer maxSpeed, boolean onlyAbandoned) {}

        static Map<Long, Info> fetch(boolean offline) {
            if (offline) return Map.of();
            try {
                String xml = RoadSpeeds.post(QUERY);
                if (xml == null) { System.err.println("Handhavingsrelaties ophalen mislukt: geen roodlicht-herkenning."); return Map.of(); }
                return parse(xml);
            } catch (Exception e) {
                System.err.println("Handhavingsrelaties verwerken mislukt (" + e.getMessage() + ")");
                return Map.of();
            }
        }

        static Map<Long, Info> parse(String xml) throws Exception {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            java.util.HashMap<Long, java.util.Set<String>> kinds = new java.util.HashMap<>();
            java.util.HashMap<Long, Integer> speeds = new java.util.HashMap<>();
            java.util.HashMap<Long, Boolean> active = new java.util.HashMap<>();
            org.w3c.dom.NodeList rl = doc.getElementsByTagName("relation");
            for (int i = 0; i < rl.getLength(); i++) {
                org.w3c.dom.Element r = (org.w3c.dom.Element) rl.item(i);
                String enforcement = "", maxspeed = null;
                boolean abandoned = false;
                org.w3c.dom.NodeList tl = r.getElementsByTagName("tag");
                for (int j = 0; j < tl.getLength(); j++) {
                    org.w3c.dom.Element t = (org.w3c.dom.Element) tl.item(j);
                    switch (t.getAttribute("k")) {
                        case "enforcement" -> enforcement = t.getAttribute("v");
                        case "maxspeed" -> maxspeed = t.getAttribute("v");
                        case "abandoned", "disused" -> abandoned = !t.getAttribute("v").equals("no");
                        default -> { }
                    }
                }
                boolean dead = abandoned || enforcement.equals("no");
                java.util.Set<String> ks = new java.util.TreeSet<>();
                for (String k : enforcement.split(";")) if (!k.isBlank()) ks.add(k.trim());
                Integer speed = maxspeed == null ? null : RoadSpeeds.parseRoadSpeed(maxspeed);
                org.w3c.dom.NodeList ml = r.getElementsByTagName("member");
                for (int j = 0; j < ml.getLength(); j++) {
                    org.w3c.dom.Element m = (org.w3c.dom.Element) ml.item(j);
                    if (!m.getAttribute("type").equals("node") || !m.getAttribute("role").equals("device")) continue;
                    long id = Long.parseLong(m.getAttribute("ref"));
                    active.merge(id, !dead, Boolean::logicalOr);
                    if (dead) continue;
                    kinds.computeIfAbsent(id, x -> new java.util.TreeSet<>()).addAll(ks);
                    if (speed != null && ks.contains("maxspeed")) speeds.merge(id, speed, Math::min);
                }
            }
            java.util.HashMap<Long, Info> out = new java.util.HashMap<>();
            for (var e : active.entrySet()) {
                out.put(e.getKey(), new Info(kinds.getOrDefault(e.getKey(), java.util.Set.of()), speeds.get(e.getKey()), !e.getValue()));
            }
            return out;
        }

        /** Soort camera volgens de relaties: roodlicht, snelheid, of beide. */
        static String type(Info i) {
            boolean red = i.kinds().contains("traffic_signals") || i.kinds().contains("red_light_camera");
            boolean speed = i.kinds().contains("maxspeed") || i.kinds().contains("average_speed");
            return red && speed ? "SPEED_AND_RED_LIGHT" : red ? "RED_LIGHT" : "SPEED";
        }
    }

    static class RoadSpeeds {
        static final double MAX_DIST_M = 20.0;
        static final double TIE_M = 8.0;

        static final String QUERY = String.join("\n",
                "[out:xml][timeout:180];",
                "area[\"ISO3166-1\"=\"NL\"][\"admin_level\"=\"2\"]->.nl;",
                "node[\"highway\"=\"speed_camera\"](area.nl)->.c;",
                "way(around.c:25)[\"highway\"~\"^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$\"];",
                "out geom;");

        /** speed = null als de weg geen maxspeed heeft; oneway: 1 = in tekenrichting, -1 = tegen, 0 = beide. */
        record Way(Integer speed, int oneway, boolean tunnel, double[] lats, double[] lons) {}

        /** Per flitser: as van de weg (0-180) en, bij eenrichtingsweg, de rijrichting. Gevuld door estimate(). */
        record Axis(double axis, Double travel) {}
        static final java.util.HashMap<Long, Axis> AXES = new java.util.HashMap<>();
        /** Wegen binnen deze extra afstand met een andere as = kruising/afrit: geen as opgeven. */
        static final double AXIS_TIE_M = 6.0;

        static Map<Long, Integer> fetchAndEstimate(List<Camera> cameras, boolean offline) {
            if (offline) return Map.of();
            try {
                String xml = post(QUERY);
                if (xml == null) {
                    System.err.println("Wegen ophalen mislukt: maximumsnelheden niet aangevuld.");
                    return Map.of();
                }
                return estimate(cameras, xml);
            } catch (Exception e) {
                System.err.println("Wegen verwerken mislukt (" + e.getMessage() + "): maximumsnelheden niet aangevuld.");
                return Map.of();
            }
        }

        static String post(String query) throws InterruptedException {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
            String body = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);
            for (String server : SERVERS) {
                for (int attempt = 1; attempt <= ATTEMPTS_PER_SERVER; attempt++) {
                    try {
                        HttpRequest req = HttpRequest.newBuilder(URI.create(server))
                                .timeout(Duration.ofSeconds(200))
                                .header("Content-Type", "application/x-www-form-urlencoded")
                                .header("User-Agent", "flitsapp-data/1 (dagelijkse update)")
                                .POST(HttpRequest.BodyPublishers.ofString(body))
                                .build();
                        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                        // Een "remark" betekent een fout of time-out op de server: dan niet gebruiken.
                        if (res.statusCode() == 200 && res.body().contains("<osm") && !res.body().contains("<remark")) return res.body();
                        System.err.println("Wegen: " + server + " poging " + attempt + ": HTTP " + res.statusCode());
                    } catch (IOException e) {
                        System.err.println("Wegen: " + server + " poging " + attempt + ": " + e.getMessage());
                    }
                    Thread.sleep(attempt * 15_000L);
                }
            }
            return null;
        }

        static Map<Long, Integer> estimate(List<Camera> cameras, String xml) throws Exception {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            org.w3c.dom.Document doc = f.newDocumentBuilder()
                    .parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            List<Way> ways = new ArrayList<>();
            org.w3c.dom.NodeList wl = doc.getElementsByTagName("way");
            for (int i = 0; i < wl.getLength(); i++) {
                org.w3c.dom.Element w = (org.w3c.dom.Element) wl.item(i);
                Integer speed = null;
                String oneway = null, highway = "", junction = "";
                boolean tunnel = false;
                org.w3c.dom.NodeList tags = w.getElementsByTagName("tag");
                for (int j = 0; j < tags.getLength(); j++) {
                    org.w3c.dom.Element t = (org.w3c.dom.Element) tags.item(j);
                    String k = t.getAttribute("k"), v = t.getAttribute("v");
                    switch (k) {
                        case "maxspeed" -> speed = parseRoadSpeed(v);
                        case "oneway" -> oneway = v;
                        case "highway" -> highway = v;
                        case "junction" -> junction = v;
                        case "tunnel" -> tunnel = !v.equals("no");
                        default -> { }
                    }
                }
                // Snelweg en rotonde zijn in OSM impliciet eenrichtingsverkeer.
                int dir = "yes".equals(oneway) || "true".equals(oneway) || "1".equals(oneway) ? 1
                        : "-1".equals(oneway) ? -1
                        : oneway == null && (highway.equals("motorway") || junction.equals("roundabout")) ? 1 : 0;
                org.w3c.dom.NodeList nds = w.getElementsByTagName("nd");
                if (nds.getLength() < 2) continue;
                double[] la = new double[nds.getLength()], lo = new double[nds.getLength()];
                for (int j = 0; j < nds.getLength(); j++) {
                    org.w3c.dom.Element n = (org.w3c.dom.Element) nds.item(j);
                    la[j] = Double.parseDouble(n.getAttribute("lat"));
                    lo[j] = Double.parseDouble(n.getAttribute("lon"));
                }
                ways.add(new Way(speed, dir, tunnel, la, lo));
            }
            java.util.HashMap<Long, Integer> result = new java.util.HashMap<>();
            int ambiguous = 0, agree = 0, disagree = 0, higher = 0;
            int axisOk = 0, axisAmbiguous = 0, dirSame = 0, dirOpposite = 0, dirOther = 0;
            AXES.clear();
            for (Camera c : cameras) {
                double[] dist = new double[ways.size()];
                double bestAny = Double.MAX_VALUE;
                int nearest = -1;
                for (int i = 0; i < ways.size(); i++) {
                    dist[i] = distanceToWay(c.lat(), c.lon(), ways.get(i));
                    if (dist[i] < bestAny) { bestAny = dist[i]; nearest = i; }
                }
                // --- as van de weg (voor "rijd ik over de weg van deze flitser?") ---
                if (nearest >= 0 && bestAny <= MAX_DIST_M) {
                    double ax = axisAt(c.lat(), c.lon(), ways.get(nearest));
                    boolean clash = false;
                    for (int i = 0; i < ways.size(); i++) {
                        if (i != nearest && dist[i] <= bestAny + AXIS_TIE_M && axisDiff(ax, axisAt(c.lat(), c.lon(), ways.get(i))) > 25) clash = true;
                    }
                    if (clash) axisAmbiguous++;
                    else {
                        axisOk++;
                        Way w = ways.get(nearest);
                        Double travel = null;
                        if (w.oneway() != 0) {
                            double b = bearingAt(c.lat(), c.lon(), w);
                            travel = w.oneway() == 1 ? b : (b + 180) % 360;
                            // Analyse: wat betekent de richting-tag van een flitser in OSM?
                            if (c.direction() != null) {
                                double d = Math.abs(c.direction() - travel) % 360;
                                d = Math.min(d, 360 - d);
                                if (d <= 45) dirSame++; else if (d >= 135) dirOpposite++; else dirOther++;
                            }
                        }
                        AXES.put(c.id(), new Axis(ax, travel));
                    }
                }
                // --- maximumsnelheid (alleen wegen met maxspeed) ---
                double best = Double.MAX_VALUE;
                for (int i = 0; i < ways.size(); i++) if (ways.get(i).speed() != null) best = Math.min(best, dist[i]);
                if (best > MAX_DIST_M) continue;
                int min = Integer.MAX_VALUE, max = 0;
                for (int i = 0; i < ways.size(); i++) {
                    if (ways.get(i).speed() != null && dist[i] <= best + TIE_M) {
                        min = Math.min(min, ways.get(i).speed());
                        max = Math.max(max, ways.get(i).speed());
                    }
                }
                if (min != max) ambiguous++;
                // Controle: bij flitsers MET label vergelijken we de schatting met het echte label.
                if (c.maxSpeed() != null) { if (min == c.maxSpeed()) agree++; else { disagree++; if (min > c.maxSpeed()) higher++; } }
                else result.put(c.id(), min);
            }
            long missing = cameras.stream().filter(c -> c.maxSpeed() == null).count();
            System.out.println("Controle op flitsers met label: schatting klopt bij " + agree + ", wijkt af bij " + disagree
                    + " (waarvan " + higher + " keer te hoog)");
            System.out.println("Maximumsnelheid aangevuld uit wegen: " + result.size() + " van " + missing
                    + " zonder label (" + ambiguous + " keer twijfel tussen wegen: laagste genomen)");
            System.out.println("As van de weg bekend: " + axisOk + " flitsers (" + axisAmbiguous + " op kruising/afrit: geen as)");
            System.out.println("Richting-tag vs rijrichting op eenrichtingswegen: gelijk " + dirSame
                    + ", tegengesteld " + dirOpposite + ", anders " + dirOther);
            return result;
        }

        /** Richting (0-360) van het wegdeel dat het dichtst bij het punt ligt, in tekenrichting van de weg. */
        static double bearingAt(double lat, double lon, Way w) {
            double kx = Math.toRadians(1) * 6_371_000 * Math.cos(Math.toRadians(lat));
            double ky = Math.toRadians(1) * 6_371_000;
            double best = Double.MAX_VALUE, bearing = 0;
            for (int i = 0; i < w.lats().length - 1; i++) {
                double ax = (w.lons()[i] - lon) * kx, ay = (w.lats()[i] - lat) * ky;
                double bx = (w.lons()[i + 1] - lon) * kx, by = (w.lats()[i + 1] - lat) * ky;
                double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                if (len2 == 0) continue;
                double t = Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                double cx = ax + t * dx, cy = ay + t * dy, d = Math.sqrt(cx * cx + cy * cy);
                if (d < best) { best = d; bearing = (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360; }
            }
            return bearing;
        }

        /** As (0-180): richting zonder voor/achter. */
        static double axisAt(double lat, double lon, Way w) { return bearingAt(lat, lon, w) % 180; }

        static double axisDiff(double a, double b) {
            double d = Math.abs(a - b) % 180;
            return Math.min(d, 180 - d);
        }

        /** Getal ("80") of een NL-zone ("NL:urban" = 50). Onbekend of geen limiet = null. */
        static Integer parseRoadSpeed(String raw) {
            String v = raw.trim();
            switch (v) {
                case "NL:urban": return 50;
                case "NL:rural": return 80;
                case "NL:trunk": return 100;
                case "NL:motorway": return 130;
                case "NL:zone30": return 30;
                case "NL:zone60": return 60;
                default: break;
            }
            Matcher m = SPEED.matcher(v);
            return m.find() ? Integer.valueOf(m.group(1)) : null;
        }

        /** Kortste afstand (m) van een punt tot een weg (vlakke benadering rond het punt). */
        static double distanceToWay(double lat, double lon, Way w) {
            double kx = Math.toRadians(1) * 6_371_000 * Math.cos(Math.toRadians(lat));
            double ky = Math.toRadians(1) * 6_371_000;
            double best = Double.MAX_VALUE;
            for (int i = 0; i < w.lats().length - 1; i++) {
                double ax = (w.lons()[i] - lon) * kx, ay = (w.lats()[i] - lat) * ky;
                double bx = (w.lons()[i + 1] - lon) * kx, by = (w.lats()[i + 1] - lat) * ky;
                double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                double t = len2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2));
                double cx = ax + t * dx, cy = ay + t * dy;
                best = Math.min(best, Math.sqrt(cx * cx + cy * cy));
            }
            return best;
        }
    }
}
