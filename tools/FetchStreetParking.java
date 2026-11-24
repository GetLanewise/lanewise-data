import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPOutputStream;

/**
 * Betaald parkeren aan de weg in Nederland (RDW Open Data Parkeren, CC0). Combineert:
 *  - GEBIEDSREGELING (qtex-qwd8): gebieden met gebruiksdoel BETAALDP en hun regeling;
 *  - GEOMETRIE GEBIED (nsk3-v9n7): vlak van het gebied;
 *  - GEBIED (adw6-9hsg): naam van het gebied; GEBIEDSBEHEERDER (2uc2-nnv3): gemeente;
 *  - TIJDVAK (ixf8-gtwq): per dag van/tot, tariefcode, maximale parkeerduur;
 *  - TARIEFDEEL (534e-5vdg): tarief per tariefcode (bedrag per stap, per duurblok).
 * Uitvoer: {"format":1,...,"areas":[{"id","n","m","p":[[lat,lon,...]],"r":[[dag,van,tot,code,maxMin]]}],"fares":{code:[[vanMin,totMin,bedrag,stapMin]]}}
 *
 * Gebruik: java tools/FetchStreetParking.java uit.json.gz
 */
public class FetchStreetParking {
    static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    static final String TODAY = java.time.LocalDate.now().toString().replace("-", "");

