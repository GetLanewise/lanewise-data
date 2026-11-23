import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Haalt de actuele weerswaarschuwingen van het KNMI (dataset KNMI-public-weather-warnings 1.0,
 * CC BY 4.0) en schrijft alleen de waarschuwingen die nu of straks gelden naar een klein landelijk
 * bestand voor de Flitsapp. Bron vermelden is verplicht: "Bron: KNMI" (zie attribution in het bestand).
 *
 * Draaien (Java 17+, geen extra libraries):
 *   java tools/FetchWeather.java knmi-waarschuwingen.json
 * Sleutel: omgevingsvariabele KNMI_API_KEY (GitHub-secret). Zonder sleutel wordt de openbare
 * anonieme sleutel van het KNMI gebruikt (gedeeld met iedereen, dus soms "Rate Limit Exceeded").
 *
 * Veiligheid: lukt het ophalen niet, dan blijft het oude bestand staan (de app toont dan hoe oud
 * de informatie is). Testen zonder internet: geef een opgeslagen KNMI-JSON mee als tweede argument.
 */
public class FetchWeather {

    static final String BASE = "https://api.dataplatform.knmi.nl/open-data/v1/datasets/KNMI-public-weather-warnings/versions/1.0/files";
    /** Openbare anonieme sleutel van het KNMI (developer.dataplatform.knmi.nl/open-data-api), geldig tot 1 augustus 2027. */
    static final String ANONYMOUS_KEY = "eyJvcmciOiI1ZTU1NGUxOTI3NGE5NjAwMDEyYTNlYjEiLCJpZCI6IjUzYTg1ZDBhMmQ5YzRkYzJiYWNlNzQ4NTQ2Zjk4ODExIiwiaCI6Im11cm11cjEyOCJ9";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Gebruik: java FetchWeather.java <uitvoer.json> [invoer.json]"); System.exit(2); }
        Path out = Path.of(args[0]);
        String key = System.getenv().getOrDefault("KNMI_API_KEY", "").isBlank() ? ANONYMOUS_KEY : System.getenv("KNMI_API_KEY");
        String raw = args.length >= 2 ? Files.readString(Path.of(args[1])) : download(key);
        Object root = new Json(raw).value();
        long now = System.currentTimeMillis();
        String warnings = convert(root, now);
        if (Files.exists(out) && Files.readString(out).contains(warnings)) {
            System.out.println("Geen wijzigingen.");
            return;
        }
        String json = "{\n\"format\":1,\n"
                + "\"source\":\"knmi\",\n"
                + "\"attribution\":\"Bron: KNMI (bewerkt: alleen huidige en komende waarschuwingen)\",\n"
                + "\"license\":\"CC-BY-4.0\",\n"
                + "\"generatedAt\":" + now + ",\n"
                + warnings + "\n}\n";
        Path tmp = out.resolveSibling(out.getFileName() + ".tmp");
        Files.writeString(tmp, json, StandardCharsets.UTF_8);
        Files.move(tmp, out, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        System.out.println("Geschreven: " + out);
    }

    static String download(String key) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        for (int attempt = 1; attempt <= 4; attempt++) {
            try {
                String list = get(http, BASE + "?maxKeys=10&sorting=desc&orderBy=created", key);
                Map<?, ?> l = (Map<?, ?>) new Json(list).value();
                String newest = null;
                for (Object f : (List<?>) l.get("files")) {
                    String name = (String) ((Map<?, ?>) f).get("filename");
                    if (name.endsWith(".json")) { newest = name; break; }
                }
                if (newest == null) fail("Geen JSON-bestand gevonden in de KNMI-lijst.");
                Map<?, ?> u = (Map<?, ?>) new Json(get(http, BASE + "/" + newest + "/url", key)).value();
                System.out.println("KNMI-bestand: " + newest);
                return get(http, (String) u.get("temporaryDownloadUrl"), null);
            } catch (Exception e) {
                System.err.println("KNMI poging " + attempt + ": " + e.getMessage());
                Thread.sleep(attempt * 20_000L);
            }
        }
        fail("KNMI niet bereikbaar; oud bestand blijft staan.");
        return null;
    }

    static String get(HttpClient http, String url, String key) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(60))
                .header("User-Agent", "flitsapp-data/1 (weerswaarschuwingen)");
        if (key != null) b.header("Authorization", key);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IllegalStateException("HTTP " + r.statusCode() + " " + r.body().substring(0, Math.min(120, r.body().length())));
        return r.body();
    }

    /** Alleen waarschuwingen die niet verlopen zijn; per deel (kleur + gebied) één regel. */
    static String convert(Object root, long now) {
        Map<?, ?> m = (Map<?, ?>) root;
        StringBuilder sb = new StringBuilder("\"warnings\":[\n");
        int n = 0;
        for (Object o : (List<?>) m.get("warnings")) {
            Map<?, ?> w = (Map<?, ?>) o;
            String status = String.valueOf(w.get("status"));
            if (status.equals("expired") || status.equals("cancelled") || status.equals("canceled")) continue;
            long expires = time(w.get("expires"));
            if (expires > 0 && expires < now) continue;
            Map<?, ?> nl = (Map<?, ?>) ((Map<?, ?>) w.get("texts")).get("nl");
            for (Object po : (List<?>) w.get("parts")) {
                Map<?, ?> p = (Map<?, ?>) po;
                Map<?, ?> area = (Map<?, ?>) p.get("area");
                if (area == null) continue;
                if (n++ > 0) sb.append(",\n");
                sb.append("{\"id\":").append(str(String.valueOf(p.get("part_uuid"))))
                  .append(",\"phenomenon\":").append(str(String.valueOf(w.get("phenomenon"))))
                  .append(",\"color\":").append(str(String.valueOf(p.get("color"))))
                  .append(",\"type\":").append(str(String.valueOf(w.get("warning_type"))))
                  .append(",\"onset\":").append(time(w.get("onset")))
                  .append(",\"expires\":").append(expires)
                  .append(",\"headline\":").append(str(nl == null ? "" : String.valueOf(nl.get("headline"))))
                  .append(",\"description\":").append(str(nl == null ? "" : String.valueOf(nl.get("description"))))
                  .append(",\"area\":").append(Json.write(area))
                  .append('}');
            }
        }
        System.out.println("Actuele waarschuwingen (delen): " + n);
        return sb.append("\n]").toString();
    }

    static long time(Object o) {
        if (o == null) return 0;
        try { return Instant.parse(o.toString()).toEpochMilli(); } catch (Exception e) { return 0; }
    }

    static String str(String s) { return Json.write(s); }

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
