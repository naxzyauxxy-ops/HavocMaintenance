package club.havocsmp.maintenance;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class MaintenanceCommand implements CommandExecutor, TabCompleter {

    private static final List<String> ROOT_ARGS =
            List.of("on", "off", "toggle", "status", "reason", "whitelist", "reload");
    private static final List<String> WHITELIST_ARGS = List.of("add", "remove", "list");

    private final HavocMaintenance plugin;

    MaintenanceCommand(HavocMaintenance plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {

        if (!sender.hasPermission("havocmaintenance.admin")) {
            sender.sendMessage(plugin.mm("<red>You don't have permission to do that.</red>"));
            return true;
        }

        if (args.length == 0) {
            sendStatus(sender);
            sendUsage(sender, label);
            return true;
        }

        String actor = sender.getName();

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "on", "enable" -> {
                String reason = joinFrom(args, 1);
                boolean changed = plugin.setMaintenance(true, reason, actor);
                if (changed) {
                    broadcast("messages.enabled-broadcast", actor);
                } else {
                    sender.sendMessage(plugin.mm("<yellow>Maintenance was already on. Reason updated.</yellow>"));
                }
                sendStatus(sender);
            }

            case "off", "disable" -> {
                boolean changed = plugin.setMaintenance(false, null, actor);
                if (changed) {
                    broadcast("messages.disabled-broadcast", actor);
                } else {
                    sender.sendMessage(plugin.mm("<yellow>Maintenance is already off.</yellow>"));
                }
                sendStatus(sender);
            }

            case "toggle" -> {
                boolean next = !plugin.state().isEnabled();
                plugin.setMaintenance(next, joinFrom(args, 1), actor);
                broadcast(next ? "messages.enabled-broadcast" : "messages.disabled-broadcast", actor);
                sendStatus(sender);
            }

            case "status" -> sendStatus(sender);

            case "reason" -> {
                String reason = joinFrom(args, 1);
                if (reason == null) {
                    sender.sendMessage(plugin.mm("<red>Usage: /" + label + " reason (text)</red>"));
                    return true;
                }
                plugin.state().setReason(reason);
                plugin.snapshot().refresh();
                sender.sendMessage(plugin.mm("<green>Reason set to:</green> <white><reason></white>",
                        Placeholder.unparsed("reason", reason)));
            }

            case "whitelist", "wl" -> handleWhitelist(sender, label, args);

            case "reload" -> {
                plugin.reloadEverything();
                sender.sendMessage(plugin.mm("<green>Config reloaded and the HTTP API restarted.</green>"));
                sendStatus(sender);
            }

            default -> sendUsage(sender, label);
        }

        return true;
    }

    private void handleWhitelist(CommandSender sender, String label, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(plugin.mm("<red>Usage: /" + label + " whitelist (add|remove|list) [player]</red>"));
            return;
        }

        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "list" -> {
                List<String> names = plugin.state().whitelistSnapshot();
                if (names.isEmpty()) {
                    sender.sendMessage(plugin.mm("<gray>The maintenance whitelist is empty.</gray>"));
                } else {
                    sender.sendMessage(plugin.mm(
                            "<yellow>Maintenance whitelist (<count>):</yellow> <white><names></white>",
                            Placeholder.unparsed("count", String.valueOf(names.size())),
                            Placeholder.unparsed("names", String.join(", ", names))
                    ));
                }
            }

            case "add" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.mm("<red>Usage: /" + label + " whitelist add (player)</red>"));
                    return;
                }
                boolean added = plugin.state().addToWhitelist(args[2]);
                sender.sendMessage(added
                        ? plugin.mm("<green>Added <white><name></white> to the maintenance whitelist.</green>",
                        Placeholder.unparsed("name", args[2]))
                        : plugin.mm("<yellow><name> is already whitelisted.</yellow>",
                        Placeholder.unparsed("name", args[2])));
            }

            case "remove" -> {
                if (args.length < 3) {
                    sender.sendMessage(plugin.mm("<red>Usage: /" + label + " whitelist remove (player)</red>"));
                    return;
                }
                boolean removed = plugin.state().removeFromWhitelist(args[2]);
                sender.sendMessage(removed
                        ? plugin.mm("<green>Removed <white><name></white> from the maintenance whitelist.</green>",
                        Placeholder.unparsed("name", args[2]))
                        : plugin.mm("<yellow><name> wasn't on the whitelist.</yellow>",
                        Placeholder.unparsed("name", args[2])));
            }

            default -> sender.sendMessage(
                    plugin.mm("<red>Usage: /" + label + " whitelist (add|remove|list) [player]</red>"));
        }
    }

    private void sendStatus(CommandSender sender) {
        boolean on = plugin.state().isEnabled();
        StatusSnapshot snap = plugin.snapshot();

        sender.sendMessage(plugin.mm("<dark_gray><strikethrough>                              </strikethrough></dark_gray>"));
        sender.sendMessage(plugin.mm(
                "<gray>Maintenance:</gray> " + (on ? "<red><bold>ON</bold></red>" : "<green><bold>OFF</bold></green>")));
        sender.sendMessage(plugin.mm("<gray>Reason:</gray> <white><reason></white>",
                Placeholder.unparsed("reason", plugin.state().getReason())));

        if (plugin.state().getSince() > 0) {
            long seconds = (System.currentTimeMillis() - plugin.state().getSince()) / 1000L;
            sender.sendMessage(plugin.mm("<gray>Since:</gray> <white><ago> ago</white>",
                    Placeholder.unparsed("ago", humanDuration(seconds))));
        }

        sender.sendMessage(plugin.mm("<gray>Players:</gray> <white><online>/<max></white>",
                Placeholder.unparsed("online", String.valueOf(snap.online())),
                Placeholder.unparsed("max", String.valueOf(snap.max()))));
        sender.sendMessage(plugin.mm("<gray>TPS:</gray> <white><tps></white>  <gray>MSPT:</gray> <white><mspt></white>",
                Placeholder.unparsed("tps", String.valueOf(snap.tps())),
                Placeholder.unparsed("mspt", String.valueOf(snap.mspt()))));
        sender.sendMessage(plugin.mm("<dark_gray><strikethrough>                              </strikethrough></dark_gray>"));
    }

    private void sendUsage(CommandSender sender, String label) {
        sender.sendMessage(plugin.mm("<yellow>/" + label + " (on | off | toggle | status | reason | whitelist | reload)</yellow>"));
    }

    private void broadcast(String configPath, String actor) {
        String raw = plugin.getConfig().getString(configPath, "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        Component message = plugin.mm(raw, Placeholder.unparsed("actor", actor));
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("havocmaintenance.admin")) {
                player.sendMessage(message);
            }
        }
        plugin.getServer().getConsoleSender().sendMessage(message);
    }

    private static String joinFrom(String[] args, int index) {
        if (args.length <= index) {
            return null;
        }
        return String.join(" ", Arrays.copyOfRange(args, index, args.length));
    }

    private static String humanDuration(long seconds) {
        Duration d = Duration.ofSeconds(Math.max(0, seconds));
        long days = d.toDays();
        long hours = d.toHoursPart();
        long minutes = d.toMinutesPart();

        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return d.toSecondsPart() + "s";
    }

    // ------------------------------------------------------------------
    // Tab completion
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {

        if (!sender.hasPermission("havocmaintenance.admin")) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<>();

        if (args.length == 1) {
            return StringUtil.copyPartialMatches(args[0], ROOT_ARGS, out);
        }

        if (args.length == 2 && args[0].equalsIgnoreCase("whitelist")) {
            return StringUtil.copyPartialMatches(args[1], WHITELIST_ARGS, out);
        }

        if (args.length == 3 && args[0].equalsIgnoreCase("whitelist")) {
            if (args[1].equalsIgnoreCase("remove")) {
                return StringUtil.copyPartialMatches(args[2], plugin.state().whitelistSnapshot(), out);
            }
            if (args[1].equalsIgnoreCase("add")) {
                List<String> online = new ArrayList<>();
                plugin.getServer().getOnlinePlayers().forEach(p -> online.add(p.getName()));
                return StringUtil.copyPartialMatches(args[2], online, out);
            }
        }

        return Collections.emptyList();
    }
}
