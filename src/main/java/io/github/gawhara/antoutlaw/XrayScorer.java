package io.github.gawhara.antoutlaw;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Turns a window of mining records into a 0-100 suspicion score with human-readable reasons.
 * Pure logic (no Bukkit) so it can be unit tested.
 *
 * "Hidden ore" = an ore whose every open neighbour was opened by the player's own mining,
 * i.e. it was NOT visible from a cave/tunnel when the player started heading to it.
 */
final class XrayScorer {
    private XrayScorer() {}

    enum Kind { STONE, ORE_HIDDEN, ORE_EXPOSED, DECOY_EXPOSED }

    enum Level { NONE, LOW, MEDIUM, HIGH }

    record BreakRecord(long time, int x, int y, int z, String material, Kind kind) {}

    static final class Config {
        double ratioLow = 0.02, ratioHigh = 0.12;
        int minHidden = 5, minStone = 60;
        int low = 40, medium = 60, high = 80;
        double cellsPerBreak = 3.0, decoyDensity = 0.04;
    }

    static final class Result {
        int score;
        Level level = Level.NONE;
        final List<String> reasons = new ArrayList<>();
        int stone, hidden, exposed, turnedToward, decoyExposed, decoyBreaks;
        double ratio, perMinute, avgGap = -1, decoyExpected, spanMinutes;
        boolean enoughData;
        /** material -> [hidden, exposed] */
        final Map<String, int[]> perType = new TreeMap<>();
    }

    static Result score(List<BreakRecord> recs, Config c, int decoyBreaks) {
        Result r = new Result();
        r.decoyBreaks = decoyBreaks;
        List<BreakRecord> path = new ArrayList<>();
        long first = Long.MAX_VALUE, last = Long.MIN_VALUE;
        int stoneSince = 0;
        List<Integer> gaps = new ArrayList<>();

        for (BreakRecord b : recs) {
            switch (b.kind()) {
                case STONE -> { r.stone++; stoneSince++; }
                case ORE_HIDDEN -> {
                    r.hidden++;
                    r.perType.computeIfAbsent(b.material(), k -> new int[2])[0]++;
                    gaps.add(stoneSince);
                    stoneSince = 0;
                }
                case ORE_EXPOSED -> {
                    r.exposed++;
                    r.perType.computeIfAbsent(b.material(), k -> new int[2])[1]++;
                }
                case DECOY_EXPOSED -> r.decoyExposed++;
            }
            if (b.kind() != Kind.DECOY_EXPOSED) {
                path.add(b);
                first = Math.min(first, b.time());
                last = Math.max(last, b.time());
            }
        }
        r.spanMinutes = path.isEmpty() ? 0 : (last - first) / 60000.0;
        r.ratio = r.hidden / (double) Math.max(1, r.hidden + r.stone);
        r.perMinute = r.hidden / Math.max(3.0, r.spanMinutes);

        if (gaps.size() >= 3) {
            int from = Math.max(0, gaps.size() - 10);
            double sum = 0;
            for (int i = from; i < gaps.size(); i++) sum += gaps.get(i);
            r.avgGap = sum / (gaps.size() - from);
        }
        r.turnedToward = countTurnedToward(path);

        r.enoughData = r.hidden >= c.minHidden && r.stone >= c.minStone;
        double total = 0;

        if (r.enoughData) {
            double p1 = clamp((r.ratio - c.ratioLow) / (c.ratioHigh - c.ratioLow)) * 35;
            if (p1 > 1) {
                total += p1;
                r.reasons.add(String.format(Locale.US,
                        "Hidden-ore ratio %.1f%% (normal ≈ 0.5–2%%) [+%d]", r.ratio * 100, Math.round(p1)));
            }
            double p2 = clamp(r.perMinute / 1.0) * 15;
            if (p2 > 1) {
                total += p2;
                r.reasons.add(String.format(Locale.US,
                        "%.2f hidden valuable ores per minute [+%d]", r.perMinute, Math.round(p2)));
            }
            if (r.avgGap >= 0) {
                double p3 = clamp((40 - r.avgGap) / 30.0) * 15;
                if (p3 > 1) {
                    total += p3;
                    r.reasons.add(String.format(Locale.US,
                            "Only %.0f stone blocks between ores on average [+%d]", r.avgGap, Math.round(p3)));
                }
            }
            if (r.hidden >= 4) {
                double frac = r.turnedToward / (double) r.hidden;
                double p4 = clamp((frac - 0.3) / 0.5) * 15;
                if (p4 > 1) {
                    total += p4;
                    r.reasons.add(String.format(Locale.US,
                            "Changed direction straight toward the ore %d/%d times [+%d]",
                            r.turnedToward, r.hidden, Math.round(p4)));
                }
            }
        }

        r.decoyExpected = decoyBreaks * c.cellsPerBreak * c.decoyDensity;
        if (r.decoyExposed >= 3) {
            double ratio = r.decoyExposed / Math.max(0.5, r.decoyExpected);
            double p5 = clamp((ratio - 2) / 4.0) * 30;
            if (p5 > 1) {
                total += p5;
                r.reasons.add(String.format(Locale.US,
                        "Tunnelled to %d fake ores that exist only on their screen (chance ≈ %.1f) [+%d]",
                        r.decoyExposed, r.decoyExpected, Math.round(p5)));
            }
        }

        r.score = (int) Math.min(100, Math.round(total));
        r.level = r.score >= c.high ? Level.HIGH : r.score >= c.medium ? Level.MEDIUM
                : r.score >= c.low ? Level.LOW : Level.NONE;
        if (!r.enoughData && r.reasons.isEmpty()) {
            r.reasons.add("Not enough mining data yet (need " + c.minHidden + " hidden ores and "
                    + c.minStone + " stone blocks in the window).");
        }
        return r;
    }

    /**
     * Counts hidden ores where, within the last 10 breaks, the player changed heading and then
     * moved strictly closer to the ore for at least 3 steps.
     */
    static int countTurnedToward(List<BreakRecord> path) {
        int count = 0;
        for (int i = 0; i < path.size(); i++) {
            BreakRecord ore = path.get(i);
            if (ore.kind() != Kind.ORE_HIDDEN) continue;
            int from = Math.max(0, i - 10);
            List<BreakRecord> w = path.subList(from, i);
            if (w.size() < 5) continue;
            int lastChange = -1;
            int[] prev = null;
            for (int k = 0; k + 1 < w.size(); k++) {
                int[] d = {Integer.signum(w.get(k + 1).x() - w.get(k).x()),
                           Integer.signum(w.get(k + 1).y() - w.get(k).y()),
                           Integer.signum(w.get(k + 1).z() - w.get(k).z())};
                if (prev != null && !java.util.Arrays.equals(prev, d)) lastChange = k;
                prev = d;
            }
            if (lastChange < 0) continue;
            int steps = w.size() - lastChange;
            if (steps < 3) continue;
            boolean closer = true;
            double lastDist = Double.MAX_VALUE;
            for (int k = lastChange; k < w.size(); k++) {
                double dist = dist(w.get(k), ore);
                if (dist >= lastDist) { closer = false; break; }
                lastDist = dist;
            }
            if (closer && lastDist <= 8) count++;
        }
        return count;
    }

    private static double dist(BreakRecord a, BreakRecord b) {
        double dx = a.x() - b.x(), dy = a.y() - b.y(), dz = a.z() - b.z();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double clamp(double v) {
        return Math.max(0, Math.min(1, v));
    }
}
