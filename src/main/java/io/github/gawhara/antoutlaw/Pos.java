package io.github.gawhara.antoutlaw;

/** Packs a block position into one long (x: 26 bits, z: 26 bits, y: 12 bits with +2048 offset). */
final class Pos {
    private Pos() {}

    static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | ((y + 2048) & 0xFFFL);
    }
}
