package club.havocsmp.maintenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * A small embedded HTTP server exposing maintenance state as JSON.
 *
 * Endpoints (all require an auth token):
 *
 *   GET  /ping         -> liveness check
 *   GET  /status       -> full server + maintenance state
 *   POST /maintenance  -> {"enabled": true, "reason": "...", "actor": "..."}
 *   GET  /whitelist    -> current bypass whitelist
 *   POST /whitelist    -> {"action": "add"|"remove", "player": "Name"}
 *
 * Auth: send either
 *   Authorization: Bearer &lt;token&gt;
 *   X-Api-Key: &lt;token&gt;
 *
 * Everything here runs off the main server thread, so handlers only ever read
 * {@link StatusSnapshot} / volatile state and hop back to the main thread for
 * anything that touches the Bukkit API.
 */
final class ApiServer {

    private static final int MAX_BODY_BYTES = 16 * 1024;

    /** Bumped whenever the JSON shape changes, so the bot can detect a mismatch. */
    private static final int API_VERSION = 1;

    private final HavocMaintenance plugin;
    private final String bind;
    private final int port;
    private final byte[] tokenBytes;

    private HttpServer server;
    private ExecutorService executor;

    ApiServer(HavocMaintenance plugin, String bind, int port, String token) {
        this.plugin = plugin;
        this.bind = bind;
        this.port = port;
        this.tokenBytes = token.getBytes(StandardCharsets.UTF_8);
    }

    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(bind, port), 16);
        executor = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "HavocMaintenance-API");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(executor);

        server.createContext("/ping", this::handlePing);
        server.createContext("/status", this::handleStatus);
        server.createContext("/maintenance", this::handleMaintenance);
        server.createContext("/whitelist", this::handleWhitelist);

        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    // ------------------------------------------------------------------
    // Handlers
    // ------------------------------------------------------------------

    private void handlePing(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        JsonObject body = new JsonObject();
        body.addProperty("ok", true);
        body.addProperty("plugin", plugin.getName());
        body.addProperty("api", API_VERSION);
        respond(exchange, 200, body);
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            error(exchange, 405, "Use GET for /status.");
            return;
        }
        respond(exchange, 200, buildStatus());
    }

    private void handleMaintenance(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }

        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if ("GET".equals(method)) {
            respond(exchange, 200, buildStatus());
            return;
        }

        if (!"POST".equals(method) && !"PUT".equals(method)) {
            error(exchange, 405, "Use GET or POST for /maintenance.");
            return;
        }

        JsonObject payload = readJson(exchange);
        if (payload == null) {
            error(exchange, 400, "Body must be a JSON object.");
            return;
        }

        final boolean enabled;
        if (payload.has("enabled") && payload.get("enabled").isJsonPrimitive()) {
            enabled = payload.get("enabled").getAsBoolean();
        } else if (payload.has("toggle")) {
            enabled = !plugin.state().isEnabled();
        } else {
            error(exchange, 400, "Missing 'enabled' (boolean) or 'toggle'.");
            return;
        }

        final String reason = payload.has("reason") && payload.get("reason").isJsonPrimitive()
                ? payload.get("reason").getAsString() : null;
        final String actor = payload.has("actor") && payload.get("actor").isJsonPrimitive()
                ? payload.get("actor").getAsString() : "Discord";

        // setMaintenance kicks players and fires events, so it has to run on
        // the main thread. Wait for it so the response reflects reality rather
        // than what we hoped would happen.
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                future.complete(plugin.setMaintenance(enabled, reason, actor));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            boolean changed = future.get(5, TimeUnit.SECONDS);
            JsonObject body = buildStatus();
            body.addProperty("changed", changed);
            respond(exchange, 200, body);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "API failed to apply a maintenance change", e);
            error(exchange, 500, "Server did not apply the change in time.");
        }
    }

    private void handleWhitelist(HttpExchange exchange) throws IOException {
        if (!authorize(exchange)) {
            return;
        }

        String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);

        if ("GET".equals(method)) {
            respond(exchange, 200, buildWhitelist(null));
            return;
        }

        if (!"POST".equals(method)) {
            error(exchange, 405, "Use GET or POST for /whitelist.");
            return;
        }

        JsonObject payload = readJson(exchange);
        if (payload == null || !payload.has("player") || !payload.has("action")) {
            error(exchange, 400, "Body must be {\"action\":\"add\"|\"remove\",\"player\":\"Name\"}.");
            return;
        }

        String action = payload.get("action").getAsString().toLowerCase(Locale.ROOT);
        String player = payload.get("player").getAsString().trim();

        if (player.isEmpty()) {
            error(exchange, 400, "'player' cannot be empty.");
            return;
        }

        boolean modified;
        switch (action) {
            case "add" -> modified = plugin.state().addToWhitelist(player);
            case "remove" -> modified = plugin.state().removeFromWhitelist(player);
            default -> {
                error(exchange, 400, "'action' must be 'add' or 'remove'.");
                return;
            }
        }

        respond(exchange, 200, buildWhitelist(modified));
    }

    // ------------------------------------------------------------------
    // Payload building
    // ------------------------------------------------------------------

    private JsonObject buildStatus() {
        MaintenanceState state = plugin.state();
        StatusSnapshot snap = plugin.snapshot();

        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        root.addProperty("maintenance", state.isEnabled());
        root.addProperty("reason", state.getReason());
        root.addProperty("since", state.getSince());

        JsonObject server = new JsonObject();
        server.addProperty("name", plugin.serverName());
        server.addProperty("version", snap.version());
        server.addProperty("online", snap.online());
        server.addProperty("max", snap.max());
        server.addProperty("tps", snap.tps());
        server.addProperty("mspt", snap.mspt());
        server.addProperty("uptime_seconds", plugin.uptimeSeconds());
        server.addProperty("snapshot_age_ms", System.currentTimeMillis() - snap.lastRefresh());

        JsonArray players = new JsonArray();
        snap.playerNames().forEach(players::add);
        server.add("players", players);

        root.add("server", server);
        return root;
    }

    private JsonObject buildWhitelist(Boolean modified) {
        JsonObject root = new JsonObject();
        root.addProperty("ok", true);
        if (modified != null) {
            root.addProperty("modified", modified);
        }
        JsonArray array = new JsonArray();
        plugin.state().whitelistSnapshot().forEach(array::add);
        root.add("whitelist", array);
        return root;
    }

    // ------------------------------------------------------------------
    // Plumbing
    // ------------------------------------------------------------------

    /** @return true if the request is authorised; otherwise responds 401 and returns false. */
    private boolean authorize(HttpExchange exchange) throws IOException {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String presented = null;

        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            presented = header.substring(7).trim();
        }
        if (presented == null) {
            presented = exchange.getRequestHeaders().getFirst("X-Api-Key");
        }

        if (presented == null
                || !MessageDigest.isEqual(presented.getBytes(StandardCharsets.UTF_8), tokenBytes)) {
            error(exchange, 401, "Invalid or missing API token.");
            return false;
        }
        return true;
    }

    private JsonObject readJson(HttpExchange exchange) {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] raw = in.readNBytes(MAX_BODY_BYTES);
            if (raw.length == 0) {
                return null;
            }
            var parsed = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
            return parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void error(HttpExchange exchange, int code, String message) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("ok", false);
        body.addProperty("error", message);
        respond(exchange, code, body);
    }

    private void respond(HttpExchange exchange, int code, JsonObject body) throws IOException {
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
