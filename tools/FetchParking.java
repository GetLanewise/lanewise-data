import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPOutputStream;

/**
 * Parkeergarages en -terreinen in Nederland (RDW Open Data Parkeren, CC0). Combineert:
 *  - SPECIFICATIES PARKEERGEBIED (b3us-f26s): capaciteit, laadpunten, maximale hoogte;
 *  - GEOMETRIE GEBIED (nsk3-v9n7): punt of vlak;
 *  - PARKEERGEBIED (mz4f-59fw): uuid voor de naam;
 *  - NPR-index (npropendata.rdw.nl): naam per uuid.
 * Uitvoer: {"format":1,...,"items":[{"n":"P+R Station X","lat":..,"lon":..,"cap":120,"ev":4,"h":2.1}]}
 *
 * Gebruik: java tools/FetchParking.java uit.json.gz
 */
public class FetchParking {
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

    public static void main(String[] a) throws Exception {
        List<Map<String, String>> specs = rows(get("https://opendata.rdw.nl/resource/b3us-f26s.json?$limit=50000"));
        List<Map<String, String>> geos = rows(get("https://opendata.rdw.nl/resource/nsk3-v9n7.json?$limit=50000"));
        List<Map<String, String>> areas = rows(get("https://opendata.rdw.nl/resource/mz4f-59fw.json?$limit=100000"));
        String index = get("https://npropendata.rdw.nl/parkingdata/v2/");
        Map<String, String> names = new HashMap<>();
        Matcher nm = Pattern.compile("\"name\"\\s*:\\s*\"([^\"]*)\",\\s*\"identifier\"\\s*:\\s*\"([^\"]+)\"").matcher(index);
        while (nm.find()) names.put(nm.group(2), nm.group(1));

        long now = System.currentTimeMillis();
        Map<String, double[]> pos = new HashMap<>();
        for (var g : geos) {
            boolean old = ended(g.get("enddatearea"));
            double[] p = point(g.getOrDefault("areageometryastext", ""));
            String gk = g.get("areamanagerid") + "|" + g.get("areaid");
            if (p != null && (!old || !pos.containsKey(gk))) pos.put(gk, p);
        }
        Map<String, String> uuid = new HashMap<>();
        for (var r : areas) if (r.get("uuid") != null) uuid.put(r.get("areamanagerid") + "|" + r.get("areaid"), r.get("uuid"));

        StringBuilder sb = new StringBuilder();
        int n = 0, noName = 0, cEnded = 0, cNoPos = 0, cStraat = 0, cCap = 0;
        Set<String> seen = new HashSet<>();
        // Ontbrekende plekken aanvullen uit de NPR-gegevens per locatie (toegangspunt), 8 tegelijk.
        List<String> missing = new ArrayList<>();
        for (var s : specs) {
            String key = s.get("areamanagerid") + "|" + s.get("areaid");
            if (!pos.containsKey(key) && uuid.containsKey(key) && !ended(s.get("enddatespecifications"))) missing.add(key);
        }
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        Map<String, double[]> extra = new java.util.concurrent.ConcurrentHashMap<>();
        Pattern lat = Pattern.compile("\"latitude\"\\s*:\\s*(-?[0-9.]+)"), lon = Pattern.compile("\"longitude\"\\s*:\\s*(-?[0-9.]+)");
        for (String key : missing) pool.submit(() -> {
            try {
                var req = HttpRequest.newBuilder(URI.create("https://npropendata.rdw.nl/parkingdata/v2/static/" + uuid.get(key)))
                        .header("User-Agent", "lanewise-data/1 (wekelijks)").build();
                var r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() != 200) return;
                Matcher a1 = lat.matcher(r.body()), o1 = lon.matcher(r.body());
                if (a1.find() && o1.find()) extra.put(key, new double[]{Double.parseDouble(a1.group(1)), Double.parseDouble(o1.group(1))});
            } catch (Exception e) { /* overslaan */ }
        });
        pool.shutdown();
        pool.awaitTermination(30, java.util.concurrent.TimeUnit.MINUTES);
        pos.putAll(extra);
        System.out.println("Aangevuld uit NPR: " + extra.size() + " van " + missing.size());
        // Plausibiliteit: de naam eindigt meestal op "(Plaats)". Ligt een plek > 15 km van het midden
        // (mediaan) van de andere plekken in die plaats, dan klopt de RDW-positie niet (gezien: VUmc
        // Amsterdam met de coördinaten van een garage in Utrecht). Die plek laten we weg.
        Map<String, List<double[]>> perCity = new HashMap<>();
        for (var s : specs) {
            String key = s.get("areamanagerid") + "|" + s.get("areaid");
            double[] p = pos.get(key); String city = city(names.get(uuid.getOrDefault(key, "")));
            if (p != null && city != null) perCity.computeIfAbsent(city, k -> new ArrayList<>()).add(p);
        }
        Map<String, double[]> cityMid = new HashMap<>();
        perCity.forEach((c, l) -> { if (l.size() >= 3) cityMid.put(c, new double[]{median(l, 0), median(l, 1)}); });
        int cFar = 0;
        for (var s : specs) {
            if (ended(s.get("enddatespecifications"))) { cEnded++; continue; }
            String k0 = s.get("areamanagerid") + "|" + s.get("areaid");
            double[] p0 = pos.get(k0); double[] mid = cityMid.get(city(names.get(uuid.getOrDefault(k0, ""))));
            if (p0 != null && mid != null && km(p0, mid) > 15) { cFar++; continue; }
            String key = s.get("areamanagerid") + "|" + s.get("areaid");
            if (!seen.add(key)) continue;
            double[] p = pos.get(key);
            if (p == null || p[0] < 50.7 || p[0] > 53.6 || p[1] < 3.3 || p[1] > 7.3) { cNoPos++; continue; }
            String name = names.get(uuid.getOrDefault(key, ""));
            if (name == null || name.isBlank()) { noName++; continue; }
            if (name.toLowerCase(Locale.ROOT).startsWith("straatparkeren")) { cStraat++; continue; }
            int cap = parseInt(s.get("capacity"));
            if (cap <= 0) { cCap++; continue; }
            int ev = parseInt(s.get("chargingpointcapacity"));
            int h = parseInt(s.get("maximumvehicleheight")); // cm
            if (n++ > 0) sb.append(",\n");
            sb.append(String.format(Locale.ROOT, "{\"n\":\"%s\",\"lat\":%.6f,\"lon\":%.6f,\"cap\":%d%s%s}",
                    name.replace("\\", "").replace("\"", "'"), p[0], p[1], cap,
                    ev > 0 ? ",\"ev\":" + ev : "", h >= 150 && h <= 600 ? String.format(Locale.ROOT, ",\"h\":%.2f", h / 100.0) : ""));
        }
        System.out.println("Parkeren: " + specs.size() + " specificaties, " + pos.size() + " locaties, " + names.size() + " namen -> " + n + " plekken (" + noName + " zonder naam, " + cEnded + " beëindigd, " + cNoPos + " zonder plek, " + cStraat + " straat, " + cCap + " zonder capaciteit, " + cFar + " ver van eigen plaats)");
        if (n < 500) { System.err.println("FOUT: te weinig parkeerplekken (" + n + "), bestand blijft staan"); System.exit(1); }
        String out = "{\"format\":1,\"source\":\"rdw-parkeren\",\"license\":\"CC0-1.0\",\"attribution\":\"RDW Open Data Parkeren\",\"generatedAt\":"
                + now + ",\"count\":" + n + ",\"items\":[\n" + sb + "\n]}";
        try (var gz = new GZIPOutputStream(Files.newOutputStream(Path.of(a[0])))) { gz.write(out.getBytes(StandardCharsets.UTF_8)); }
        System.out.println("Geschreven: " + a[0] + " (" + Files.size(Path.of(a[0])) / 1024 + " kB)");
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) Files.writeString(Path.of(summary), "### Parkeren\n\n" + n + " garages en terreinen\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String city(String name) {
        if (name == null) return null;
        int a = name.lastIndexOf('('), b = name.lastIndexOf(')');
        return a >= 0 && b > a ? name.substring(a + 1, b).trim().toLowerCase(Locale.ROOT) : null;
    }

    static double median(List<double[]> l, int i) {
        double[] v = l.stream().mapToDouble(p -> p[i]).sorted().toArray();
        return v[v.length / 2];
    }

    static double km(double[] a, double[] b) {
        double dy = (a[0] - b[0]) * 111.2, dx = (a[1] - b[1]) * 111.2 * Math.cos(Math.toRadians(a[0]));
        return Math.sqrt(dx * dx + dy * dy);
    }

    static boolean ended(String d) {
        if (d == null || d.isBlank()) return false;
        String digits = d.replaceAll("[^0-9]", "");
        if (digits.length() < 8) return false;
        return digits.substring(0, 8).compareTo(java.time.LocalDate.now().toString().replace("-", "")) < 0;
    }

    static int parseInt(String s) { try { return s == null ? 0 : Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return 0; } }

    /** POINT (lon lat) of het gemiddelde van de eerste ring van een (MULTI)POLYGON. */
    static double[] point(String wkt) {
        Matcher m = Pattern.compile("(-?[0-9.]+) (-?[0-9.]+)").matcher(wkt);
        double la = 0, lo = 0; int c = 0;
        int ring = wkt.indexOf(')');
        while (m.find() && (ring < 0 || m.start() < ring)) { lo += Double.parseDouble(m.group(1)); la += Double.parseDouble(m.group(2)); c++; }
        return c == 0 ? null : new double[]{la / c, lo / c};
    }

    /** Zeer eenvoudige JSON-rijen van Socrata: lijst van platte objecten met tekstwaarden. */
    static List<Map<String, String>> rows(String json) {
        List<Map<String, String>> out = new ArrayList<>();
        Matcher obj = Pattern.compile("\\{([^{}]*)\\}").matcher(json);
        Pattern kv = Pattern.compile("\"([a-z_]+)\"\\s*:\\s*\"((?:[^\"\\\\]++|\\\\.)*+)\"");
        while (obj.find()) {
            Map<String, String> m = new HashMap<>();
            Matcher k = kv.matcher(obj.group(1));
            while (k.find()) m.put(k.group(1), k.group(2));
            out.add(m);
        }
        return out;
    }

    static String get(String url) throws Exception {
        var req = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "lanewise-data/1 (wekelijks)").build();
        for (int i = 0; i < 4; i++) {
            try {
                var r = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return r.body();
            } catch (Exception e) { /* opnieuw */ }
            Thread.sleep(20_000L * (i + 1));
        }
        System.err.println("FOUT: download mislukt: " + url);
        System.exit(1);
        return null;
    }
}
