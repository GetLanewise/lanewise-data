import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.GZIPOutputStream;

/**
 * Vrachtwagenheffing: de wegvakken van het heffingsnetwerk (Rijkswaterstaat, Wegkenmerkendatabase,
 * open data CC0). Uitvoer: compact JSON (gzip) met per wegvak de lijn in [lat,lon] (5 decimalen).
 *
 * Gebruik: java tools/FetchTollNetwork.java uit.json.gz [invoer.geojson]
 * Zonder invoer wordt alleen het heffingsnetwerk (hefnetwerk = 'Ja') via WFS opgehaald.
 */
public class FetchTollNetwork {
    static final String URL = "https://geo.rijkswaterstaat.nl/services/ogc/gdr/wkd_wegkenmerkendatabase/ows?SERVICE=WFS&VERSION=2.0.0"
            + "&REQUEST=GetFeature&OUTPUTFORMAT=application%2Fjson&TYPENAMES=vrachtwagentolheffingsnetwerk&SRSNAME=EPSG:4326"
            + "&CQL_FILTER=hefnetwerk%3D%27Ja%27";

    public static void main(String[] a) throws Exception {
        String json = a.length > 1 ? Files.readString(Path.of(a[1])) : download();
        Pattern feat = Pattern.compile("\"coordinates\":(\\[\\[.*?\\]\\]).*?\"hefnetwerk\":\"([A-Za-z]+)\"");
        Matcher m = feat.matcher(json);
        StringBuilder sb = new StringBuilder();
        int n = 0, pts = 0;
        while (m.find()) {
            if (!m.group(2).equals("Ja")) continue;
            List<double[]> line = new ArrayList<>();
            Matcher c = Pattern.compile("\\[([0-9.]+),([0-9.]+)\\]").matcher(m.group(1));
            while (c.find()) line.add(new double[]{Double.parseDouble(c.group(2)), Double.parseDouble(c.group(1))});
            if (line.size() < 2) continue;
            if (n++ > 0) sb.append(",\n");
            sb.append('[');
            for (int i = 0; i < line.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(String.format(Locale.ROOT, "[%.5f,%.5f]", line.get(i)[0], line.get(i)[1]));
            }
            sb.append(']');
            pts += line.size();
        }
        System.out.println("Heffingsnetwerk: " + n + " wegvakken, " + pts + " punten");
        if (n < 5000) { System.err.println("FOUT: te weinig wegvakken (" + n + "), bestand blijft staan"); System.exit(1); }
        String out = "{\"format\":1,\"source\":\"rws-wkd\",\"license\":\"CC0-1.0\",\"attribution\":\"Rijkswaterstaat (Wegkenmerkendatabase)\",\"generatedAt\":"
                + System.currentTimeMillis() + ",\"count\":" + n + ",\"lines\":[\n" + sb + "\n]}";
        try (var gz = new GZIPOutputStream(Files.newOutputStream(Path.of(a[0])))) { gz.write(out.getBytes(StandardCharsets.UTF_8)); }
        System.out.println("Geschreven: " + a[0] + " (" + Files.size(Path.of(a[0])) / 1024 + " kB)");
        String summary = System.getenv("GITHUB_STEP_SUMMARY");
        if (summary != null) Files.writeString(Path.of(summary), "### Heffingsnetwerk\n\n" + n + " wegvakken\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static String download() throws Exception {
        var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
        var req = HttpRequest.newBuilder(URI.create(URL)).header("User-Agent", "lanewise-data/1 (wekelijks)").build();
        for (int i = 0; i < 4; i++) {
            try {
                var r = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) return r.body();
            } catch (IOException e) { /* opnieuw */ }
            Thread.sleep(30_000L * (i + 1));
        }
        System.err.println("FOUT: download mislukt");
        System.exit(1);
        return null;
    }
}
