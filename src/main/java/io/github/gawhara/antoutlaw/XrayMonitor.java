package io.github.gawhara.antoutlaw;

import io.github.gawhara.antoutlaw.XrayScorer.BreakRecord;
import io.github.gawhara.antoutlaw.XrayScorer.Kind;
import io.github.gawhara.antoutlaw.XrayScorer.Level;
import io.github.gawhara.antoutlaw.XrayScorer.Result;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Statistical X-Ray monitoring. Counts what each survival player mines in a sliding window,
 * separates ores that were already visible from ores that were hidden until the player's own tunnel
 * reached them, scores the pattern and reports to staff / Discord (with an evidence image).
 * Everything here runs on the main thread except the image rendering.
 */
final class XrayMonitor implements Listener {
    private static final BlockFace[] FACES = {BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH,
            BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST};
    private static final int MAX_RECORDS = 4000;

    private static final class PlayerData {
        final ArrayDeque<BreakRecord> records = new ArrayDeque<>();
        /** any block broken by the player in the window: key -> time */
        final Map<Long, Long> brokenAt = new HashMap<>();
        final ArrayDeque<long[]> brokenQueue = new ArrayDeque<>();
        UUID world;
        long lastAlert;
        Level lastLevel = Level.NONE;
        Result last;

        void reset() {
            records.clear();
            brokenAt.clear();
            brokenQueue.clear();
            lastLevel = Level.NONE;
            last = null;
        }
    }

    private final GawharaAntiOutlaw plugin;
    private final Map<UUID, PlayerData> data = new HashMap<>();
    private final Map<UUID, LinkedHashSet<Long>> placed = new HashMap<>();

    private boolean enabled = true, evidenceImage = true;
    private long windowMs = 600_000, cooldownMs = 600_000;
    private int evidenceMinScore = 40, triggerScore = 25, triggerHidden = 3, placedCap = 50_000;
    private final Set<Material> ores = EnumSet.noneOf(Material.class);
    private final Set<Material> stones = EnumSet.of(Material.STONE, Material.DEEPSLATE, Material.ANDESITE,
            Material.DIORITE, Material.GRANITE, Material.TUFF, Material.NETHERRACK, Material.BASALT,
            Material.BLACKSTONE, Material.END_STONE, Material.CALCITE);
    private final XrayScorer.Config cfg = new XrayScorer.Config();

    XrayMonitor(GawharaAntiOutlaw plugin) {
        this.plugin = plugin;
    }

    void reload(FileConfiguration c) {
        String p = "xray-monitor.";
        enabled = c.getBoolean(p + "enabled", true);
        windowMs = Math.max(1, c.getLong(p + "window-minutes", 10)) * 60_000L;
        cooldownMs = Math.max(10, c.getLong(p + "alert-cooldown-seconds", 600)) * 1000L;
        evidenceImage = c.getBoolean(p + "evidence-image", true);
        evidenceMinScore = c.getInt(p + "evidence-min-score", 40);
        cfg.minHidden = Math.max(1, c.getInt(p + "minimum-hidden-ores", 5));
        cfg.minStone = Math.max(1, c.getInt(p + "minimum-stone-breaks", 60));
        cfg.ratioLow = c.getDouble(p + "ratio-low", 0.02);
        cfg.ratioHigh = Math.max(cfg.ratioLow + 0.01, c.getDouble(p + "ratio-high", 0.12));
        cfg.low = c.getInt(p + "threshold-low", 40);
        cfg.medium = c.getInt(p + "threshold-medium", 60);
        cfg.high = c.getInt(p + "threshold-high", 80);
        cfg.cellsPerBreak = c.getDouble(p + "decoys.cells-per-break", 3.0);
        cfg.decoyDensity = c.getDouble(p + "decoys.density", 0.04);
        triggerScore = c.getInt(p + "decoys.trigger-score", 25);
        triggerHidden = c.getInt(p + "decoys.trigger-hidden-ores", 3);
        ores.clear();
        for (String n : c.getStringList(p + "monitored-ores")) {
            Material m = Material.matchMaterial(n);
            if (m != null) ores.add(m);
            else plugin.getLogger().warning("Unknown monitored ore material: " + n);
        }
    }

