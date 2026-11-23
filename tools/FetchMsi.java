import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Locaties van de matrixborden (MSI) boven de snelwegen, uit de NDW-shapefile (open data, CC0).
 * De app combineert dit met het live-bestand "Matrixsignaalinformatie" om te zeggen welke
 * rijstrook dicht is (rood kruis). Eén regel per portaal: weg, rijbaan, km, plek, richting en per
 * rijstrook het id van het bord.
 *
 * Gebruik: java tools/FetchMsi.java uit.json.gz [shapefile-zip]
 * Zonder zip wordt https://opendata.ndw.nu/ndw_msi_shapefiles_latest.zip gedownload.
 */
public class FetchMsi {
    static final String URL = "https://opendata.ndw.nu/ndw_msi_shapefiles_latest.zip";
    static final int FORMAT = 1;

    public static void main(String[] a) throws Exception {
        Path out = Path.of(a[0]);
        byte[] zip = a.length > 1 ? Files.readAllBytes(Path.of(a[1])) : download();
        byte[] shp = null, dbf = null;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (e.getName().endsWith("shapes.shp")) shp = z.readAllBytes();
                if (e.getName().endsWith("shapes.dbf")) dbf = z.readAllBytes();
            }
        }
        if (shp == null || dbf == null) fail("shapes.shp/.dbf niet in de zip");
        List<double[]> pts = points(shp);
        List<Map<String, String>> recs = records(dbf);
        if (pts.size() != recs.size()) fail("shp en dbf verschillen: " + pts.size() + " vs " + recs.size());

        // Groeperen per portaal: weg + rijbaan + km
        Map<String, List<Integer>> groups = new TreeMap<>();
        for (int i = 0; i < recs.size(); i++) {
            Map<String, String> r = recs.get(i);
            String road = road(r);
            String km = r.getOrDefault("km", "").trim();
            if (road.isEmpty() || km.isEmpty() || r.getOrDefault("uuid", "").isBlank()) continue;
            Double b0 = parse(r.get("bearing"));
            String dir = b0 == null ? "" : String.valueOf(Math.round(((b0 % 360) + 360) % 360 / 90.0) % 4);
            groups.computeIfAbsent(road + "|" + field(r, "carriagew") + "|" + km + "|" + dir, k -> new ArrayList<>()).add(i);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("{\n\"format\":").append(FORMAT).append(",\n\"source\":\"ndw-msi\",\n\"license\":\"CC0-1.0\",\n\"attribution\":\"NDW (Nationaal Dataportaal Wegverkeer)\",\n\"generatedAt\":")
          .append(System.currentTimeMillis()).append(",\n\"gantries\":[\n");
        int n = 0, signs = 0;
        double latMin = 50.6, latMax = 53.7, lonMin = 3.2, lonMax = 7.3;
        int outside = 0;
        for (var g : groups.entrySet()) {
            String[] k = g.getKey().split("\\|", -1);
            double lat = 0, lon = 0; int c = 0;
            List<String> lanes = new ArrayList<>();
            Double brg = null;
            for (int i : g.getValue()) {
                double[] p = pts.get(i);
                if (Double.isNaN(p[0])) continue;
                lat += p[1]; lon += p[0]; c++;
                Map<String, String> r = recs.get(i);
                String lane = r.getOrDefault("lane", "").trim();
                if (!lane.isEmpty()) lanes.add("[\"" + esc(lane) + "\",\"" + esc(r.get("uuid").trim()) + "\"]");
                if (brg == null) brg = parse(r.get("bearing"));
            }
            if (c == 0 || lanes.isEmpty()) continue;
            lat /= c; lon /= c;
            if (lat < latMin || lat > latMax || lon < lonMin || lon > lonMax) { outside++; continue; }
            if (n++ > 0) sb.append(",\n");
            sb.append(String.format(Locale.ROOT, "{\"road\":\"%s\",\"cw\":\"%s\",\"km\":%s,\"lat\":%.6f,\"lon\":%.6f%s,\"lanes\":[%s]}",
                    esc(k[0]), esc(k[1]), k[2], lat, lon, brg == null ? "" : String.format(Locale.ROOT, ",\"brg\":%.1f", brg), String.join(",", lanes)));
            signs += lanes.size();
        }
        sb.append("\n]\n}\n");

        // Datacontrole vóór publicatie
        List<String> report = new ArrayList<>();
        report.add("Matrixborden: " + recs.size() + " borden in de shapefile, " + n + " portalen, " + signs + " rijstrookborden, " + outside + " buiten NL");
        if (n < 1000) fail("Te weinig portalen (" + n + "): bron waarschijnlijk stuk. Oud bestand blijft staan.");
        try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(out))) {
            os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        report.add("Geschreven: " + out + " (" + Files.size(out) / 1024 + " kB)");
        report.forEach(System.out::println);
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) Files.writeString(Path.of(summary), "### Matrixborden\n\n" + String.join("\n\n", report) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String road(Map<String, String> r) {
        String o = r.getOrDefault("orig_road", "").trim();
        if (!o.isEmpty()) return o.replace(" ", "");
        String road = r.getOrDefault("road", "").trim();
        if (road.matches("0*[0-9]+")) return "A" + Integer.parseInt(road);
        return road;
    }

    /** Veldnamen in dBASE zijn max. 10 tekens en soms afgekapt ("carriagew…"): zoek op begin. */
    static String field(Map<String, String> r, String prefix) {
        for (var e : r.entrySet()) if (e.getKey().startsWith(prefix)) return e.getValue().trim();
        return "";
    }

    static Double parse(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    static String esc(String s) { return s.replace("\\", "").replace("\"", ""); }

    /** Punten uit een .shp (alleen type 1, Point). [x=lon, y=lat]; NaN bij een leeg record. */
    static List<double[]> points(byte[] b) {
        List<double[]> out = new ArrayList<>();
        int pos = 100;
        while (pos + 8 <= b.length) {
            int contentLen = ByteBuffer.wrap(b, pos + 4, 4).order(ByteOrder.BIG_ENDIAN).getInt() * 2;
            ByteBuffer c = ByteBuffer.wrap(b, pos + 8, contentLen).order(ByteOrder.LITTLE_ENDIAN);
            int type = c.getInt();
            if (type == 1) out.add(new double[]{c.getDouble(), c.getDouble()});
            else out.add(new double[]{Double.NaN, Double.NaN});
            pos += 8 + contentLen;
        }
        return out;
    }

    /** Records uit een .dbf (dBASE III), alle velden als tekst. */
    static List<Map<String, String>> records(byte[] b) {
        ByteBuffer h = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        int count = h.getInt(4), headerLen = h.getShort(8) & 0xFFFF, recLen = h.getShort(10) & 0xFFFF;
        List<String> names = new ArrayList<>();
        List<Integer> lens = new ArrayList<>();
        for (int p = 32; p < headerLen - 1 && b[p] != 0x0D; p += 32) {
            int end = p;
            while (end < p + 11 && b[end] != 0) end++;
            names.add(new String(b, p, end - p, StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT));
            lens.add(b[p + 16] & 0xFF);
        }
        List<Map<String, String>> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int p = headerLen + i * recLen;
            Map<String, String> m = new HashMap<>();
            int q = p + 1; // eerste byte = verwijderd-vlag
            for (int f = 0; f < names.size(); f++) {
                m.put(names.get(f), new String(b, q, lens.get(f), StandardCharsets.UTF_8).trim());
                q += lens.get(f);
            }
            out.add(m);
        }
        return out;
    }

    static byte[] download() throws Exception {
        var http = java.net.http.HttpClient.newBuilder().followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
        var req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(URL)).header("User-Agent", "flitsapp-data/1 (wekelijks)").build();
        for (int i = 0; i < 4; i++) {
            try {
                var r = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 200) return r.body();
            } catch (IOException e) { /* opnieuw */ }
            Thread.sleep(20_000L * (i + 1));
        }
        fail("Download mislukt: " + URL);
        return null;
    }

    static void fail(String why) {
        System.err.println("FOUT: " + why);
        System.exit(1);
    }
}
