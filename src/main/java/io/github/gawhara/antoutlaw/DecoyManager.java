package io.github.gawhara.antoutlaw;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * "Fake ore" trap. For suspected X-Ray users only, fully enclosed stone blocks near the player are
 * shown (client side only!) as rich ores. The server's real world is never changed: it is still stone.
 *
 * A normal player cannot see enclosed blocks, so never notices. An X-Ray client highlights them.
 * When the player's own mining exposes such a cell, the real block is restored and the event is recorded;
 * tunnelling to many fake ores is very strong evidence.
 */
final class DecoyManager {
    private static final Set<Material> HOSTS = EnumSet.of(Material.STONE, Material.DEEPSLATE,
            Material.ANDESITE, Material.DIORITE, Material.GRANITE, Material.TUFF);
    private static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    record Cell(int x, int y, int z, Material fake) {}

    private static final class State {
        Map<Long, Cell> cells = new HashMap<>();
        final Set<Long> revealed = new HashSet<>();
        long until, activeSince, lastFull;
        UUID world;
        int lx, ly, lz;
        boolean scanned;
    }

    private final GawharaAntiOutlaw plugin;
    private final Map<UUID, State> states = new HashMap<>();
    private final Map<Material, BlockData> dataCache = new EnumMap<>(Material.class);
    private final long salt = ThreadLocalRandom.current().nextLong();
    private BukkitTask task;

    private String mode = "SUSPECTS";
    private int radius = 8, maxPerPlayer = 250, maxY = 64, intervalTicks = 40, densityThreshold = 400;
    private long durationMs = 600_000;
    private double density = 0.04;
    private final List<Material> fakeStone = new ArrayList<>();
    private final List<Material> fakeDeep = new ArrayList<>();

    DecoyManager(GawharaAntiOutlaw plugin) {
        this.plugin = plugin;
    }