    // ───────────────────────── events ─────────────────────────

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent e) {
        if (!enabled) return;
        Block b = e.getBlockPlaced();
        Material m = b.getType();
        if (!ores.contains(m) && !stones.contains(m)) return;
        LinkedHashSet<Long> set = placed.computeIfAbsent(b.getWorld().getUID(), k -> new LinkedHashSet<>());
        set.add(Pos.pack(b.getX(), b.getY(), b.getZ()));
        if (set.size() > placedCap) {
            Iterator<Long> it = set.iterator();
            it.next();
            it.remove();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        if (!enabled) return;
        Player p = e.getPlayer();
        if (p.getGameMode() != GameMode.SURVIVAL || plugin.isBypassed(p)) return;

        Block b = e.getBlock();
        Material m = b.getType();
        long key = Pos.pack(b.getX(), b.getY(), b.getZ());

        Set<Long> pl = placed.get(b.getWorld().getUID());
        if (pl != null && pl.remove(key)) return; // player-placed block: not natural

        PlayerData d = data.computeIfAbsent(p.getUniqueId(), k -> new PlayerData());
        long now = System.currentTimeMillis();
        if (!b.getWorld().getUID().equals(d.world)) {
            d.reset();
            d.world = b.getWorld().getUID();
        }
        prune(d, now);

        // decoy exposure: did this break open (or hit) a fake ore the player can only see with X-Ray?
        boolean decoyHit = false;
        DecoyManager dm = plugin.decoys();
        if (dm != null && !dm.isOff()) {
            if (dm.reveal(p, key)) {
                add(d, new BreakRecord(now, b.getX(), b.getY(), b.getZ(), "DECOY", Kind.DECOY_EXPOSED));
                decoyHit = true;
            }
            for (BlockFace f : FACES) {
                Block nb = b.getRelative(f);
                if (dm.reveal(p, Pos.pack(nb.getX(), nb.getY(), nb.getZ()))) {
                    add(d, new BreakRecord(now, nb.getX(), nb.getY(), nb.getZ(), "DECOY", Kind.DECOY_EXPOSED));
                    decoyHit = true;
                }
            }
        }

        boolean isOre = ores.contains(m);
        boolean isStone = !isOre && stones.contains(m);
        Kind kind = null;
        if (isOre) kind = isHidden(b, d) ? Kind.ORE_HIDDEN : Kind.ORE_EXPOSED;
        else if (isStone) kind = Kind.STONE;

        if (kind != null) add(d, new BreakRecord(now, b.getX(), b.getY(), b.getZ(), m.name(), kind));
        // remember every block the player removed (dirt, gravel...) so "who opened this cell" is accurate
        d.brokenAt.put(key, now);
        d.brokenQueue.add(new long[]{now, key});

        if (kind == Kind.ORE_HIDDEN || decoyHit) evaluate(p, d, now);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        data.remove(e.getPlayer().getUniqueId());
    }

    // ───────────────────────── logic ─────────────────────────

    /** Hidden = every open neighbour of the ore was opened by this player's own mining. */
    private boolean isHidden(Block ore, PlayerData d) {
        for (BlockFace f : FACES) {
            Block nb = ore.getRelative(f);
            if (!nb.getType().isOccluding()
                    && !d.brokenAt.containsKey(Pos.pack(nb.getX(), nb.getY(), nb.getZ()))) {
                return false; // visible from a cave / old tunnel / water / lava
            }
        }
        return true;
    }

    private void add(PlayerData d, BreakRecord r) {
        d.records.add(r);
        if (d.records.size() > MAX_RECORDS) d.records.poll();
    }

    private void prune(PlayerData d, long now) {
        long cut = now - windowMs;
        while (!d.records.isEmpty() && d.records.peekFirst().time() < cut) d.records.pollFirst();
        while (!d.brokenQueue.isEmpty() && d.brokenQueue.peekFirst()[0] < cut) {
            long[] e = d.brokenQueue.pollFirst();
            Long t = d.brokenAt.get(e[1]);
            if (t != null && t == e[0]) d.brokenAt.remove(e[1]);
        }
        if (d.brokenQueue.size() > 20_000) { // safety net
            d.brokenQueue.clear();
            d.brokenAt.clear();
        }
    }

    private Result compute(Player p, PlayerData d) {
        int decoyBreaks = 0;
        DecoyManager dm = plugin.decoys();
        long since = dm == null ? 0 : dm.activeSince(p);
        if (since > 0) {
            for (BreakRecord r : d.records) {
                if (r.kind() != Kind.DECOY_EXPOSED && r.time() >= since) decoyBreaks++;
            }
        }
        return XrayScorer.score(new ArrayList<>(d.records), cfg, decoyBreaks);
    }

    private void evaluate(Player p, PlayerData d, long now) {
        Result r = compute(p, d);
        d.last = r;
        DecoyManager dm = plugin.decoys();
        if (dm != null && (r.score >= triggerScore || r.hidden >= triggerHidden)) dm.enable(p);
        if (r.level == Level.NONE) {
            d.lastLevel = Level.NONE;
            return;
        }
        boolean escalated = r.level.ordinal() > d.lastLevel.ordinal();
        if (escalated || now - d.lastAlert >= cooldownMs) {
            d.lastAlert = now;
            d.lastLevel = r.level;
            sendAlert(p, d, r, false);
        }
    }

    // ───────────────────────── reporting ─────────────────────────

    /** In-game summary for /gao xray. */
    List<String> describe(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        List<String> out = new ArrayList<>();
        if (d == null || d.records.isEmpty()) {
            out.add("&7No mining data for &f" + p.getName() + "&7 in the last " + (windowMs / 60000) + " min.");
            return out;
        }
        prune(d, System.currentTimeMillis());
        Result r = compute(p, d);
        out.add("&6" + p.getName() + " &7— score &e" + r.score + "/100 &7(" + r.level + ")");
        out.addAll(statLines(r).stream().map(s -> "&7" + s).toList());
        for (String s : r.reasons) out.add("&c• &f" + s);
        return out;
    }

    /** Manual evidence request: always sends an embed (+ image) to Discord. */
    boolean sendManualEvidence(Player p) {
        PlayerData d = data.get(p.getUniqueId());
        if (d == null || d.records.isEmpty()) return false;
        prune(d, System.currentTimeMillis());
        sendAlert(p, d, compute(p, d), true);
        return true;
    }

    private List<String> statLines(Result r) {
        List<String> l = new ArrayList<>();
        l.add(String.format(Locale.US, "Window: %.1f min of mining | stone broken: %d", r.spanMinutes, r.stone));
        l.add(String.format(Locale.US, "Hidden ores: %d | exposed ores (caves): %d | hidden ratio: %.1f%%",
                r.hidden, r.exposed, r.ratio * 100));
        l.add(String.format(Locale.US, "Hidden ores/min: %.2f | avg stone between ores: %s",
                r.perMinute, r.avgGap < 0 ? "n/a" : String.format(Locale.US, "%.0f", r.avgGap)));
        l.add("Direction changed straight to ore: " + r.turnedToward + "/" + r.hidden);
        if (r.decoyBreaks > 0 || r.decoyExposed > 0) {
            l.add(String.format(Locale.US, "Fake ores reached: %d (by chance ≈ %.1f)", r.decoyExposed, r.decoyExpected));
        }
        for (Map.Entry<String, int[]> e : r.perType.entrySet()) {
            l.add(String.format(Locale.US, "%s: hidden %d, exposed %d", e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        return l;
    }

    private void sendAlert(Player p, PlayerData d, Result r, boolean manual) {
        Location loc = p.getLocation();
        String world = loc.getWorld() == null ? "?" : loc.getWorld().getName();
        int x = loc.getBlockX(), y = loc.getBlockY(), z = loc.getBlockZ();
        String tp = "/tp @s " + x + " " + y + " " + z;

        // in-game staff message
        Component msg = plugin.legacy("&8[&6Gawhara&cAnti-outlaw&8] &e⛏ X-Ray " + r.level + " &7— &f" + p.getName()
                + " &7score &e" + r.score + "&7, hidden ores &e" + r.hidden + "&7, ratio &e"
                + String.format(Locale.US, "%.1f%%", r.ratio * 100)
                + (r.decoyExposed > 0 ? " &7fake ores reached &d" + r.decoyExposed : "") + " ")
                .append(Component.text("[TP]", NamedTextColor.AQUA)
                        .clickEvent(ClickEvent.runCommand("/tp " + x + " " + y + " " + z)));
        plugin.notifyAdmins(msg);

        // Discord embed
        int color = manual ? DiscordNotifier.BLUE : switch (r.level) {
            case HIGH -> DiscordNotifier.RED;
            case MEDIUM -> DiscordNotifier.ORANGE;
            default -> DiscordNotifier.YELLOW;
        };
        DiscordNotifier.Embed e = new DiscordNotifier.Embed()
                .title((manual ? "📎 X-Ray evidence (manual)" : "⛏️ X-Ray suspicion — " + r.level)
                        + " · " + p.getName() + " · " + r.score + "/100")
                .color(color)
                .description("Statistical signal based on the last " + (windowMs / 60000)
                        + " minutes of mining. **Review before punishing.**")
                .field("Player", DiscordNotifier.inline(p.getName()) + "\nUUID " + DiscordNotifier.inline(p.getUniqueId().toString()), true)
                .field("Location", DiscordNotifier.inline(world + " " + x + " " + y + " " + z)
                        + "\nTeleport: " + DiscordNotifier.inline(tp), true)
                .field("Ping", p.getPing() + " ms", true)
                .field("Stone broken", String.valueOf(r.stone), true)
                .field("Hidden ores", r.hidden + " (ratio " + String.format(Locale.US, "%.1f%%", r.ratio * 100) + ")", true)
                .field("Exposed ores (caves)", String.valueOf(r.exposed), true)
                .field("Hidden ores / min", String.format(Locale.US, "%.2f", r.perMinute), true)
                .field("Avg stone between ores", r.avgGap < 0 ? "n/a" : String.format(Locale.US, "%.0f", r.avgGap), true)
                .field("Turned straight to ore", r.turnedToward + "/" + r.hidden, true);
        if (r.decoyBreaks > 0 || r.decoyExposed > 0) {
            e.field("🪤 Fake ores reached", r.decoyExposed + " (by chance ≈ "
                    + String.format(Locale.US, "%.1f", r.decoyExpected) + ")", false);
        }
        if (!r.perType.isEmpty()) {
            StringBuilder t = new StringBuilder();
            for (Map.Entry<String, int[]> en : r.perType.entrySet()) {
                t.append(String.format(Locale.US, "%-24s hidden %d | exposed %d%n", en.getKey(), en.getValue()[0], en.getValue()[1]));
            }
            e.field("Per ore type", DiscordNotifier.block(t.toString().trim()), false);
        }
        if (!r.reasons.isEmpty()) {
            e.field("Why flagged", "• " + String.join("\n• ", r.reasons), false);
        }
        StringBuilder last = new StringBuilder();
        List<BreakRecord> all = new ArrayList<>(d.records);
        int shown = 0;
        for (int i = all.size() - 1; i >= 0 && shown < 8; i--) {
            BreakRecord br = all.get(i);
            if (br.kind() != Kind.ORE_HIDDEN) continue;
            last.append("`").append(br.material()).append("` (").append(br.x()).append(", ").append(br.y())
                .append(", ").append(br.z()).append(") <t:").append(br.time() / 1000).append(":T>\n");
            shown++;
        }
        if (shown > 0) e.field("Last hidden ores found", last.toString().trim(), false);
        e.footer("Heuristic signal — not proof. Server-side reconstruction, not a client screenshot.");

        boolean image = evidenceImage && (manual || r.score >= evidenceMinScore);
        if (!image) {
            plugin.discord().send(e);
            return;
        }
        String title = "X-Ray evidence — " + p.getName() + " — score " + r.score + "/100 (" + r.level + ")";
        String sub = world + " @ " + x + ", " + y + ", " + z + "   |   last " + (windowMs / 60000) + " min   |   "
                + java.time.Instant.now().toString().replace('T', ' ').replaceAll("\\.\\d+Z", " UTC");
        List<String> stats = statLines(r);
        List<String> reasons = new ArrayList<>(r.reasons);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] png = EvidenceRenderer.render(title, sub, all, stats, reasons);
            plugin.discord().send(e, png, "xray-evidence.png");
        });
    }
}