    public static void main(String[] a) throws Exception {
        Map<String, String> managers = new HashMap<>();
        for (var r : all("2uc2-nnv3", "")) if (active(r.get("enddateareamanagerid"))) managers.put(r.get("areamanagerid"), fixName(r.get("areamanagerdesc")));
        Map<String, String> areaNames = new HashMap<>();
        for (var r : all("adw6-9hsg", "")) if (active(r.get("enddatearea"))) areaNames.put(key(r), fixName(r.get("areadesc")));

        Map<String, String> regOf = new HashMap<>();
        for (var r : all("qtex-qwd8", "usageid='BETAALDP'"))
            if (active(r.get("enddatearearegulation")) && r.get("regulationid") != null) regOf.putIfAbsent(key(r), r.get("areamanagerid") + "|" + r.get("regulationid"));

        Map<String, String> geom = new HashMap<>();
        for (var r : all("nsk3-v9n7", "")) {
            String k = key(r);
            if (!regOf.containsKey(k)) continue;
            String w = r.get("areageometryastext");
            if (w == null || !w.contains("POLYGON")) continue;
            if (active(r.get("enddatearea")) || !geom.containsKey(k)) geom.put(k, w);
        }

        // Tijdvakken per regeling
        Set<String> regs = new HashSet<>(regOf.values());
        Map<String, List<String>> frames = new HashMap<>();
        Set<String> usedFares = new HashSet<>();
        for (var r : all("ixf8-gtwq", "")) {
            String rk = r.get("areamanagerid") + "|" + r.get("regulationid");
            if (!regs.contains(rk) || !active(r.get("enddatetimeframe"))) continue;
            if (!"J".equals(r.get("claimrightpossible"))) continue; // alleen tijden waarin je (betaald) recht kunt kopen
            String fare = r.get("areamanagerid") + "|" + r.get("farecalculationcode");
            usedFares.add(fare);
            frames.computeIfAbsent(rk, x -> new ArrayList<>()).add(String.format(Locale.ROOT, "[\"%s\",%d,%d,\"%s\",%d]",
                    r.get("daytimeframe"), parseInt(r.get("starttimetimeframe")), parseInt(r.get("endtimetimeframe")),
                    esc(fare), parseInt(r.get("maxdurationright"))));
        }
        // Tarieven die nu gelden
        Map<String, List<String>> fares = new TreeMap<>();
        for (var r : all("534e-5vdg", "")) {
            String fk = r.get("areamanagerid") + "|" + r.get("farecalculationcode");
            if (!usedFares.contains(fk)) continue;
            String s = r.getOrDefault("startdatefarepart", ""), e = r.getOrDefault("enddatefarepart", "");
            if (s.length() >= 8 && s.substring(0, 8).compareTo(TODAY) > 0) continue;
            if (!active(e)) continue;
            fares.computeIfAbsent(fk, x -> new ArrayList<>()).add(String.format(Locale.ROOT, "[%d,%d,%s,%d]",
                    parseInt(r.get("startdurationfarepart")), parseInt(r.get("enddurationfarepart")),
                    num(r.get("amountfarepart")), Math.max(1, parseInt(r.get("stepsizefarepart")))));
        }

        StringBuilder sb = new StringBuilder();
        int n = 0, noGeom = 0, noFrames = 0, points = 0;
        for (var e : new TreeMap<>(regOf).entrySet()) {
            String k = e.getKey();
            String w = geom.get(k);
            if (w == null) { noGeom++; continue; }
            List<String> fr = frames.get(e.getValue());
            if (fr == null || fr.isEmpty()) { noFrames++; continue; }
            List<String> polys = polygons(w);
            if (polys.isEmpty()) { noGeom++; continue; }
            for (String p : polys) points += p.split(",").length / 2;
            String[] parts = k.split("\\|", 2);
            if (n++ > 0) sb.append(",\n");
            sb.append("{\"id\":\"").append(esc(parts[1])).append("\",\"n\":\"").append(esc(areaNames.getOrDefault(k, parts[1])))
              .append("\",\"m\":\"").append(esc(managers.getOrDefault(parts[0], ""))).append("\",\"p\":[")
              .append(String.join(",", polys)).append("],\"r\":[").append(String.join(",", fr)).append("]}");
        }
        StringBuilder fs = new StringBuilder();
        for (var f : fares.entrySet()) {
            if (fs.length() > 0) fs.append(",\n");
            fs.append('"').append(esc(f.getKey())).append("\":[").append(String.join(",", f.getValue())).append(']');
        }
        System.out.println("Betaald parkeren: " + regOf.size() + " gebieden BETAALDP -> " + n + " met vlak en tijden (" + noGeom + " zonder vlak, " + noFrames + " zonder tijden), " + fares.size() + " tarieven, " + points + " punten");
        if (n < 500) { System.err.println("FOUT: te weinig gebieden (" + n + "), bestand blijft staan"); System.exit(1); }
        String out = "{\"format\":1,\"source\":\"rdw-betaald-parkeren\",\"license\":\"CC0-1.0\",\"attribution\":\"RDW Open Data Parkeren\",\"generatedAt\":"
                + System.currentTimeMillis() + ",\"count\":" + n + ",\"areas\":[\n" + sb + "\n],\"fares\":{\n" + fs + "\n}}";
        try (var gz = new GZIPOutputStream(Files.newOutputStream(Path.of(a[0])))) { gz.write(out.getBytes(StandardCharsets.UTF_8)); }
        System.out.println("Geschreven: " + a[0] + " (" + Files.size(Path.of(a[0])) / 1024 + " kB)");
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) Files.writeString(Path.of(summary), "### Betaald parkeren\n\n" + n + " gebieden, " + fares.size() + " tarieven\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** De RDW-bron bevat soms kapotte tekens (U+FFFD); bekende namen herstellen, anders het teken weghalen. */
    static String fixName(String s) {
        if (s == null || s.indexOf('�') < 0) return s;
        String t = s.replace("S�dwest-Frysl�n", "Súdwest-Fryslân").replace("Noardeast-Frysl�n", "Noardeast-Fryslân");
        return t.replace("�", "");
    }

    static String key(Map<String, String> r) { return r.get("areamanagerid") + "|" + r.get("areaid"); }

    static boolean active(String d) {
        if (d == null || d.isBlank()) return true;
        String digits = d.replaceAll("[^0-9]", "");
        return digits.length() < 8 || digits.substring(0, 8).compareTo(TODAY) >= 0;
    }

    static int parseInt(String s) { try { return s == null ? 0 : (int) Math.round(Double.parseDouble(s.trim())); } catch (NumberFormatException e) { return 0; } }
    static String num(String s) { try { return String.format(Locale.ROOT, "%.4f", Double.parseDouble(s)); } catch (Exception e) { return "0"; } }
    static String esc(String s) { return s == null ? "" : s.replace("\\", "").replace("\"", "'"); }

    /** Buitenringen van (MULTI)POLYGON als [lat,lon,lat,lon,...], vereenvoudigd tot hooguit 60 punten. */
    static List<String> polygons(String wkt) {
        List<String> out = new ArrayList<>();
        Matcher ring = Pattern.compile("\\(\\(([^()]+)\\)").matcher(wkt); // eerste ring na "((" = buitenrand
        while (ring.find()) {
            String[] pts = ring.group(1).trim().split("\\s*,\\s*");
            if (pts.length < 3) continue;
            int step = Math.max(1, (int) Math.ceil(pts.length / 60.0));
            StringBuilder sb = new StringBuilder("[");
            int c = 0;
            for (int i = 0; i < pts.length; i += step) {
                String[] xy = pts[i].trim().split("\\s+");
                if (xy.length < 2) continue;
                double lon = Double.parseDouble(xy[0]), lat = Double.parseDouble(xy[1]);
                if (lat < 50.6 || lat > 53.7 || lon < 3.2 || lon > 7.3) { c = -999; break; }
                if (c++ > 0) sb.append(',');
                sb.append(String.format(Locale.ROOT, "%.5f,%.5f", lat, lon));
            }
            if (c >= 3) out.add(sb.append(']').toString());
        }
        return out;
    }

    /** Alle rijen van een Socrata-dataset (in blokken van 50.000). */
    static List<Map<String, String>> all(String id, String where) throws Exception {
        List<Map<String, String>> out = new ArrayList<>();
        for (int off = 0; ; off += 50000) {
            String url = "https://opendata.rdw.nl/resource/" + id + ".json?$limit=50000&$offset=" + off + "&$order=:id"
                    + (where.isEmpty() ? "" : "&$where=" + java.net.URLEncoder.encode(where, StandardCharsets.UTF_8));
            List<Map<String, String>> rows = rows(get(url));
            out.addAll(rows);
            if (rows.size() < 50000) return out;
        }
    }

    static List<Map<String, String>> rows(String json) {
        List<Map<String, String>> out = new ArrayList<>();
        Matcher obj = Pattern.compile("\\{((?:[^{}\"]++|\"(?:[^\"\\\\]++|\\\\.)*+\")*+)\\}").matcher(json);
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
