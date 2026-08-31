package club.havocsmp.maintenance;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * An immutable-ish view of the server, refreshed on the main thread on a timer.
 *
 * The Bukkit API is not thread safe, so the HTTP handlers must never call
 * things like getOnlinePlayers() themselves. They read this instead. Every
 * field is volatile and the player list is replaced wholesale rather than
 * mutated, so readers always see a consistent list.
 */
public final class StatusSnapshot {

    private final HavocMaintenance plugin;

    private volatile List<String> playerNames = Collections.emptyList();
    private volatile int online;
    private volatile int max;
    private volatile double tps1m;
    private volatile double mspt;
    private volatile String version = "unknown";
    private volatile long lastRefresh;

    StatusSnapshot(HavocMaintenance plugin) {
        this.plugin = plugin;
        this.version = plugin.getServer().getBukkitVersion();
    }

    /** Must run on the main server thread. */
    void refresh() {
        List<String> names = new ArrayList<>();
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            names.add(player.getName());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);

        this.playerNames = Collections.unmodifiableList(names);
        this.online = names.size();
        this.max = plugin.getServer().getMaxPlayers();

        double[] tps = plugin.getServer().getTPS();
        this.tps1m = tps.length > 0 ? Math.min(20.0D, tps[0]) : 20.0D;
        this.mspt = plugin.getServer().getAverageTickTime();

        this.lastRefresh = System.currentTimeMillis();
    }

    public List<String> playerNames() {
        return playerNames;
    }

    public int online() {
        return online;
    }

    public int max() {
        return max;
    }

    public double tps() {
        return Math.round(tps1m * 100.0D) / 100.0D;
    }

    public double mspt() {
        return Math.round(mspt * 100.0D) / 100.0D;
    }

    public String version() {
        return version;
    }

    public long lastRefresh() {
        return lastRefresh;
    }
}
