package club.havocsmp.maintenance;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;

import java.util.UUID;

/**
 * Blocks logins during maintenance and rewrites the server list MOTD.
 */
public final class ConnectionListener implements Listener {

    private final HavocMaintenance plugin;

    ConnectionListener(HavocMaintenance plugin) {
        this.plugin = plugin;
    }

    /**
     * A player gets in during maintenance if they hold the bypass permission,
     * are op, or are on the maintenance whitelist.
     *
     * @param hasBypassPermission pre-resolved, because permission lookups at
     *                            login time are awkward to do from a static context
     */
    static boolean isExempt(HavocMaintenance plugin, UUID uuid, String name, boolean hasBypassPermission) {
        if (hasBypassPermission) {
            return true;
        }
        return plugin.state().isWhitelisted(name);
    }

    // Runs at HIGHEST rather than MONITOR so that anything cancelling the login
    // for a better reason (bans, IP filters, anti-alt) still wins, but normal
    // plugins can't quietly re-allow a login we just blocked.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLogin(PlayerLoginEvent event) {
        if (!plugin.state().isEnabled()) {
            return;
        }

        // Already rejected by something else - leave that reason intact.
        if (event.getResult() != PlayerLoginEvent.Result.ALLOWED) {
            return;
        }

        Player player = event.getPlayer();
        boolean bypass = player.hasPermission("havocmaintenance.bypass") || player.isOp();

        if (isExempt(plugin, player.getUniqueId(), player.getName(), bypass)) {
            String notice = plugin.getConfig().getString("messages.bypass-join", "");
            if (notice != null && !notice.isBlank()) {
                // Deferred a tick: sending during login is unreliable because the
                // player isn't fully in the world yet.
                Component message = plugin.mm(notice);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    Player online = plugin.getServer().getPlayer(player.getUniqueId());
                    if (online != null && online.isOnline()) {
                        online.sendMessage(message);
                    }
                }, 20L);
            }
            return;
        }

        Component kick = plugin.mm(
                plugin.getConfig().getString("messages.kick", "<red>Server is under maintenance.</red>"),
                Placeholder.unparsed("reason", plugin.state().getReason()),
                Placeholder.unparsed("server", plugin.serverName())
        );

        event.disallow(PlayerLoginEvent.Result.KICK_OTHER, kick);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPing(PaperServerListPingEvent event) {
        if (!plugin.getConfig().getBoolean("motd.enabled", true)) {
            return;
        }

        if (plugin.state().isEnabled()) {
            String motd = plugin.getConfig().getString("messages.motd-maintenance", "");
            if (motd != null && !motd.isBlank()) {
                event.motd(plugin.mm(
                        motd,
                        Placeholder.unparsed("reason", plugin.state().getReason()),
                        Placeholder.unparsed("server", plugin.serverName())
                ));
            }

            if (plugin.getConfig().getBoolean("motd.hide-players", false)) {
                event.setHidePlayers(true);
            } else {
                int online = plugin.getConfig().getInt("motd.online-count", 0);
                int max = plugin.getConfig().getInt("motd.max-count", -1);
                if (online >= 0) {
                    event.setNumPlayers(online);
                }
                if (max >= 0) {
                    event.setMaxPlayers(max);
                }
                // Blank the hover-over player sample so it doesn't contradict
                // the counts we just set.
                event.getListedPlayers().clear();
            }

            if (plugin.getConfig().getBoolean("motd.fake-version.enabled", false)) {
                event.setVersion(plugin.getConfig().getString("motd.fake-version.text", "Maintenance"));
                // Forcing a protocol mismatch is what makes the client actually
                // render that version string instead of ignoring it.
                event.setProtocolVersion(-1);
            }
        } else {
            String motd = plugin.getConfig().getString("messages.motd-normal", "");
            if (motd != null && !motd.isBlank()) {
                event.motd(plugin.mm(motd, Placeholder.unparsed("server", plugin.serverName())));
            }
        }
    }
}
