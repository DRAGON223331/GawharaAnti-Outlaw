package io.github.gawhara.antoutlaw;

import io.github.gawhara.antoutlaw.XrayScorer.BreakRecord;
import io.github.gawhara.antoutlaw.XrayScorer.Kind;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Draws an "evidence board" PNG from server-side data: where the player dug, which ores they
 * reached, and where fake (decoy) ores were. It is a reconstruction, NOT a screenshot of their client.
 * Any failure (no fonts / no java.desktop module) returns null so the alert is still sent as text.
 */
final class EvidenceRenderer {
    private EvidenceRenderer() {}

    private static final int W = 1100, H = 690;
    private static final Color BG = new Color(0x1e1f22), PANEL = new Color(0x2b2d31),
            GRID = new Color(0x3a3c42), TEXT = new Color(0xdbdee1), MUTED = new Color(0x949ba4),
            STONE = new Color(0x6d717a), PATH = new Color(255, 255, 255, 60),
            DECOY = new Color(0xe91e8c);

    static byte[] render(String title, String subtitle, List<BreakRecord> records,
                         List<String> statLines, List<String> reasons) {
        try {
            System.setProperty("java.awt.headless", "true");
            BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(BG);
                g.fillRect(0, 0, W, H);

                Font base = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
                g.setFont(base.deriveFont(Font.BOLD, 22f));
                g.setColor(TEXT);
                g.drawString(clip(g, title, W - 40), 20, 36);
                g.setFont(base.deriveFont(14f));
                g.setColor(MUTED);
                g.drawString(clip(g, subtitle, W - 40), 20, 60);

                int[] bb = bounds(records);
                drawPanel(g, base, "Top view (X / Z)", 20, 80, 520, 440, records, bb, true);
                drawPanel(g, base, "Side view (X / Y)", 560, 80, 520, 440, records, bb, false);
                drawLegend(g, base, 20, 530);
                drawText(g, base, statLines, reasons, 20, 566);
            } finally {
                g.dispose();
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            if (!ImageIO.write(img, "png", out)) return null;
            return out.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** minX, maxX, minY, maxY, minZ, maxZ */
    private static int[] bounds(List<BreakRecord> recs) {
        int[] b = {Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE,
                   Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (BreakRecord r : recs) {
            b[0] = Math.min(b[0], r.x()); b[1] = Math.max(b[1], r.x());
            b[2] = Math.min(b[2], r.y()); b[3] = Math.max(b[3], r.y());
            b[4] = Math.min(b[4], r.z()); b[5] = Math.max(b[5], r.z());
        }
        if (recs.isEmpty()) return new int[]{0, 16, 0, 16, 0, 16};
        return b;
    }

    private static void drawPanel(Graphics2D g, Font base, String label, int px, int py, int pw, int ph,
                                  List<BreakRecord> recs, int[] bb, boolean top) {
        g.setColor(PANEL);
        g.fillRoundRect(px, py, pw, ph, 12, 12);
        g.setFont(base.deriveFont(Font.BOLD, 13f));
        g.setColor(MUTED);
        g.drawString(label, px + 12, py + 20);

        int hMin = bb[0], hMax = bb[1];
        int vMin = top ? bb[4] : bb[2], vMax = top ? bb[5] : bb[3];
        int pad = 3;
        hMin -= pad; hMax += pad; vMin -= pad; vMax += pad;
        int spanH = Math.max(16, hMax - hMin + 1), spanV = Math.max(16, vMax - vMin + 1);
        int ix = px + 14, iy = py + 32, iw = pw - 28, ih = ph - 64;
        double scale = Math.max(2.0, Math.min(40.0, Math.min(iw / (double) spanH, ih / (double) spanV)));

        // grid
        int step = spanH > 160 ? 32 : spanH > 80 ? 16 : spanH > 40 ? 8 : 4;
        g.setStroke(new BasicStroke(1f));
        g.setColor(GRID);
        for (int x = (hMin / step) * step; x <= hMax; x += step) {
            int sx = ix + (int) ((x - hMin) * scale);
            if (sx >= ix && sx <= ix + iw) g.drawLine(sx, iy, sx, iy + ih);
        }
        for (int v = (vMin / step) * step; v <= vMax; v += step) {
            int sy = iy + (int) (top ? (v - vMin) * scale : (vMax - v) * scale);
            if (sy >= iy && sy <= iy + ih) g.drawLine(ix, sy, ix + iw, sy);
        }

        g.setClip(ix - 2, iy - 2, iw + 4, ih + 4);
        double cell = Math.max(3, scale * 0.92);

        // path line
        g.setColor(PATH);
        g.setStroke(new BasicStroke(1.5f));
        int[] prev = null;
        for (BreakRecord r : recs) {
            if (r.kind() == Kind.DECOY_EXPOSED) continue;
            int[] p = project(r, hMin, vMin, vMax, ix, iy, scale, top);
            if (prev != null) g.drawLine(prev[0], prev[1], p[0], p[1]);
            prev = p;
        }
        // stone
        for (BreakRecord r : recs) {
            if (r.kind() != Kind.STONE) continue;
            int[] p = project(r, hMin, vMin, vMax, ix, iy, scale, top);
            g.setColor(STONE);
            g.fillRect(p[0] - (int) (cell / 2), p[1] - (int) (cell / 2), (int) cell, (int) cell);
        }
        // exposed ores (hollow) and hidden ores (filled)
        for (BreakRecord r : recs) {
            if (r.kind() != Kind.ORE_EXPOSED && r.kind() != Kind.ORE_HIDDEN) continue;
            int[] p = project(r, hMin, vMin, vMax, ix, iy, scale, top);
            int s = (int) Math.max(7, cell * 1.5);
            g.setColor(oreColor(r.material()));
            if (r.kind() == Kind.ORE_HIDDEN) {
                g.fillRect(p[0] - s / 2, p[1] - s / 2, s, s);
                g.setColor(Color.WHITE);
                g.setStroke(new BasicStroke(1.5f));
                g.drawRect(p[0] - s / 2, p[1] - s / 2, s, s);
            } else {
                g.setStroke(new BasicStroke(2f));
                g.drawRect(p[0] - s / 2, p[1] - s / 2, s, s);
            }
        }
        // decoys
        for (BreakRecord r : recs) {
            if (r.kind() != Kind.DECOY_EXPOSED) continue;
            int[] p = project(r, hMin, vMin, vMax, ix, iy, scale, top);
            int s = (int) Math.max(8, cell * 1.6);
            Polygon d = new Polygon(new int[]{p[0], p[0] + s, p[0], p[0] - s},
                                    new int[]{p[1] - s, p[1], p[1] + s, p[1]}, 4);
            g.setColor(DECOY);
            g.fillPolygon(d);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(1.5f));
            g.drawPolygon(d);
        }
        // start / end
        BreakRecord start = null, end = null;
        for (BreakRecord r : recs) {
            if (r.kind() == Kind.DECOY_EXPOSED) continue;
            if (start == null) start = r;
            end = r;
        }
        if (start != null) marker(g, base, project(start, hMin, vMin, vMax, ix, iy, scale, top), new Color(0x2ecc71), "S");
        if (end != null && end != start) marker(g, base, project(end, hMin, vMin, vMax, ix, iy, scale, top), new Color(0xe74c3c), "E");
        g.setClip(null);

        // axis labels
        g.setFont(base.deriveFont(11f));
        g.setColor(MUTED);
        String left = (top ? "X " : "X ") + hMin, right = hMax + "";
        g.drawString(left, ix, py + ph - 24);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(right, ix + iw - fm.stringWidth(right), py + ph - 24);
        String v1 = (top ? "Z " : "Y ") + vMin, v2 = (top ? "Z " : "Y ") + vMax;
        g.drawString(top ? v1 + " (north ↑)" : v2 + " (up ↑)", ix, py + ph - 8);
        String bottomRight = top ? v2 : v1;
        g.drawString(bottomRight, ix + iw - fm.stringWidth(bottomRight), py + ph - 8);
    }

    private static int[] project(BreakRecord r, int hMin, int vMin, int vMax, int ix, int iy, double scale, boolean top) {
        int sx = ix + (int) ((r.x() - hMin) * scale + scale / 2);
        int v = top ? r.z() : r.y();
        int sy = iy + (int) (top ? (v - vMin) * scale : (vMax - v) * scale);
        return new int[]{sx, sy};
    }

    private static void marker(Graphics2D g, Font base, int[] p, Color c, String letter) {
        g.setColor(c);
        g.fillOval(p[0] - 9, p[1] - 9, 18, 18);
        g.setColor(Color.WHITE);
        g.setFont(base.deriveFont(Font.BOLD, 12f));
        g.drawString(letter, p[0] - 4, p[1] + 4);
    }

    private static Color oreColor(String material) {
        String m = material.toUpperCase();
        if (m.contains("DIAMOND")) return new Color(0x4be8e0);
        if (m.contains("EMERALD")) return new Color(0x17dd62);
        if (m.contains("DEBRIS")) return new Color(0xc0763c);
        if (m.contains("GOLD")) return new Color(0xf5d142);
        if (m.contains("LAPIS")) return new Color(0x3b5bdb);
        if (m.contains("REDSTONE")) return new Color(0xe03131);
        if (m.contains("IRON")) return new Color(0xd9b99b);
        return new Color(0xf59f00);
    }

    private static void drawLegend(Graphics2D g, Font base, int x, int y) {
        g.setFont(base.deriveFont(12f));
        int cx = x;
        cx = legendItem(g, cx, y, STONE, false, "stone broken");
        cx = legendItem(g, cx, y, new Color(0x4be8e0), true, "hidden ore (was not visible)");
        cx = legendItem(g, cx, y, new Color(0x4be8e0), false, "exposed ore (cave / already visible)");
        legendDecoy(g, cx, y, "fake ore only on their client");
    }

    private static int legendItem(Graphics2D g, int x, int y, Color c, boolean filled, String label) {
        g.setColor(c);
        if (filled) g.fillRect(x, y, 12, 12);
        else { g.setStroke(new BasicStroke(2f)); g.drawRect(x, y, 12, 12); }
        g.setColor(MUTED);
        g.drawString(label, x + 18, y + 11);
        return x + 18 + g.getFontMetrics().stringWidth(label) + 22;
    }

    private static void legendDecoy(Graphics2D g, int x, int y, String label) {
        g.setColor(DECOY);
        g.fillPolygon(new int[]{x + 6, x + 12, x + 6, x}, new int[]{y, y + 6, y + 12, y + 6}, 4);
        g.setColor(MUTED);
        g.drawString(label, x + 18, y + 11);
    }

    private static void drawText(Graphics2D g, Font base, List<String> stats, List<String> reasons, int x, int y) {
        g.setFont(base.deriveFont(Font.BOLD, 13f));
        g.setColor(TEXT);
        g.drawString("Statistics", x, y);
        g.drawString("Why flagged", x + 540, y);
        g.setFont(base.deriveFont(13f));
        g.setColor(MUTED);
        int ly = y + 20;
        for (String s : stats) {
            if (ly > H - 8) break;
            g.drawString(clip(g, s, 520), x, ly);
            ly += 18;
        }
        ly = y + 20;
        for (String s : reasons) {
            if (ly > H - 8) break;
            g.drawString(clip(g, "• " + s, 520), x + 540, ly);
            ly += 18;
        }
    }

    private static String clip(Graphics2D g, String s, int maxW) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= maxW) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }
}