    void reload(FileConfiguration cfg) {
        String p = "xray-monitor.decoys.";
        mode = cfg.getString(p + "mode", "SUSPECTS").toUpperCase();
        radius = Math.max(3, Math.min(16, cfg.getInt(p + "radius", 8)));
        density = Math.max(0.001, Math.min(0.2, cfg.getDouble(p + "density", 0.04)));
        densityThreshold = (int) Math.round(density * 10000);
        maxPerPlayer = Math.max(10, cfg.getInt(p + "max-per-player", 250));
        maxY = cfg.getInt(p + "max-y", 64);
        intervalTicks = Math.max(10, cfg.getInt(p + "interval-ticks", 40));
        durationMs = Math.max(30, cfg.getLong(p + "duration-seconds", 600)) * 1000L;
        fakeStone.clear();
        fakeDeep.clear();
        for (String name : cfg.getStringList(p + "fake-ores")) {
            Material m = Material.matchMaterial(name);
            if (m == null) {
                plugin.getLogger().warning("Unknown decoy ore material: " + name);
                continue;
            }
            fakeStone.add(m);
            Material deep = Material.matchMaterial("DEEPSLATE_" + m.name());
            fakeDeep.add(deep != null ? deep : m);
        }
        if (fakeStone.isEmpty()) {
            fakeStone.add(Material.DIAMOND_ORE);
            fakeDeep.add(Material.DEEPSLATE_DIAMOND_ORE);
        }
        stopTask();
        if (!mode.equals("OFF")) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 40L, intervalTicks);
        } else {
            restoreAllOnline();
            states.clear();
        }
    }

    boolean isOff() {
        return mode.equals("OFF");
    }

    String mode() {
        return mode;
    }

    double density() {
        return density;
    }

    void shutdown() {
        stopTask();
        restoreAllOnline();
        states.clear();
    }

    private void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void restoreAllOnline() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            State s = states.get(p.getUniqueId());
            if (s != null) restoreAll(p, s);
        }
    }

    /** Starts (or extends) the trap for this player. */
    void enable(Player p) {
        if (isOff()) return;
        long now = System.currentTimeMillis();
        State s = states.computeIfAbsent(p.getUniqueId(), k -> new State());
        if (s.until < now) {
            s.activeSince = now;
            s.revealed.clear();
        }
        s.until = now + durationMs;
    }

    /** Time the trap became active for this player (0 = not active). */
    long activeSince(Player p) {
        if (isOff()) return 0;
        State s = states.get(p.getUniqueId());
        if (s == null) return 0;
        return active(s, System.currentTimeMillis()) ? s.activeSince : 0;
    }

    private boolean active(State s, long now) {
        return mode.equals("ALL") || s.until > now;
    }

    void forget(Player p) {
        states.remove(p.getUniqueId());
    }

    /**
     * Called when the player breaks the block at {@code key} / next to it.
     * If a decoy sits at {@code key}, it is restored to the real block.
     *
     * @return true if a decoy was there
     */
    boolean reveal(Player p, long key) {
        State s = states.get(p.getUniqueId());
        if (s == null) return false;
        Cell c = s.cells.remove(key);
        if (c == null) return false;
        s.revealed.add(key);
        World w = p.getWorld();
        if (w.getUID().equals(s.world)) {
            p.sendBlockChange(new Location(w, c.x(), c.y(), c.z()), w.getBlockAt(c.x(), c.y(), c.z()).getBlockData());
        }
        return true;
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (plugin.isBypassed(p) || p.getGameMode() != GameMode.SURVIVAL) continue;
            if (mode.equals("ALL")) enable(p);
            State s = states.get(p.getUniqueId());
            if (s == null) continue;
            if (!active(s, now)) {
                if (!s.cells.isEmpty()) restoreAll(p, s);
                continue;
            }
            try {
                refresh(p, s, now);
            } catch (Exception ex) {
                plugin.getLogger().warning("Decoy refresh failed: " + ex);
            }
        }
    }

    private void refresh(Player p, State s, long now) {
        World w = p.getWorld();
        Location l = p.getLocation();
        int px = l.getBlockX(), py = l.getBlockY(), pz = l.getBlockZ();
        if (!w.getUID().equals(s.world)) {
            s.cells.clear();
            s.revealed.clear();
            s.world = w.getUID();
            s.scanned = false;
            s.lastFull = 0;
        }
        boolean moved = !s.scanned || Math.abs(px - s.lx) >= 3 || Math.abs(py - s.ly) >= 3 || Math.abs(pz - s.lz) >= 3;
        if (moved) {
            Map<Long, Cell> next = scan(w, s, px, py, pz);
            for (Map.Entry<Long, Cell> e : s.cells.entrySet()) {
                if (!next.containsKey(e.getKey())) restore(p, w, e.getValue());
            }
            for (Map.Entry<Long, Cell> e : next.entrySet()) {
                if (!s.cells.containsKey(e.getKey())) sendFake(p, w, e.getValue());
            }
            s.cells = next;
            s.lx = px; s.ly = py; s.lz = pz;
            s.scanned = true;
        }
        // chunks resent by the server wipe client-side fakes, so re-send periodically
        if (now - s.lastFull >= 10_000) {
            for (Cell c : s.cells.values()) sendFake(p, w, c);
            s.lastFull = now;
        }
    }

    private Map<Long, Cell> scan(World w, State s, int px, int py, int pz) {
        Map<Long, Cell> next = new HashMap<>();
        int minY = Math.max(w.getMinHeight(), py - radius);
        int maxYv = Math.min(Math.min(maxY, w.getMaxHeight() - 1), py + radius);
        for (int x = px - radius; x <= px + radius; x++) {
            for (int z = pz - radius; z <= pz + radius; z++) {
                for (int y = minY; y <= maxYv; y++) {
                    long h = hash(x, y, z);
                    if ((h % 10000) >= densityThreshold) continue;
                    long key = Pos.pack(x, y, z);
                    if (s.revealed.contains(key)) continue;
                    Material host = typeAt(w, x, y, z);
                    if (host == null || !HOSTS.contains(host)) continue;
                    if (!enclosed(w, x, y, z)) continue;
                    if (next.size() >= maxPerPlayer) return next;
                    List<Material> pool = host == Material.DEEPSLATE ? fakeDeep : fakeStone;
                    Material fake = pool.get((int) ((h >>> 20) % pool.size()));
                    next.put(key, new Cell(x, y, z, fake));
                }
            }
        }
        return next;
    }

    private boolean enclosed(World w, int x, int y, int z) {
        for (int[] f : FACES) {
            Material m = typeAt(w, x + f[0], y + f[1], z + f[2]);
            if (m == null || !m.isOccluding()) return false;
        }
        return true;
    }

    /** Never loads chunks: returns null when the chunk is not loaded or Y is out of range. */
    private static Material typeAt(World w, int x, int y, int z) {
        if (y < w.getMinHeight() || y >= w.getMaxHeight()) return null;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null;
        return w.getBlockAt(x, y, z).getType();
    }

    private long hash(int x, int y, int z) {
        long h = x * 73428767L ^ y * 912931L ^ z * 42317861L ^ salt;
        h ^= h >>> 13;
        h *= 0x9E3779B97F4A7C15L;
        h ^= h >>> 29;
        return h >>> 1;
    }

    private void sendFake(Player p, World w, Cell c) {
        BlockData d = dataCache.computeIfAbsent(c.fake(), Bukkit::createBlockData);
        p.sendBlockChange(new Location(w, c.x(), c.y(), c.z()), d);
    }

    private void restore(Player p, World w, Cell c) {
        if (!w.isChunkLoaded(c.x() >> 4, c.z() >> 4)) return;
        p.sendBlockChange(new Location(w, c.x(), c.y(), c.z()), w.getBlockAt(c.x(), c.y(), c.z()).getBlockData());
    }

    private void restoreAll(Player p, State s) {
        World w = p.getWorld();
        if (w.getUID().equals(s.world)) {
            for (Cell c : s.cells.values()) restore(p, w, c);
        }
        s.cells.clear();
        s.scanned = false;
    }
}
