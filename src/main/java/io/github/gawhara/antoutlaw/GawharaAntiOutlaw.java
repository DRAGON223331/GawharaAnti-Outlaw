package io.github.gawhara.antoutlaw;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class GawharaAntiOutlaw extends JavaPlugin implements Listener, PluginMessageListener {
    private static final Pattern URL_PATTERN = Pattern.compile("(?i)\\b(?:https?://|www\\.)[^\\s<>]+");
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final Map<UUID, MiningStats> mining = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastXrayAlert = new ConcurrentHashMap<>();
    private final Set<String> exemptions = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this, this);
        try {
            Bukkit.getMessenger().registerIncomingPluginChannel(this, "minecraft:brand", this);
        } catch (IllegalArgumentException ignored) {
            getLogger().warning("Could not register minecraft:brand channel; client-brand checks unavailable.");
        }
        getLogger().info("GawharaAnti-outlaw enabled.");
        sendDiscord("🟢 **GawharaAnti-outlaw** started on server.");
    }

    @Override
    public void onDisable() {
        try { Bukkit.getMessenger().unregisterIncomingPluginChannel(this, "minecraft:brand", this); }
        catch (Exception ignored) {}
        sendDiscord("🔴 **GawharaAnti-outlaw** stopped.");
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (bypass(player)) return;
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());

        if (getConfig().getBoolean("chat.anti-swear.enabled", true) && containsBlockedWord(message)) {
            String action = getConfig().getString("chat.anti-swear.action", "BLOCK");
            boolean block = !action.equalsIgnoreCase("WARN");
            if (block) event.setCancelled(true);
            String msg = block
                    ? getConfig().getString("chat.anti-swear.blocked-message", "&cMessage blocked.")
                    : getConfig().getString("chat.anti-swear.warn-message", "&cPlease keep the chat respectful.");
            player.sendMessage(color(msg));
            alert("🚫 **Chat filter**\nPlayer: `" + safe(player.getName()) + "`\nReason: blocked word detected\nAction: `" + (block ? "BLOCK" : "WARN") + "`");
            if (block) return;
        }

        if (getConfig().getBoolean("chat.anti-link.enabled", true)) {
            Matcher matcher = URL_PATTERN.matcher(message);
            while (matcher.find()) {
                String url = matcher.group();
                if (!isAllowedUrl(url)) {
                    event.setCancelled(true);
                    player.sendMessage(color(getConfig().getString("chat.anti-link.blocked-message", "&cThis link type is not allowed.")));
                    alert("🔗 **Blocked link**\nPlayer: `" + safe(player.getName()) + "`\nLink: `" + safe(url) + "`");
                    return;
                }
            }
        }
    }

    private boolean containsBlockedWord(String input) {
        String normalized = normalize(input);
        for (String word : getConfig().getStringList("chat.anti-swear.blocked-words")) {
            String target = normalize(word);
            if (!target.isBlank() && normalized.contains(target)) return true;
        }
        return false;
    }

    private String normalize(String input) {
        String s = input.toLowerCase(Locale.ROOT)
                .replace('0', 'o').replace('1', 'i').replace('3', 'e')
                .replace('4', 'a').replace('5', 's').replace('7', 't')
                .replace('@', 'a').replace('$', 's');
        return s.replaceAll("[^\\p{IsAlphabetic}\\p{IsDigit}]", "");
    }

    private boolean isAllowedUrl(String raw) {
        String cleaned = raw.replaceAll("[),.!?;:]+$", "");
        String candidate = cleaned.startsWith("www.") ? "https://" + cleaned : cleaned;
        try {
            URI uri = URI.create(candidate);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            String path = Optional.ofNullable(uri.getPath()).orElse("");
            int dot = path.lastIndexOf('.');
            if (dot >= 0) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.ROOT);
                if (getConfig().getStringList("chat.anti-link.allowed-extensions").contains(ext)) return true;
            }
            for (String allowed : getConfig().getStringList("chat.anti-link.allowed-video-hosts")) {
                String domain = allowed.toLowerCase(Locale.ROOT);
                if (host.equals(domain) || host.endsWith("." + domain)) return true;
            }
        } catch (IllegalArgumentException ignored) {
            return false;
        }
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!getConfig().getBoolean("xray-monitor.enabled", true) || bypass(event.getPlayer())) return;
        Material type = event.getBlock().getType();
        MiningStats stats = mining.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new MiningStats());
        synchronized (stats) {
            if (isOre(type)) stats.ores++;
            if (isStoneLike(type)) stats.stone++;
            int minOre = getConfig().getInt("xray-monitor.minimum-ore-breaks", 18);
            int minStone = getConfig().getInt("xray-monitor.minimum-stone-breaks", 80);
            double ratio = stats.ores / (double) Math.max(1, stats.ores + stats.stone);
            double threshold = getConfig().getDouble("xray-monitor.suspicious-ore-ratio", 0.22);
            long now = System.currentTimeMillis();
            long cooldown = getConfig().getLong("xray-monitor.alert-cooldown-seconds", 600) * 1000L;
            long last = lastXrayAlert.getOrDefault(event.getPlayer().getUniqueId(), 0L);
            if (stats.ores >= minOre && stats.stone >= minStone && ratio >= threshold && now - last >= cooldown) {
                lastXrayAlert.put(event.getPlayer().getUniqueId(), now);
                alert("⛏️ **Mining anomaly (review required)**\nPlayer: `" + safe(event.getPlayer().getName())
                        + "`\nOre blocks: `" + stats.ores + "`\nStone blocks: `" + stats.stone
                        + "`\nOre ratio: `" + String.format(Locale.US, "%.1f%%", ratio * 100)
                        + "`\nThis is a heuristic signal, not proof of X-Ray.");
            }
            if (stats.ores + stats.stone > 2000) { stats.ores = 0; stats.stone = 0; }
        }
    }

    private boolean isOre(Material material) {
        return getConfig().getStringList("xray-monitor.monitored-ores").contains(material.name());
    }

    private boolean isStoneLike(Material m) {
        String n = m.name();
        return n.contains("STONE") || n.contains("DEEPSLATE") || n.equals("NETHERRACK") || n.equals("END_STONE") || n.equals("TUFF");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (getConfig().getBoolean("discord.notify-joins", false))
            alert("➡️ Player joined: `" + safe(event.getPlayer().getName()) + "`");
    }

    // Client brand is only a hint: clients can hide, spoof, or omit it.
    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!channel.equals("minecraft:brand") || !getConfig().getBoolean("client-brand-monitor.enabled", true) || bypass(player)) return;
        String brand;
        try {
            int length = message[0] & 0xFF;
            brand = new String(message, 1, Math.min(length, message.length - 1), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (Exception ex) { return; }
        for (String keyword : getConfig().getStringList("client-brand-monitor.suspicious-brand-keywords")) {
            if (brand.contains(keyword.toLowerCase(Locale.ROOT))) {
                alert("🧩 **Suspicious client brand (unverified)**\nPlayer: `" + safe(player.getName())
                        + "`\nReported brand: `" + safe(brand) + "`\nDo not punish based on this signal alone.");
                break;
            }
        }
    }

    private boolean bypass(Player player) {
        return player.hasPermission("gawharaoutlaw.bypass") || exemptions.contains(player.getUniqueId().toString());
    }

    private void alert(String message) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("gawharaoutlaw.admin")) online.sendMessage(color("&8[&6Gawhara&cAnti-outlaw&8] &e" + message.replace("**", "")));
        }
        sendDiscord(message);
    }

    private void sendDiscord(String content) {
        if (!getConfig().getBoolean("discord.enabled", true)) return;
        String webhook = getConfig().getString("discord.webhook-url", "").trim();
        if (webhook.isEmpty()) return;
        if (!webhook.startsWith("https://discord.com/api/webhooks/") &&
            !webhook.startsWith("https://discordapp.com/api/webhooks/")) {
            getLogger().warning("Discord webhook URL is invalid; expected a Discord webhook URL.");
            return;
        }
        String username = getConfig().getString("discord.username", "GawharaAnti-outlaw");
        String json = "{\"username\":\"" + jsonEscape(username) + "\",\"content\":\"" + jsonEscape(content) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhook))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                .thenAccept(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        getLogger().warning("Discord webhook returned HTTP " + response.statusCode());
                })
                .exceptionally(ex -> { getLogger().warning("Discord webhook failed: " + ex.getMessage()); return null; });
    }

    private String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
    private String safe(String s) { return s.replace("`", "'").replace("@", "@\u200b").replace("\n", " "); }
    private String color(String s) { return ChatColor.translateAlternateColorCodes('&', s); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("gawharaoutlaw.admin")) {
            sender.sendMessage(color(getConfig().getString("messages.no-permission", "&cNo permission.")));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("status")) {
            sender.sendMessage(color(getConfig().getString("messages.status", "&ePlugin running.")));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
            sender.sendMessage(color(getConfig().getString("messages.reloaded", "&aReloaded.")));
            return true;
        }
        if (args[0].equalsIgnoreCase("exempt") && args.length >= 3) {
            Player target = Bukkit.getPlayerExact(args[2]);
            if (target == null) { sender.sendMessage(color("&cPlayer must be online.")); return true; }
            if (args[1].equalsIgnoreCase("add")) exemptions.add(target.getUniqueId().toString());
            else if (args[1].equalsIgnoreCase("remove")) exemptions.remove(target.getUniqueId().toString());
            sender.sendMessage(color(args[1].equalsIgnoreCase("add")
                    ? getConfig().getString("messages.exempt-added", "&aAdded.")
                    : getConfig().getString("messages.exempt-removed", "&aRemoved.")));
            return true;
        }
        sender.sendMessage(color(getConfig().getString("messages.usage", "&e/gao reload | status | exempt add/remove <player>")));
        return true;
    }

    private static final class MiningStats { int ores; int stone; }
}
