package clubtrack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.Desktop;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Eclipse-ready Java server for the ClubTrack asset handover system. */
public class ClubTrackServer {
    private static final List<Asset> assets = new ArrayList<>();
    private static final List<Handover> handovers = new ArrayList<>();
    private static final Path PUBLIC = Path.of("public").toAbsolutePath().normalize();

    public static void main(String[] args) throws IOException {
        seed();
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 3000;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", ClubTrackServer::handle);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("ClubTrack is running at http://localhost:" + port);
        // Open the dashboard automatically when launched from Eclipse.
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(new URI("http://localhost:" + port));
            }
        } catch (Exception ignored) {
            System.out.println("Open http://localhost:" + port + " in a browser.");
        }
    }

    private static synchronized void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        try {
            if (path.equals("/api/dashboard") && method.equals("GET")) { dashboard(exchange); return; }
            if (path.equals("/api/assets") && method.equals("GET")) { json(exchange, 200, assetsJson()); return; }
            if (path.equals("/api/assets") && method.equals("POST")) { addAsset(exchange); return; }
            if (path.equals("/api/handovers") && method.equals("GET")) { json(exchange, 200, handoversJson()); return; }
            if (path.equals("/api/handovers") && method.equals("POST")) { addHandover(exchange); return; }
            if (path.matches("/api/handovers/\\d+/approve") && method.equals("PATCH")) { approve(exchange, path); return; }
            serveStatic(exchange, path);
        } catch (Exception error) {
            json(exchange, 500, "{\"error\":\"" + escape(error.getMessage()) + "\"}");
        }
    }

    private static void dashboard(HttpExchange e) throws IOException {
        long available = assets.stream().filter(a -> a.status.equals("Available")).count();
        long pending = handovers.stream().filter(h -> h.status.equals("Pending approval")).count();
        String recent = handoversJson();
        json(e, 200, "{\"total\":" + assets.size() + ",\"available\":" + available + ",\"assigned\":" + (assets.size() - available) + ",\"pending\":" + pending + ",\"recent\":" + recent + "}");
    }

    private static void addAsset(HttpExchange e) throws IOException {
        Map<String, String> fields = fields(e);
        String name = fields.get("name"), code = fields.get("code");
        if (blank(name) || blank(code)) { error(e, "Asset name and ID are required."); return; }
        if (assets.stream().anyMatch(a -> a.code.equalsIgnoreCase(code))) { error(e, "That asset ID already exists."); return; }
        Asset asset = new Asset(System.currentTimeMillis(), name, value(fields,"category","Other"), code, value(fields,"condition","Good"), value(fields,"location","Not specified"), "Unassigned", "Available");
        assets.add(asset); json(e, 201, asset.json());
    }

    private static void addHandover(HttpExchange e) throws IOException {
        Map<String, String> fields = fields(e);
        Asset asset = findAsset(number(fields.get("assetId")));
        if (asset == null || blank(fields.get("from")) || blank(fields.get("to")) || blank(fields.get("dueDate"))) { error(e, "Complete all required handover fields."); return; }
        if (!asset.status.equals("Available") && !asset.holder.equals(fields.get("from"))) { error(e, "The chosen asset is not held by the stated outgoing member."); return; }
        Handover handover = new Handover(System.currentTimeMillis(), asset.id, fields.get("from"), fields.get("to"), value(fields,"club","General"), fields.get("dueDate"), value(fields,"notes",""), value(fields,"condition",asset.condition), "Pending approval", LocalDate.now().toString());
        handovers.add(handover); json(e, 201, handover.json());
    }

    private static void approve(HttpExchange e, String path) throws IOException {
        long id = number(path.replaceAll(".*/(\\d+)/approve", "$1"));
        Handover handover = handovers.stream().filter(h -> h.id == id && h.status.equals("Pending approval")).findFirst().orElse(null);
        if (handover == null) { error(e, "Pending handover not found."); return; }
        Asset asset = findAsset(handover.assetId);
        handover.status = "Completed"; asset.holder = handover.to; asset.status = "Assigned"; asset.condition = handover.condition;
        json(e, 200, handover.json());
    }

    private static Map<String, String> fields(HttpExchange e) throws IOException {
        String input = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> result = new ConcurrentHashMap<>();
        Matcher m = Pattern.compile("\\\"([^\\\"]+)\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(input);
        while (m.find()) result.put(m.group(1), unescape(m.group(2)));
        return result;
    }
    private static void serveStatic(HttpExchange e, String requested) throws IOException {
        String relative = requested.equals("/") ? "index.html" : requested.substring(1);
        Path file = PUBLIC.resolve(relative).normalize();
        if (!file.startsWith(PUBLIC) || !Files.exists(file)) { e.sendResponseHeaders(404, -1); return; }
        String type = relative.endsWith(".css") ? "text/css" : relative.endsWith(".js") ? "application/javascript" : "text/html";
        byte[] bytes = Files.readAllBytes(file); e.getResponseHeaders().set("Content-Type", type + "; charset=utf-8"); e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
    private static void json(HttpExchange e, int status, String body) throws IOException { byte[] bytes = body.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json"); e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close(); }
    private static void error(HttpExchange e, String message) throws IOException { json(e, 400, "{\"error\":\"" + escape(message) + "\"}"); }
    private static Asset findAsset(long id) { return assets.stream().filter(a -> a.id == id).findFirst().orElse(null); }
    private static String assetsJson() { return "[" + String.join(",", assets.stream().map(Asset::json).toList()) + "]"; }
    private static String handoversJson() { return "[" + String.join(",", handovers.stream().map(Handover::json).toList()) + "]"; }
    private static long number(String s) { try { return Long.parseLong(s); } catch (Exception ex) { return -1; } }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String value(Map<String,String> fields, String key, String fallback) { return blank(fields.get(key)) ? fallback : fields.get(key); }
    private static String escape(String s) { return (s == null ? "" : s).replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    private static String unescape(String s) { return s.replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\"); }
    private static void seed() {
        assets.add(new Asset(1,"Canon EOS 200D Camera","Media","MDC-014","Good","Media Room","Aarav Mehta","Assigned"));
        assets.add(new Asset(2,"JBL PartyBox Speaker","Events","EVT-008","Excellent","Store Room A","Unassigned","Available"));
        assets.add(new Asset(3,"Robotics Toolkit","Technical","TEC-021","Fair","Innovation Lab","Ishita Rao","Assigned"));
        assets.add(new Asset(4,"Club Banner Set","Branding","BRD-003","Good","Store Room B","Unassigned","Available"));
        handovers.add(new Handover(1,1,"Aarav Mehta","Diya Nair","Photography Club","2026-09-25","Return with charged battery and SD card.","Good","Pending approval","2026-09-21"));
    }
    static class Asset { long id; String name, category, code, condition, location, holder, status; Asset(long id,String name,String category,String code,String condition,String location,String holder,String status){this.id=id;this.name=name;this.category=category;this.code=code;this.condition=condition;this.location=location;this.holder=holder;this.status=status;} String json(){return "{\"id\":"+id+",\"name\":\""+escape(name)+"\",\"category\":\""+escape(category)+"\",\"code\":\""+escape(code)+"\",\"condition\":\""+escape(condition)+"\",\"location\":\""+escape(location)+"\",\"holder\":\""+escape(holder)+"\",\"status\":\""+escape(status)+"\"}";} }
    static class Handover { long id,assetId; String from,to,club,dueDate,notes,condition,status,createdAt; Handover(long id,long assetId,String from,String to,String club,String dueDate,String notes,String condition,String status,String createdAt){this.id=id;this.assetId=assetId;this.from=from;this.to=to;this.club=club;this.dueDate=dueDate;this.notes=notes;this.condition=condition;this.status=status;this.createdAt=createdAt;} String json(){return "{\"id\":"+id+",\"assetId\":"+assetId+",\"from\":\""+escape(from)+"\",\"to\":\""+escape(to)+"\",\"club\":\""+escape(club)+"\",\"dueDate\":\""+escape(dueDate)+"\",\"notes\":\""+escape(notes)+"\",\"condition\":\""+escape(condition)+"\",\"status\":\""+escape(status)+"\",\"createdAt\":\""+escape(createdAt)+"\"}";} }
}
