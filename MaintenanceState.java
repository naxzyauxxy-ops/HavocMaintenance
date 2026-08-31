package club.havocsmp.maintenance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Holds the live maintenance state and the bypass whitelist, and persists both
 * back into config.yml so a restart doesn't silently drop the server back
 * online mid-maintenance.
 *
 * Fields read by the HTTP API are volatile because those reads happen on the
 * HTTP server's threads, not the main server thread.
 */
public final class MaintenanceState {

    private final HavocMaintenance plugin;

    private volatile boolean enabled;
    private volatile String reason;
    private volatile long since;

    /** Lowercased names. Guarded by the object monitor. */
    private final Set<String> whitelist = new LinkedHashSet<>();

    MaintenanceState(HavocMaintenance plugin) {
        this.plugin = plugin;
    }

    void load() {
        this.enabled = plugin.getConfig().getBoolean("state.enabled", false);
        this.reason = orDefault(plugin.getConfig().getString("state.reason"), "Scheduled maintenance");
        this.since = plugin.getConfig().getLong("state.since", 0L);

        synchronized (whitelist) {
            whitelist.clear();
            for (String name : plugin.getConfig().getStringList("whitelist")) {
                if (name != null && !name.isBlank()) {
                    whitelist.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    void save() {
        plugin.getConfig().set("state.enabled", enabled);
        plugin.getConfig().set("state.reason", reason);
        plugin.getConfig().set("state.since", since);
        synchronized (whitelist) {
            plugin.getConfig().set("whitelist", new ArrayList<>(whitelist));
        }
        plugin.saveConfig();
    }

    /** @return true if the state actually changed. */
    boolean set(boolean newEnabled, String newReason) {
        boolean changed = this.enabled != newEnabled;

        if (newReason != null && !newReason.isBlank()) {
            this.reason = newReason.trim();
        }
        if (changed) {
            this.enabled = newEnabled;
            this.since = System.currentTimeMillis();
        }
        save();
        return changed;
    }

    void setReason(String newReason) {
        if (newReason != null && !newReason.isBlank()) {
            this.reason = newReason.trim();
            save();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String getReason() {
        return reason == null ? "" : reason;
    }

    /** Epoch millis of the last state flip, or 0 if it has never flipped. */
    public long getSince() {
        return since;
    }

    public boolean isWhitelisted(String playerName) {
        if (playerName == null) {
            return false;
        }
        synchronized (whitelist) {
            return whitelist.contains(playerName.toLowerCase(Locale.ROOT));
        }
    }

    public List<String> whitelistSnapshot() {
        synchronized (whitelist) {
            return new ArrayList<>(whitelist);
        }
    }

    /** @return true if the name was added, false if it was already present. */
    public boolean addToWhitelist(String playerName) {
        boolean added;
        synchronized (whitelist) {
            added = whitelist.add(playerName.toLowerCase(Locale.ROOT));
        }
        if (added) {
            save();
        }
        return added;
    }

    /** @return true if the name was removed, false if it wasn't there. */
    public boolean removeFromWhitelist(String playerName) {
        boolean removed;
        synchronized (whitelist) {
            removed = whitelist.remove(playerName.toLowerCase(Locale.ROOT));
        }
        if (removed) {
            save();
        }
        return removed;
    }

    private static String orDefault(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }
}
