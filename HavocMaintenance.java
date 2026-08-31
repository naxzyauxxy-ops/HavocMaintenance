package club.havocsmp.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Level;

/**
 * HavocMaintenance
 *
 * Maintenance mode for Paper / Purpur 1.21.x.
 *
 * The interesting part for the Discord side is {@link ApiServer}: a tiny
 * embedded HTTP server that exposes the live server state as JSON and accepts
 * writes to toggle maintenance. The bot polls GET /status and calls
 * POST /maintenance; nothing else is needed to keep the two in sync.
 */
public final class HavocMaintenance extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private MaintenanceState state;
    private StatusSnapshot snapshot;
    private ApiServer apiServer;
    private WebhookNotifier webhook;

    private long enabledAtMillis;

    @Override
    public void onEnable() {
        this.enabledAtMillis = System.currentTimeMillis();

        saveDefaultConfig();

        this.state = new MaintenanceState(this);
        this.state.load();

        this.snapshot = new StatusSnapshot(this);
        this.webhook = new WebhookNotifier(this);

        getServer().getPluginManager().registerEvents(new ConnectionListener(this), this);

        MaintenanceCommand command = new MaintenanceCommand(this);
        PluginCommand pluginCommand = getCommand("maintenance");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        } else {
            getLogger().severe("Command 'maintenance' is missing from plugin.yml - the jar is built wrong.");
        }

        // The API is served from background threads, so it must never touch the
        // Bukkit API directly. Instead a snapshot is refreshed on the main
        // thread on a timer, and the HTTP handlers only read that.
        long refreshTicks = Math.max(5L, getConfig().getLong("api.refresh-ticks", 20L));
        getServer().getScheduler().runTaskTimer(this, snapshot::refresh, 1L, refreshTicks);

        startApi();

        getLogger().info("Enabled. Maintenance is currently "
                + (state.isEnabled() ? "ON" : "OFF") + ".");
    }

    @Override
    public void onDisable() {
        stopApi();
        if (state != null) {
            state.save();
        }
        getLogger().info("Disabled.");
    }

    // ------------------------------------------------------------------
    // API lifecycle
    // ------------------------------------------------------------------

    private void startApi() {
        if (!getConfig().getBoolean("api.enabled", true)) {
            getLogger().info("HTTP API is disabled in config.yml.");
            return;
        }

        String token = getConfig().getString("api.token", "");
        if (token == null || token.isBlank() || token.equals("CHANGE_ME_TO_A_LONG_RANDOM_STRING")) {
            getLogger().severe("=".repeat(70));
            getLogger().severe("HTTP API NOT STARTED: api.token in config.yml is still the default.");
            getLogger().severe("Set it to a long random string, then run /maintenance reload.");
            getLogger().severe("=".repeat(70));
            return;
        }

        String bind = getConfig().getString("api.bind", "0.0.0.0");
        int port = getConfig().getInt("api.port", 8123);

        try {
            apiServer = new ApiServer(this, bind, port, token);
            apiServer.start();
            getLogger().info("HTTP API listening on " + bind + ":" + port);
        } catch (Exception e) {
            apiServer = null;
            getLogger().log(Level.SEVERE,
                    "Failed to start the HTTP API on " + bind + ":" + port
                            + ". Is the port already in use, or not allocated to this server?", e);
        }
    }

    private void stopApi() {
        if (apiServer != null) {
            apiServer.stop();
            apiServer = null;
        }
    }

    /** Restarts the API and reloads config-backed state. Safe to call repeatedly. */
    public void reloadEverything() {
        stopApi();
        reloadConfig();
        state.load();
        startApi();
    }

    // ------------------------------------------------------------------
    // State changes
    // ------------------------------------------------------------------

    /**
     * Flips maintenance mode. Must be called from the main server thread -
     * {@link ApiServer} hops threads before calling this.
     *
     * @param enabled new state
     * @param reason  reason to display, or null to keep the existing one
     * @param actor   who did it, for logs / broadcasts / the webhook
     * @return true if the state actually changed
     */
    public boolean setMaintenance(boolean enabled, String reason, String actor) {
        boolean changed = state.set(enabled, reason);

        if (enabled) {
            kickNonExemptPlayers();
        }

        if (changed) {
            webhook.notifyStateChange(enabled, state.getReason(), actor);
        }

        snapshot.refresh();
        return changed;
    }

    private void kickNonExemptPlayers() {
        Component message = mm(
                getConfig().getString("messages.kick-on-enable", "<red>Server is under maintenance.</red>"),
                Placeholder.unparsed("reason", state.getReason()),
                Placeholder.unparsed("server", serverName())
        );

        getServer().getOnlinePlayers().stream()
                .filter(player -> !ConnectionListener.isExempt(this, player.getUniqueId(), player.getName(),
                        player.hasPermission("havocmaintenance.bypass") || player.isOp()))
                .forEach(player -> player.kick(message));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    public Component mm(String input, TagResolver... resolvers) {
        if (input == null) {
            return Component.empty();
        }
        return MM.deserialize(input, resolvers);
    }

    public String serverName() {
        return getConfig().getString("server-name", "Server");
    }

    public MaintenanceState state() {
        return state;
    }

    public StatusSnapshot snapshot() {
        return snapshot;
    }

    public WebhookNotifier webhook() {
        return webhook;
    }

    public long uptimeSeconds() {
        return (System.currentTimeMillis() - enabledAtMillis) / 1000L;
    }
}
