package io.github.gawhara.antoutlaw;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GawharaAntiOutlaw extends JavaPlugin implements Listener, PluginMessageListener {
    private static final String PREFIX = "&8[&6Gawhara&cAnti-outlaw&8] ";

    private DiscordNotifier discord;
    private XrayMonitor xray;
    private DecoyManager decoys;
    private ChatGuard chatGuard;
    private final Set<UUID> exemptions = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> chatViolations = new ConcurrentHashMap<>();
    private File exemptionsFile;

    // ───────────────────────── lifecycle ─────────────────────────

    @Override
    public void onEnable() {
        saveDefaultConfig();
        exemptionsFile = new File(getDataFolder(), "exemptions.yml");
        loadExemptions();

        discord = new DiscordNotifier(getLogger());
        discord.start();
        xray = new XrayMonitor(this);
        decoys = new DecoyManager(this);

        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(xray, this);
        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", this);
        } catch (IllegalArgumentException ex) {
            getLogger().warning("Could not register minecraft:brand channel; client-brand checks unavailable.");
        }
        applyConfig();
        getLogger().info("GawharaAnti-outlaw " + getDescription().getVersion() + " enabled.");

        discord.send(new DiscordNotifier.Embed()
                .title("🟢 GawharaAnti-outlaw started")
                .color(DiscordNotifier.GREEN)
                .field("Version", getDescription().getVersion(), true)
                .field("Chat filter", onOff("chat.anti-swear.enabled") + " / links " + onOff("chat.anti-link.enabled"), true)
                .field("X-Ray monitor", onOff("xray-monitor.enabled") + " (decoys: " + decoys.mode() + ")", true));
    }

    @Override
    public void onDisable() {
        try {
            Bukkit.getMessenger().unregisterIncomingPluginChannel(this, "minecraft:brand", this);
        } catch (Exception ignored) {
            // channel was never registered
        }
        if (decoys != null) decoys.shutdown();
        if (discord != null) {
            discord.send(new DiscordNotifier.Embed().title("🔴 GawharaAnti-outlaw stopped").color(DiscordNotifier.GRAY));
            discord.shutdown(); // flushes the queue so the message is really delivered
        }
    }

    private String onOff(String path) {
        return getConfig().getBoolean(path, true) ? "ON" : "OFF";
    }

    /** (Re)reads every setting. Safe to call on reload. */
    private void applyConfig() {
        FileConfiguration c = getConfig();
        discord.configure(c.getBoolean("discord.enabled", true), c.getString("discord.webhook-url", ""),
                c.getString("discord.username", "GawharaAnti-outlaw"));
        Set<String> exts = lower(c.getStringList("chat.anti-link.allowed-extensions"));
        Set<String> hosts = lower(c.getStringList("chat.anti-link.allowed-video-hosts"));
        Set<String> tlds = lower(c.getStringList("chat.anti-link.bare-domain-tlds"));
        chatGuard = new ChatGuard(exts, hosts, tlds,
                c.getBoolean("chat.anti-link.block-bare-domains", true),
                c.getBoolean("chat.anti-link.block-discord-invites", true));
        xray.reload(c);
        decoys.reload(c);
    }

    private static Set<String> lower(List<String> in) {
        Set<String> s = new HashSet<>();
        for (String x : in) s.add(x.toLowerCase(Locale.ROOT).trim());
        return s;
    }

    // ───────────────────────── helpers used by other classes ─────────────────────────

    DiscordNotifier discord() { return discord; }

    DecoyManager decoys() { return decoys; }

    boolean isBypassed(Player p) {
        return p.hasPermission("gawharaoutlaw.bypass") || exemptions.contains(p.getUniqueId());
    }

    Component legacy(String s) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(s);
    }

    void notifyAdmins(Component message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("gawharaoutlaw.admin")) online.sendMessage(message);
        }
    }

    private void notifyAdmins(String legacyText) {
        notifyAdmins(legacy(PREFIX + legacyText));
    }

    private String color(String s) {
        return s == null ? "" : s.replace('&', '§');
    }

    private static String loc(Location l) {
        if (l == null || l.getWorld() == null) return "?";
        return l.getWorld().getName() + " " + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ();
    }

    private void runSync(Runnable r) {
        if (!isEnabled()) return;
        Bukkit.getScheduler().runTask(this, r);
    }

    // ───────────────────────── chat ─────────────────────────

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (isBypassed(player)) return;
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        FileConfiguration c = getConfig();

        if (c.getBoolean("chat.anti-swear.enabled", true)) {
            Optional<String> hit = TextNormalizer.findBlockedWord(message,
                    c.getStringList("chat.anti-swear.blocked-words"),
                    c.getStringList("chat.anti-swear.whole-word-words"),
                    c.getBoolean("chat.anti-swear.collapse-repeated-letters", true));
            if (hit.isPresent()) {
                boolean block = !"WARN".equalsIgnoreCase(c.getString("chat.anti-swear.action", "BLOCK"));
                if (block) event.setCancelled(true);
                player.sendMessage(color(block
                        ? c.getString("chat.anti-swear.blocked-message", "&cYour message was blocked.")
                        : c.getString("chat.anti-swear.warn-message", "&cPlease keep the chat respectful.")));
                int count = chatViolations.merge(player.getUniqueId(), 1, Integer::sum);
                String word = hit.get();
                runSync(() -> reportChat(player, "🚫 Blocked word in chat", DiscordNotifier.RED, message,
                        "Matched word", word, block ? "BLOCKED" : "WARNED (message sent)", count));
                if (block) return;
            }
        }

        if (c.getBoolean("chat.anti-link.enabled", true)) {
            Optional<ChatGuard.LinkResult> link = chatGuard.findBlocked(message);
            if (link.isPresent()) {
                event.setCancelled(true);
                player.sendMessage(color(c.getString("chat.anti-link.blocked-message", "&cThis link type is not allowed.")));
                int count = chatViolations.merge(player.getUniqueId(), 1, Integer::sum);
                ChatGuard.LinkResult lr = link.get();
                runSync(() -> reportChat(player, lr.invite() ? "📨 Discord invite blocked" : "🔗 Blocked link",
                        DiscordNotifier.ORANGE, message, "Blocked link", lr.url() + "\nReason: " + lr.reason(),
                        "BLOCKED", count));
            }
        }
    }

    /** Main thread. Sends the full offending message to Discord and to online staff. */
    private void reportChat(Player p, String title, int color, String message, String detailName,
                            String detail, String action, int violationNo) {
        boolean includeContent = getConfig().getBoolean("discord.include-blocked-content", true);
        DiscordNotifier.Embed e = new DiscordNotifier.Embed()
                .title(title + " · " + p.getName())
                .color(color)
                .description(includeContent ? DiscordNotifier.block(message) : "_(message content hidden by config)_")
                .field("Player", DiscordNotifier.inline(p.getName()) + "\nUUID " + DiscordNotifier.inline(p.getUniqueId().toString()), true)
                .field(detailName, DiscordNotifier.inline(detail.replace("\n", " — ")), true)
                .field("Action", action, true)
                .field("Violation #", String.valueOf(violationNo), true)
                .field("Location", DiscordNotifier.inline(loc(p.getLocation())), true)
                .field("Ping", p.getPing() + " ms", true)
                .footer("GawharaAnti-outlaw chat protection");
        discord.send(e);
        notifyAdmins("&c" + title.replaceAll("^[^\\p{L}]+", "") + " &7— &f" + p.getName()
                + (includeContent ? "&7: &e" + message : ""));
    }

    // ───────────────────────── join / quit / brand ─────────────────────────

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("discord.notify-joins", false)) return;
        Player p = event.getPlayer();
        discord.send(new DiscordNotifier.Embed().title("➡️ Player joined: " + p.getName())
                .color(DiscordNotifier.GRAY).field("UUID", DiscordNotifier.inline(p.getUniqueId().toString()), true));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        decoys.forget(event.getPlayer());
        chatViolations.remove(event.getPlayer().getUniqueId());
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("minecraft:brand") || !getConfig().getBoolean("client-brand-monitor.enabled", true)
                || isBypassed(player)) return;
        String brand = parseBrand(message);
        if (brand == null || brand.isBlank()) return;
        String lower = brand.toLowerCase(Locale.ROOT);
        for (String keyword : getConfig().getStringList("client-brand-monitor.suspicious-brand-keywords")) {
            if (!keyword.isBlank() && lower.contains(keyword.toLowerCase(Locale.ROOT))) {
                List<String> channels = new ArrayList<>(player.getListeningPluginChannels());
                String chans = channels.isEmpty() ? "none yet"
                        : String.join(", ", channels.subList(0, Math.min(10, channels.size())));
                discord.send(new DiscordNotifier.Embed()
                        .title("🧩 Suspicious client brand (unverified) · " + player.getName())
                        .color(DiscordNotifier.YELLOW)
                        .description("The client announced itself as " + DiscordNotifier.inline(brand)
                                + ". Brands can be hidden or faked — **do not punish on this alone.**")
                        .field("Player", DiscordNotifier.inline(player.getName()) + "\nUUID "
                                + DiscordNotifier.inline(player.getUniqueId().toString()), true)
                        .field("Reported brand", DiscordNotifier.inline(brand), true)
                        .field("Matched keyword", DiscordNotifier.inline(keyword), true)
                        .field("Ping", player.getPing() + " ms", true)
                        .field("Registered plugin channels", DiscordNotifier.inline(chans), false));
                notifyAdmins("&eSuspicious client brand &7— &f" + player.getName() + "&7: &e" + brand
                        + " &8(unverified)");
                break;
            }
        }
    }

    /** The brand payload is a Minecraft string: VarInt length + UTF-8 bytes. */
    static String parseBrand(byte[] msg) {
        if (msg == null || msg.length == 0) return null;
        int i = 0, len = 0, shift = 0;
        while (i < msg.length) {
            int b = msg[i++];
            len |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
            if (shift > 28) return null;
        }
        len = Math.max(0, Math.min(len, msg.length - i));
        return new String(msg, i, len, StandardCharsets.UTF_8);
    }

    // ───────────────────────── exemptions ─────────────────────────

    private void loadExemptions() {
        exemptions.clear();
        if (!exemptionsFile.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(exemptionsFile);
        for (String s : y.getStringList("uuids")) {
            try {
                exemptions.add(UUID.fromString(s));
            } catch (IllegalArgumentException ignored) {
                // skip invalid entry
            }
        }
    }

    private void saveExemptions() {
        YamlConfiguration y = new YamlConfiguration();
        List<String> list = new ArrayList<>();
        for (UUID u : exemptions) list.add(u.toString());
        y.set("uuids", list);
        try {
            y.save(exemptionsFile);
        } catch (IOException ex) {
            getLogger().warning("Could not save exemptions.yml: " + ex.getMessage());
        }
    }

    // ───────────────────────── commands ─────────────────────────

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        FileConfiguration c = getConfig();
        if (!sender.hasPermission("gawharaoutlaw.admin")) {
            sender.sendMessage(color(c.getString("messages.no-permission", "&cNo permission.")));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> {
                sender.sendMessage(color(c.getString("messages.status", "&eGawharaAnti-outlaw is running.")));
                sender.sendMessage(color("&7Discord: " + (discord.isConfigured() ? "&aconfigured" : "&cnot configured")
                        + " &8(queue " + discord.queued() + ")"));
                sender.sendMessage(color("&7X-Ray monitor: &f" + onOff("xray-monitor.enabled")
                        + " &7| decoys: &f" + decoys.mode()));
                sender.sendMessage(color("&7Exempt players: &f" + exemptions.size()));
            }
            case "reload" -> {
                reloadConfig();
                loadExemptions();
                applyConfig();
                sender.sendMessage(color(c.getString("messages.reloaded", "&aConfiguration reloaded.")));
            }
            case "test" -> {
                if (!discord.isConfigured()) {
                    sender.sendMessage(color("&cDiscord webhook is not configured (discord.webhook-url)."));
                } else {
                    discord.send(new DiscordNotifier.Embed().title("✅ Test message")
                            .description("Sent by " + sender.getName()).color(DiscordNotifier.GREEN));
                    sender.sendMessage(color("&aTest message queued."));
                }
            }
            case "exempt" -> exemptCommand(sender, args);
            case "xray" -> {
                Player t = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) {
                    sender.sendMessage(color("&e/gao xray <online player>"));
                } else {
                    for (String line : xray.describe(t)) sender.sendMessage(color(line));
                }
            }
            case "evidence" -> {
                Player t = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : null;
                if (t == null) sender.sendMessage(color("&e/gao evidence <online player>"));
                else if (!discord.isConfigured()) sender.sendMessage(color("&cDiscord webhook is not configured."));
                else if (xray.sendManualEvidence(t)) sender.sendMessage(color("&aEvidence report queued for Discord."));
                else sender.sendMessage(color("&7No mining data for that player in the current window."));
            }
            default -> sender.sendMessage(color(c.getString("messages.usage",
                    "&e/gao status | reload | test | exempt add|remove|list | xray <player> | evidence <player>")));
        }
        return true;
    }

    private void exemptCommand(CommandSender sender, String[] args) {
        FileConfiguration c = getConfig();
        if (args.length >= 2 && args[1].equalsIgnoreCase("list")) {
            if (exemptions.isEmpty()) {
                sender.sendMessage(color("&7No exempt players."));
                return;
            }
            for (UUID u : exemptions) {
                OfflinePlayer op = Bukkit.getOfflinePlayer(u);
                sender.sendMessage(color("&7- &f" + (op.getName() == null ? u.toString() : op.getName())));
            }
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(color(c.getString("messages.usage", "&e/gao exempt add|remove|list <player>")));
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[2]);
        if (target == null) {
            OfflinePlayer off = Bukkit.getOfflinePlayer(args[2]);
            if (off.hasPlayedBefore()) target = off;
        }
        if (target == null) {
            sender.sendMessage(color("&cUnknown player (must be online or have joined before)."));
            return;
        }
        if (args[1].equalsIgnoreCase("add")) {
            exemptions.add(target.getUniqueId());
            saveExemptions();
            sender.sendMessage(color(c.getString("messages.exempt-added", "&aPlayer added to bypass list.")));
        } else if (args[1].equalsIgnoreCase("remove")) {
            exemptions.remove(target.getUniqueId());
            saveExemptions();
            sender.sendMessage(color(c.getString("messages.exempt-removed", "&aPlayer removed from bypass list.")));
        } else {
            sender.sendMessage(color(c.getString("messages.usage", "&e/gao exempt add|remove|list <player>")));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!sender.hasPermission("gawharaoutlaw.admin")) return out;
        if (args.length == 1) {
            for (String s : List.of("status", "reload", "test", "exempt", "xray", "evidence")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("exempt")) {
            for (String s : List.of("add", "remove", "list")) {
                if (s.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(s);
            }
        } else if ((args.length == 2 && (args[0].equalsIgnoreCase("xray") || args[0].equalsIgnoreCase("evidence")))
                || (args.length == 3 && args[0].equalsIgnoreCase("exempt"))) {
            String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(p.getName());
            }
        }
        return out;
    }
}
