import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Bouwt het wegenbestand voor de Flitsapp: alle wegen in Nederland (OpenStreetMap) met hun
 * maximumsnelheid, als compact binair bestand in vakjes, zodat de app lokaal kan zien op welke weg
 * je rijdt en welke limiet daar geldt. Draaien (Java 17+, geen extra libraries):
 *
 *   java tools/FetchRoads.java osm-nl-wegen.bin.gz
 *
 * Haalt NL op in blokken (Overpass), vereenvoudigt de lijnen (Douglas-Peucker, 3 m), knipt ze per
 * vakje van 0,02° x 0,03° (±2 km) en schrijft gzip-binair (formaat "FRD1", zie [write]).
 * Veiligheid: minder dan MIN_WAYS wegen of een blok dat blijft mislukken → niets schrijven.
 * Licentie: ODbL (© OpenStreetMap-bijdragers); een eigen laag, nooit samengevoegd met andere data.
 */
public class FetchRoads {

    static final List<String> SERVERS = List.of(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter");
    static final String HIGHWAYS = "motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|road|motorway_link|trunk_link|primary_link|secondary_link|tertiary_link";
    static final List<String> CLASSES = List.of(HIGHWAYS.split("\\|"));

    static final double LAT_MIN = 50.70, LAT_MAX = 53.60, LON_MIN = 3.30, LON_MAX = 7.25;
    /** Vakjes (in 1e-5 graden). */
    static final int TILE_LAT = 2000, TILE_LON = 3000;
    static final double SIMPLIFY_M = 3.0;
    /** Langere stukken worden opgeknipt, zodat de app altijd met de 3x3 vakjes rond je positie toe kan. */
    static final double MAX_SEGMENT_M = 900.0;
    static final int MIN_WAYS = 500_000;
    static final int BLOCKS_LAT = 6, BLOCKS_LON = 6;

    record Way(long id, int speed, int cls, int oneway, String cond, int[] lat, int[] lon) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Gebruik: java FetchRoads.java <uitvoer.bin.gz> --geojsonseq <wegen.geojsonseq>   (productie, uit osmium)");
            System.err.println("     of: java FetchRoads.java <uitvoer.bin.gz> --overpass s,w,n,e              (klein testgebied)");
            System.exit(2);
        }
        if (args[1].equals("--crossings")) { crossings(Path.of(args[2]), Path.of(args[0])); return; }
        if (args[1].equals("--restrictions")) { restrictions(Path.of(args[2]), Path.of(args[0]), false); return; }
        if (args[1].equals("--restrictions-overpass")) { restrictions(Path.of(args[2]), Path.of(args[0]), true); return; }
        Map<Long, Way> ways = new HashMap<>();
        Map<String, Integer> conds = new HashMap<>();
        double[] area;
        boolean test;
        if (args[1].equals("--geojsonseq")) {
            area = new double[]{LAT_MIN, LON_MIN, LAT_MAX, LON_MAX};
            test = false;
            readGeoJsonSeq(Path.of(args[2]), ways, conds);
        } else {
            area = java.util.Arrays.stream(args[2].split(",")).mapToDouble(Double::parseDouble).toArray();
            test = true;
            fetchBlock(area[0], area[1], area[2], area[3], ways, conds);
        }
        if (!test && ways.size() < MIN_WAYS) fail("Te weinig wegen (" + ways.size() + "), bestand blijft staan");
        if (ways.isEmpty()) fail("Geen wegen ontvangen");
        long withSpeed = ways.values().stream().filter(x -> x.speed() > 0).count();
        System.out.println("Wegen: " + ways.size() + ", met maximumsnelheid: " + withSpeed + ", tijdsregels: " + conds.size());
        byte[] data = write(ways.values(), conds, area);
        Path out = Path.of(args[0]);
        try (var gz = new GZIPOutputStream(Files.newOutputStream(out))) { gz.write(data); }
        System.out.println("Geschreven: " + out + " (" + data.length / 1024 + " kB, gzip " + Files.size(out) / 1024 + " kB)");
    }

    /**
     * Uitvoer van `osmium export -f geojsonseq`: per regel één Feature (LineString, [lon,lat]) met de
     * OSM-tags als properties en het id in "@id".
     */
    static void readGeoJsonSeq(Path file, Map<Long, Way> ways, Map<String, Integer> conds) throws Exception {
        long lines = 0, skipped = 0;
        try (var r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '\u001e') line = line.substring(1);
                if (line.isBlank()) continue;
                lines++;
                Map<?, ?> f = (Map<?, ?>) new Json(line).value();
                Map<?, ?> g = (Map<?, ?>) f.get("geometry");
                Map<?, ?> p = (Map<?, ?>) f.get("properties");
                if (g == null || p == null || !"LineString".equals(g.get("type"))) { skipped++; continue; }
                List<double[]> pts = new ArrayList<>();
                for (Object c : (List<?>) g.get("coordinates")) {
                    List<?> xy = (List<?>) c;
                    pts.add(new double[]{((Number) xy.get(1)).doubleValue(), ((Number) xy.get(0)).doubleValue()});
                }
                Map<String, String> tags = new HashMap<>();
                p.forEach((k, v) -> { if (v != null) tags.put(k.toString(), v instanceof Double d && d == Math.rint(d) ? String.valueOf(d.longValue()) : v.toString()); });
                Object idv = p.get("@id");
                long id = idv instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(idv).replaceAll("[^0-9]", ""));
                if ("yes".equals(tags.get("area")) || pts.size() < 2) { skipped++; continue; }
                Way w = toWay(id, tags, simplify(pts), conds);
                if (w != null) ways.put(id, w); else skipped++;
            }
        }
        System.out.println("GeoJSON-regels: " + lines + ", overgeslagen: " + skipped);
    }


    /**
     * Spoorwegovergangen (railway=level_crossing, uit osmium als geojsonseq-punten) naar een klein
     * JSON-bestand: {"format":1,...,"crossings":[[lat,lon],...]} (gzip).
     */
    static void crossings(Path in, Path out) throws Exception {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        try (var r = Files.newBufferedReader(in, StandardCharsets.UTF_8)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isEmpty() && line.charAt(0) == '\u001e') line = line.substring(1);
                if (line.isBlank()) continue;
                Map<?, ?> f = (Map<?, ?>) new Json(line).value();
                Map<?, ?> g = (Map<?, ?>) f.get("geometry");
                if (g == null || !"Point".equals(g.get("type"))) continue;
                List<?> c = (List<?>) g.get("coordinates");
                double lon = ((Number) c.get(0)).doubleValue(), lat = ((Number) c.get(1)).doubleValue();
                if (lat < LAT_MIN || lat > LAT_MAX || lon < LON_MIN || lon > LON_MAX) continue;
                if (n++ > 0) sb.append(',');
                sb.append(String.format(Locale.ROOT, "[%.6f,%.6f]", lat, lon));
            }
        }
        if (n < 500) fail("Te weinig spoorwegovergangen (" + n + ")");
        String json = "{\"format\":1,\"source\":\"osm\",\"attribution\":\"© OpenStreetMap-bijdragers\",\"license\":\"ODbL-1.0\",\"generatedAt\":"
                + System.currentTimeMillis() + ",\"count\":" + n + ",\"crossings\":[" + sb + "]}";
        try (var gz = new GZIPOutputStream(Files.newOutputStream(out))) { gz.write(json.getBytes(StandardCharsets.UTF_8)); }
        System.out.println("Spoorwegovergangen: " + n + " -> " + out);
    }

    // ---------------- afmetingsbeperkingen ----------------

    /** OSM-sleutel → korte soort in het bestand (h = hoogte m, w = gewicht t, b = breedte m, l = lengte m, a = aslast t). */
    static final Map<String, String> RESTRICTION_KEYS = new java.util.LinkedHashMap<>(Map.of(
            "maxheight", "h", "maxheight:physical", "h", "maxweight", "w", "maxwidth", "b", "maxwidth:physical", "b",
            "maxlength", "l", "maxaxleload", "a"));

    static final java.util.Set<String> HIGHWAYS_SET = java.util.Set.of(HIGHWAYS.split("[|]"));

    /**
     * Maximale hoogte, gewicht, breedte, lengte en aslast op wegen (en losse hoogtebalken), voor
     * vrachtwagens, campers en aanhangers. Uitvoer: {"format":1,...,"items":[{"k":"h","v":3.2,"ow":0,"p":[[lat,lon],...]}]}.
     * Invoer: osmium geojsonseq (productie) of Overpass-JSON met "out tags geom" (test, [overpass]=true).
     */
    static void restrictions(Path in, Path out, boolean overpass) throws Exception {
        List<String> items = new ArrayList<>();
        Map<String, Integer> perKind = new java.util.TreeMap<>();
        int unparsed = 0;
        List<Map.Entry<Map<String, String>, List<double[]>>> feats = new ArrayList<>();
        if (overpass) {
            Map<?, ?> root = (Map<?, ?>) new Json(Files.readString(in)).value();
            for (Object o : (List<?>) root.get("elements")) {
                Map<?, ?> e = (Map<?, ?>) o;
                Map<String, String> tags = new HashMap<>();
                Map<?, ?> t = (Map<?, ?>) e.get("tags");
                if (t != null) t.forEach((k, v) -> tags.put(k.toString(), String.valueOf(v)));
                List<double[]> pts = new ArrayList<>();
                if (e.get("geometry") instanceof List<?> gl) {
                    for (Object g : gl) { Map<?, ?> m = (Map<?, ?>) g; pts.add(new double[]{((Number) m.get("lat")).doubleValue(), ((Number) m.get("lon")).doubleValue()}); }
                } else if (e.get("lat") != null) {
                    pts.add(new double[]{((Number) e.get("lat")).doubleValue(), ((Number) e.get("lon")).doubleValue()});
                }
                feats.add(Map.entry(tags, pts));
            }
        } else {
            try (var r = Files.newBufferedReader(in, StandardCharsets.UTF_8)) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.isEmpty() && line.charAt(0) == '\u001e') line = line.substring(1);
                    if (line.isBlank()) continue;
                    Map<?, ?> f = (Map<?, ?>) new Json(line).value();
                    Map<?, ?> g = (Map<?, ?>) f.get("geometry");
                    Map<?, ?> p = (Map<?, ?>) f.get("properties");
                    if (g == null || p == null) continue;
                    Map<String, String> tags = new HashMap<>();
                    p.forEach((k, v) -> { if (v != null) tags.put(k.toString(), v.toString()); });
                    List<double[]> pts = new ArrayList<>();
                    if ("LineString".equals(g.get("type"))) {
                        for (Object c : (List<?>) g.get("coordinates")) { List<?> xy = (List<?>) c; pts.add(new double[]{((Number) xy.get(1)).doubleValue(), ((Number) xy.get(0)).doubleValue()}); }
                    } else if ("Point".equals(g.get("type"))) {
                        List<?> c = (List<?>) g.get("coordinates");
                        pts.add(new double[]{((Number) c.get(1)).doubleValue(), ((Number) c.get(0)).doubleValue()});
                    }
                    feats.add(Map.entry(tags, pts));
                }
            }
        }
        for (var f : feats) {
            Map<String, String> tags = f.getKey();
            List<double[]> pts = f.getValue();
            if (pts.isEmpty()) continue;
            boolean isWay = pts.size() >= 2;
            // Alleen wegen voor gemotoriseerd verkeer, of losse hoogtebalken/portalen (punten).
            if (isWay && (tags.get("highway") == null || !HIGHWAYS_SET.contains(tags.get("highway")))) continue;
            double[] a = pts.get(0);
            if (a[0] < LAT_MIN || a[0] > LAT_MAX || a[1] < LON_MIN || a[1] > LON_MAX) continue;
            Map<String, Double> best = new HashMap<>();
            for (var k : RESTRICTION_KEYS.entrySet()) {
                String raw = tags.get(k.getKey());
                if (raw == null) continue;
                Double v = restrictionValue(raw, k.getValue());
                if (v == null) { unparsed++; continue; }
                best.merge(k.getValue(), v, Math::min);
            }
            if (best.isEmpty()) continue;
            String ow = "yes".equals(tags.get("oneway")) || "motorway".equals(tags.get("highway")) ? "1" : "-1".equals(tags.get("oneway")) ? "-1" : "0";
            List<double[]> line = isWay ? simplify(pts) : pts;
            StringBuilder p = new StringBuilder();
            for (double[] q : line) { if (p.length() > 0) p.append(','); p.append(String.format(Locale.ROOT, "[%.6f,%.6f]", q[0], q[1])); }
            for (var e : best.entrySet()) {
                items.add(String.format(Locale.ROOT, "{\"k\":\"%s\",\"v\":%.2f,\"ow\":%s,\"p\":[%s]}", e.getKey(), e.getValue(), ow, p));
                perKind.merge(e.getKey(), 1, Integer::sum);
            }
        }
        String json = "{\"format\":1,\"source\":\"osm\",\"attribution\":\"© OpenStreetMap-bijdragers\",\"license\":\"ODbL-1.0\",\"generatedAt\":"
                + System.currentTimeMillis() + ",\"count\":" + items.size() + ",\"items\":[\n" + String.join(",\n", items) + "\n]}";
        System.out.println("Beperkingen: " + items.size() + " " + perKind + ", zonder getal (default/none): " + unparsed);
        if (!overpass && items.size() < 2000) fail("Te weinig beperkingen (" + items.size() + "), bestand blijft staan");
        try (var gz = new GZIPOutputStream(Files.newOutputStream(out))) { gz.write(json.getBytes(StandardCharsets.UTF_8)); }
        System.out.println("Geschreven: " + out + " (" + Files.size(out) / 1024 + " kB)");
    }

    /** "3.2", "3,2 m", "3.2m", "7.5 t", "3500 kg", 12'6" → getal in m of t; null = onbekend of geen ("none", "default"). */
    static Double restrictionValue(String raw, String kind) {
        String s = raw.trim().toLowerCase(Locale.ROOT).replace(',', '.');
        if (s.isEmpty() || s.matches("none|default|below_default|no_sign|no_indications|unsigned|signals|.*;.*")) return null;
        try {
            java.util.regex.Matcher ft = java.util.regex.Pattern.compile("^([0-9]+)'\\s*([0-9]+([.][0-9]+)?)?\"?$").matcher(s);
            if (ft.matches()) return Integer.parseInt(ft.group(1)) * 0.3048 + (ft.group(2) == null ? 0 : Double.parseDouble(ft.group(2)) * 0.0254);
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([0-9]+([.][0-9]+)?)\\s*(m|t|kg|st)?$").matcher(s);
            if (!m.matches()) return null;
            double v = Double.parseDouble(m.group(1));
            if ("kg".equals(m.group(3))) v /= 1000.0;
            boolean weight = kind.equals("w") || kind.equals("a");
            if (weight ? (v < 0.5 || v > 100) : (v < 1.0 || v > 30)) return null; // onzin
            return v;
        } catch (NumberFormatException e) { return null; }
    }

    // ---------------- ophalen ----------------

    static void fetchBlock(double s, double w, double n, double e, Map<Long, Way> ways, Map<String, Integer> conds) throws Exception {
        String bbox = String.format(Locale.ROOT, "%.4f,%.4f,%.4f,%.4f", s, w, n, e);
        String q = "[out:xml][timeout:300][maxsize:536870912];way[\"highway\"~\"^(" + HIGHWAYS + ")$\"][\"area\"!=\"yes\"](" + bbox + ");out tags geom qt;";
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30)).build();
        String body = "data=" + URLEncoder.encode(q, StandardCharsets.UTF_8);
        Exception last = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            String server = SERVERS.get((attempt - 1) % SERVERS.size());
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(server)).timeout(Duration.ofSeconds(330))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", "flitsapp-data/1 (wekelijkse wegenupdate)")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (res.statusCode() != 200) { res.body().close(); throw new IllegalStateException("HTTP " + res.statusCode()); }
                Map<Long, Way> got = new HashMap<>();
                try (InputStream in = res.body()) { parse(in, got, conds); }
                ways.putAll(got);
                return;
            } catch (Exception ex) {
                last = ex;
                System.err.println("Blok " + bbox + " poging " + attempt + " (" + server + "): " + ex.getMessage());
                Thread.sleep(attempt * 30_000L);
            }
        }
        fail("Blok " + bbox + " blijft mislukken: " + (last == null ? "" : last.getMessage()));
    }

    static void parse(InputStream in, Map<Long, Way> out, Map<String, Integer> conds) throws Exception {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XMLStreamReader r = f.createXMLStreamReader(in, "UTF-8");
        long id = 0;
        Map<String, String> tags = new HashMap<>();
        List<double[]> pts = new ArrayList<>();
        boolean inWay = false;
        boolean sawOsm = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                switch (r.getLocalName()) {
                    case "osm" -> sawOsm = true;
                    case "way" -> { inWay = true; id = Long.parseLong(r.getAttributeValue(null, "id")); tags.clear(); pts.clear(); }
                    case "nd" -> { if (inWay && r.getAttributeValue(null, "lat") != null)
                        pts.add(new double[]{Double.parseDouble(r.getAttributeValue(null, "lat")), Double.parseDouble(r.getAttributeValue(null, "lon"))}); }
                    case "tag" -> { if (inWay) tags.put(r.getAttributeValue(null, "k"), r.getAttributeValue(null, "v")); }
                    case "remark" -> throw new IllegalStateException("Overpass-opmerking (time-out of te groot)");
                    default -> { }
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT && r.getLocalName().equals("way")) {
                inWay = false;
                if (pts.size() >= 2) {
                    Way w = toWay(id, tags, simplify(pts), conds);
                    if (w != null) out.put(id, w);
                }
            }
        }
        if (!sawOsm) throw new IllegalStateException("Geen OSM-antwoord (server druk?)");
    }

    static Way toWay(long id, Map<String, String> t, List<double[]> pts, Map<String, Integer> conds) {
        int cls = CLASSES.indexOf(t.getOrDefault("highway", ""));
        if (cls < 0) return null;
        Integer speed = speed(t.get("maxspeed"));
        if (speed == null) {
            Integer f = speed(t.get("maxspeed:forward")), b = speed(t.get("maxspeed:backward"));
            if (f != null && b != null) speed = Math.min(f, b); else if (f != null) speed = f; else if (b != null) speed = b;
        }
        String ow = t.get("oneway");
        int oneway = "yes".equals(ow) || "1".equals(ow) || "true".equals(ow) ? 1 : "-1".equals(ow) ? 2
                : ow == null && (cls == 0 || "roundabout".equals(t.get("junction"))) ? 1 : 0;
        String cond = t.get("maxspeed:conditional");
        if (cond != null) conds.putIfAbsent(cond, conds.size());
        int[] la = new int[pts.size()], lo = new int[pts.size()];
        for (int i = 0; i < pts.size(); i++) { la[i] = (int) Math.round(pts.get(i)[0] * 1e5); lo[i] = (int) Math.round(pts.get(i)[1] * 1e5); }
        return new Way(id, speed == null ? 0 : speed, cls, oneway, cond, la, lo);
    }

    static final Pattern NUM = Pattern.compile("^\\s*(\\d{1,3})\\s*$");

    static Integer speed(String raw) {
        if (raw == null) return null;
        switch (raw.trim()) {
            case "NL:urban": return 50;
            case "NL:rural": return 80;
            case "NL:trunk": return 100;
            case "NL:motorway": return 130;
            case "NL:zone30": return 30;
            case "NL:zone60": return 60;
            case "walk": case "NL:walk": return 15;
            default: break;
        }
        Matcher m = NUM.matcher(raw);
        if (!m.find()) return null;
        int v = Integer.parseInt(m.group(1));
        return v >= 5 && v <= 130 ? v : null;
    }

    // ---------------- vereenvoudigen (Douglas-Peucker, in meters) ----------------

    static List<double[]> simplify(List<double[]> p) {
        if (p.size() <= 2) return new ArrayList<>(p);
        boolean[] keep = new boolean[p.size()];
        keep[0] = keep[p.size() - 1] = true;
        dp(p, 0, p.size() - 1, keep);
        List<double[]> r = new ArrayList<>();
        for (int i = 0; i < p.size(); i++) if (keep[i]) r.add(p.get(i));
        return r;
    }

    static void dp(List<double[]> p, int a, int b, boolean[] keep) {
        if (b <= a + 1) return;
        double max = -1; int idx = -1;
        for (int i = a + 1; i < b; i++) { double d = segDist(p.get(i), p.get(a), p.get(b)); if (d > max) { max = d; idx = i; } }
        if (max > SIMPLIFY_M) { keep[idx] = true; dp(p, a, idx, keep); dp(p, idx, b, keep); }
    }

    static double segDist(double[] q, double[] a, double[] b) {
        double kx = 111_320 * Math.cos(Math.toRadians(q[0])), ky = 110_540;
        double ax = (a[1] - q[1]) * kx, ay = (a[0] - q[0]) * ky, bx = (b[1] - q[1]) * kx, by = (b[0] - q[0]) * ky;
        double dx = bx - ax, dy = by - ay, l2 = dx * dx + dy * dy;
        double t = l2 == 0 ? 0 : Math.max(0, Math.min(1, -(ax * dx + ay * dy) / l2));
        double cx = ax + t * dx, cy = ay + t * dy;
        return Math.sqrt(cx * cx + cy * cy);
    }

    // ---------------- schrijven ----------------

    /**
     * Formaat FRD1 (big-endian):
     *   "FRD1", int versie=1, int latMin, lonMin, tileLat, tileLon (1e-5 graden), int rows, cols,
     *   long gemaakt, int nCond + nCond x UTF (maxspeed:conditional-teksten),
     *   int[rows*cols+1] offsets (t.o.v. begin data), daarna de data per vakje.
     * Per stuk weg in een vakje: varint snelheid (0 = onbekend), byte vlaggen (bit 0-1 eenrichting:
     * 0 beide, 1 tekenrichting, 2 tegen; bit 2-5 wegklasse), varint conditional+1 (0 = geen),
     * varint aantal punten, dan zigzag-varint delta's (lat, lon) in 1e-5 graden; het eerste punt
     * t.o.v. de linkeronderhoek van het vakje.
     */
    static byte[] write(java.util.Collection<Way> ways, Map<String, Integer> conds, double[] area) throws Exception {
        int latMin = (int) Math.floor(area[0] * 1e5 / TILE_LAT) * TILE_LAT, lonMin = (int) Math.floor(area[1] * 1e5 / TILE_LON) * TILE_LON;
        int rows = (int) Math.ceil((area[2] * 1e5 - latMin) / TILE_LAT) + 1, cols = (int) Math.ceil((area[3] * 1e5 - lonMin) / TILE_LON) + 1;
        ByteArrayOutputStream[] tiles = new ByteArrayOutputStream[rows * cols];
        int pieces = 0;
        for (Way w : ways) {
            // Lange stukken opknippen, daarna per vakje knippen.
            List<int[]> pts = densify(w);
            int start = 0;
            int tile = tileOf(pts.get(0), latMin, lonMin, rows, cols);
            for (int i = 1; i < pts.size(); i++) {
                int t = tileOf(pts.get(i), latMin, lonMin, rows, cols);
                if (t != tile || i == pts.size() - 1) {
                    if (tile >= 0) { piece(tiles, tile, w, pts.subList(start, i + 1), conds, latMin, lonMin, cols); pieces++; }
                    start = i; tile = t;
                }
            }
        }
        ByteArrayOutputStream all = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(all);
        d.writeBytes("FRD1"); d.writeInt(1);
        d.writeInt(latMin); d.writeInt(lonMin); d.writeInt(TILE_LAT); d.writeInt(TILE_LON); d.writeInt(rows); d.writeInt(cols);
        d.writeLong(System.currentTimeMillis());
        String[] cs = new String[conds.size()];
        conds.forEach((k, v) -> cs[v] = k);
        d.writeInt(cs.length);
        for (String c : cs) d.writeUTF(c);
        int off = 0;
        for (ByteArrayOutputStream t : tiles) { d.writeInt(off); off += t == null ? 0 : t.size(); }
        d.writeInt(off);
        for (ByteArrayOutputStream t : tiles) if (t != null) t.writeTo(d);
        d.flush();
        System.out.println("Stukken weg in vakjes: " + pieces + " (" + rows + "x" + cols + " vakjes)");
        return all.toByteArray();
    }

    static List<int[]> densify(Way w) {
        List<int[]> r = new ArrayList<>();
        for (int i = 0; i < w.lat().length; i++) {
            if (i > 0) {
                int[] a = r.get(r.size() - 1);
                double len = segDist(new double[]{a[0] / 1e5, a[1] / 1e5}, new double[]{w.lat()[i] / 1e5, w.lon()[i] / 1e5}, new double[]{w.lat()[i] / 1e5, w.lon()[i] / 1e5});
                int parts = (int) Math.ceil(len / MAX_SEGMENT_M);
                for (int k = 1; k < parts; k++) r.add(new int[]{a[0] + (int) Math.round((w.lat()[i] - a[0]) * (double) k / parts), a[1] + (int) Math.round((w.lon()[i] - a[1]) * (double) k / parts)});
            }
            r.add(new int[]{w.lat()[i], w.lon()[i]});
        }
        return r;
    }

    static int tileOf(int[] p, int latMin, int lonMin, int rows, int cols) {
        int r = Math.floorDiv(p[0] - latMin, TILE_LAT), c = Math.floorDiv(p[1] - lonMin, TILE_LON);
        return r < 0 || c < 0 || r >= rows || c >= cols ? -1 : r * cols + c;
    }

    static void piece(ByteArrayOutputStream[] tiles, int tile, Way w, List<int[]> pts, Map<String, Integer> conds, int latMin, int lonMin, int cols) {
        ByteArrayOutputStream o = tiles[tile] == null ? (tiles[tile] = new ByteArrayOutputStream()) : tiles[tile];
        varint(o, w.speed());
        o.write((w.oneway() & 3) | ((w.cls() & 15) << 2));
        varint(o, w.cond() == null ? 0 : conds.get(w.cond()) + 1);
        varint(o, pts.size());
        int pLat = latMin + (tile / cols) * TILE_LAT, pLon = lonMin + (tile % cols) * TILE_LON;
        for (int[] p : pts) { varint(o, zig(p[0] - pLat)); varint(o, zig(p[1] - pLon)); pLat = p[0]; pLon = p[1]; }
    }

    static int zig(int v) { return (v << 1) ^ (v >> 31); }

    static void varint(ByteArrayOutputStream o, int v) {
        while ((v & ~0x7F) != 0) { o.write((v & 0x7F) | 0x80); v >>>= 7; }
        o.write(v);
    }

    static void fail(String msg) { System.err.println("FOUT: " + msg); System.exit(1); }
    /** Minimale JSON-lezer/schrijver (geen library nodig). */
    static class Json {
        private final String s;
        private int i;
        Json(String s) { this.s = s; }

        Object value() {
            ws();
            char c = s.charAt(i);
            if (c == '{') { i++; Map<String, Object> m = new LinkedHashMap<>(); ws();
                if (s.charAt(i) == '}') { i++; return m; }
                while (true) { ws(); String k = (String) value(); ws(); i++; m.put(k, value()); ws();
                    if (s.charAt(i++) == '}') return m; } }
            if (c == '[') { i++; List<Object> l = new ArrayList<>(); ws();
                if (s.charAt(i) == ']') { i++; return l; }
                while (true) { l.add(value()); ws(); if (s.charAt(i++) == ']') return l; } }
            if (c == '"') { i++; StringBuilder b = new StringBuilder();
                while (true) { char d = s.charAt(i++);
                    if (d == '"') return b.toString();
                    if (d == '\\') { char e = s.charAt(i++);
                        switch (e) { case 'n' -> b.append('\n'); case 't' -> b.append('\t'); case 'r' -> b.append('\r');
                            case 'b' -> b.append('\b'); case 'f' -> b.append('\f');
                            case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                            default -> b.append(e); } }
                    else b.append(d); } }
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            int st = i; while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            return Double.parseDouble(s.substring(st, i));
        }

        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        static String write(Object o) {
            if (o == null) return "null";
            if (o instanceof String str) {
                StringBuilder b = new StringBuilder("\"");
                for (char c : str.toCharArray()) {
                    switch (c) { case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\"); case '\n' -> b.append("\\n");
                        case '\r' -> b.append("\\r"); case '\t' -> b.append("\\t");
                        default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } }
                }
                return b.append('"').toString();
            }
            if (o instanceof Double d) {
                if (d == Math.rint(d) && Math.abs(d) < 1e15) return String.valueOf(d.longValue());
                return String.format(Locale.ROOT, "%.5f", d).replaceAll("0+$", "");
            }
            if (o instanceof Boolean) return o.toString();
            if (o instanceof Map<?, ?> m) {
                StringBuilder b = new StringBuilder("{"); boolean first = true;
                for (var e : m.entrySet()) { if (!first) b.append(','); first = false; b.append(write(e.getKey().toString())).append(':').append(write(e.getValue())); }
                return b.append('}').toString();
            }
            if (o instanceof List<?> l) {
                StringBuilder b = new StringBuilder("["); for (int k = 0; k < l.size(); k++) { if (k > 0) b.append(','); b.append(write(l.get(k))); }
                return b.append(']').toString();
            }
            return write(o.toString());
        }
    }
}
