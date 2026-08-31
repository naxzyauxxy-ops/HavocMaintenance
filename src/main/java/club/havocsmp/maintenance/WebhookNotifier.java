package club.havocsmp.maintenance;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.logging.Level;

/**
 * Fires a Discord webhook the instant maintenance is toggled.
 *
 * This is deliberately independent of the bot: even if the bot is offline or
 * mid-restart, the announcement still lands in the channel. The bot's polling
 * loop is what keeps a persistent status embed accurate over time; this is the
 * "it just happened" notification.
 */
public final class WebhookNotifier {

    private final HavocMaintenance plugin;
    private final HttpClient client;

    WebhookNotifier(HavocMaintenance plugin) {
        this.plugin = plugin;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    void notifyStateChange(boolean enabled, String reason, String actor) {
        String url = plugin.getConfig().getString("webhook.url", "");
        if (url == null || url.isBlank() || !url.startsWith("https://")) {
            return;
        }

        JsonObject embed = new JsonObject();
        embed.addProperty("title", enabled
                ? "\uD83D\uDD27 Maintenance mode enabled"
                : "\u2705 Server is back online");
        embed.addProperty("description", enabled
                ? "**" + plugin.serverName() + "** is now closed to players."
                : "**" + plugin.serverName() + "** is open again. See you in there.");
        embed.addProperty("color", plugin.getConfig().getInt(
                enabled ? "webhook.color-maintenance" : "webhook.color-online",
                enabled ? 15548997 : 5763719));
        embed.addProperty("timestamp", Instant.now().toString());

        JsonArray fields = new JsonArray();
        if (enabled && reason != null && !reason.isBlank()) {
            fields.add(field("Reason", reason, false));
        }
        fields.add(field("Changed by", actor == null ? "Unknown" : actor, true));
        embed.add("fields", fields);

        JsonObject footer = new JsonObject();
        footer.addProperty("text", plugin.serverName());
        embed.add("footer", footer);

        JsonArray embeds = new JsonArray();
        embeds.add(embed);

        JsonObject payload = new JsonObject();
        String username = plugin.getConfig().getString("webhook.username", "");
        if (username != null && !username.isBlank()) {
            payload.addProperty("username", username);
        }
        payload.add("embeds", embeds);

        send(url, payload.toString());
    }

    private void send(String url, String json) {
        // Never block the main thread on a network call.
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> response =
                        client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() >= 300) {
                    plugin.getLogger().warning("Discord webhook returned HTTP "
                            + response.statusCode() + ": " + response.body());
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to deliver the Discord webhook", e);
            }
        });
    }

    private static JsonObject field(String name, String value, boolean inline) {
        JsonObject object = new JsonObject();
        object.addProperty("name", name);
        object.addProperty("value", value);
        object.addProperty("inline", inline);
        return object;
    }
}
