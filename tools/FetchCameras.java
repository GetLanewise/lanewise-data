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
 *  - of het aantal meer dan MAX_DROP lager is dan in het vorige bestand.
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

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Gebruik: java FetchCameras.java <uitvoer.json> [invoer.csv]");
            System.exit(2);
        }
        Path out = Path.of(args[0]);
        String csv = args.length >= 2 ? Files.readString(Path.of(args[1])) : download();

        List<Camera> cameras = parse(csv);
        System.out.println("Ontvangen: " + cameras.size() + " flitsers");

        if (cameras.size() < MIN_CAMERAS) {
            fail("Te weinig flitsers (" + cameras.size() + " < " + MIN_CAMERAS + "), oud bestand blijft staan");
        }
        int previous = previousCount(out);
        if (previous > 0 && cameras.size() < previous * (1 - MAX_DROP)) {
            fail("Aantal daalde van " + previous + " naar " + cameras.size() + " (meer dan "
                    + Math.round(MAX_DROP * 100) + "%), oud bestand blijft staan");
        }

        cameras.sort((a, b) -> Long.compare(a.id(), b.id()));
        String camerasJson = camerasJson(cameras);
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
    static String camerasJson(List<Camera> cameras) {
        StringBuilder sb = new StringBuilder("[\n");
        for (int i = 0; i < cameras.size(); i++) {
            Camera c = cameras.get(i);
            sb.append("{\"id\":\"osm:node/").append(c.id()).append('"')
              .append(",\"lat\":").append(c.lat())
              .append(",\"lon\":").append(c.lon())
              .append(",\"type\":\"SPEED\"");
            if (c.maxSpeed() != null) sb.append(",\"maxSpeed\":").append(c.maxSpeed());
            if (c.direction() != null) sb.append(",\"direction\":").append(c.direction());
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

    static void fail(String message) {
        System.err.println("FOUT: " + message);
        System.exit(1);
    }
}
