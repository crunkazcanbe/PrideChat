package com.dogpound.pridechat;

import net.minecraft.client.gui.FontRenderer;

/**
 * How new chat lines and bubbles arrive (roadmap #8): fade, slide, pop, bounce, typewriter, scale, dissolve, enchant,
 * hologram, float — at a speed you pick. Motion kinds move/scale the whole row or bubble; letter kinds (typewriter,
 * dissolve, enchant) reveal the text piece by piece; hologram flickers cyan with scan lines while it settles.
 * Client only, pure maths on the render thread.
 */
final class Anim {
    static final String[] KINDS = {"none", "fade", "slide", "pop", "bounce", "typewriter", "scale", "dissolve", "enchant", "hologram", "float"};

    static boolean letters(String kind) { return "typewriter".equals(kind) || "dissolve".equals(kind) || "enchant".equals(kind); }

    /** how long an entrance lasts, ms (letter kinds take longer for longer text) */
    static long duration(String kind, int len) {
        long d = letters(kind) ? Math.min(3000, 200 + len * 28L) : "bounce".equals(kind) || "hologram".equals(kind) ? 600 : 350;
        return d * 100 / Math.max(10, ChatConfig.animSpeed);
    }

    /** 0 → 1 over the entrance (1 = settled) */
    static float progress(String kind, long age, int len) {
        if (kind == null || kind.isEmpty() || "none".equals(kind)) return 1;
        long d = duration(kind, len);
        return age >= d ? 1 : Math.max(0, age / (float) d);
    }

    /** {dx, dy, scaleX, scaleY, alpha 0-1} for a box `w` wide and `h` tall at progress p */
    static float[] motion(String kind, float p, int w, int h) {
        float[] m = {0, 0, 1, 1, 1};
        if (p >= 1) return m;
        float e = 1 - (1 - p) * (1 - p) * (1 - p);                         // ease out
        switch (kind) {
            case "fade": m[4] = p; break;
            case "slide": m[0] = -(1 - e) * Math.min(80, w / 3f); m[4] = Math.min(1, p * 2); break;
            case "pop": { float s = p < 0.7f ? 0.5f + 0.6f * p / 0.7f : 1.1f - 0.1f * (p - 0.7f) / 0.3f; m[2] = m[3] = s; m[4] = Math.min(1, p * 3); break; }
            case "bounce": m[1] = -(float) Math.abs(Math.sin(p * Math.PI * 2.5)) * (1 - p) * h * 0.9f; break;
            case "scale": m[2] = e; m[4] = Math.min(1, p * 2); break;
            case "float": m[1] = (1 - e) * h * 1.6f; m[4] = p; break;
            case "hologram": m[4] = (float) (0.55 + 0.45 * Math.abs(Math.sin(p * 40))) * (0.4f + 0.6f * p); break;
            default: break;
        }
        return m;
    }

    /** is letter gi still hidden at progress p? (for emoji / pieces that aren't text) */
    static boolean hidden(String kind, float p, int gi, int len, long seed) {
        if (p >= 1 || !letters(kind)) return false;
        return "dissolve".equals(kind) ? hash(gi, seed) >= p : gi >= (int) (p * len);
    }

    /**
     * Draw a piece of text with the sender's effects (roadmap #47): glow (a soft pulsing halo in the text's colour),
     * shadow (a long drop shadow), color (rainbow colours that move along the letters). gi = its first letter's index.
     */
    static void text(FontRenderer fr, String s, int x, int y, int argb, boolean shadow, String fx, int gi) {
        if (fx == null || fx.isEmpty() || s.isEmpty()) {
            if (shadow) fr.drawStringWithShadow(s, x, y, argb); else fr.drawString(s, x, y, argb);
            return;
        }
        int a = argb >>> 24;
        String plain = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(s);
        long now = System.currentTimeMillis();
        if (fx.contains("shadow")) fr.drawString(plain, x + 2, y + 2, Math.max(4, a * 150 / 255) << 24);
        if (fx.contains("glow")) {
            int g = 0xF5A9B8;
            for (int i = 0; i + 1 < s.length(); i++) if (s.charAt(i) == '§' && "0123456789abcdef".indexOf(Character.toLowerCase(s.charAt(i + 1))) >= 0) {
                g = fr.getColorCode(Character.toLowerCase(s.charAt(i + 1))); break;
            }
            float pulse = 0.65f + 0.35f * (float) Math.sin(now / 300.0);
            int ga = Math.max(4, (int) (a * 0.32f * pulse)) << 24 | (g & 0xFFFFFF);
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++) if (dx != 0 || dy != 0) fr.drawString(plain, x + dx, y + dy, ga);
        }
        if (fx.contains("color")) {
            int cx = x;
            for (int i = 0; i < plain.length(); i++) {
                char c = plain.charAt(i);
                int col = java.awt.Color.HSBtoRGB((now / 1800f + (gi + i) * 0.045f) % 1f, 0.55f, 1f) & 0xFFFFFF;
                String one = String.valueOf(c);
                if (shadow) fr.drawStringWithShadow(one, cx, y, Math.max(4, a) << 24 | col); else fr.drawString(one, cx, y, Math.max(4, a) << 24 | col);
                cx += fr.getCharWidth(c);
            }
            return;
        }
        if (shadow && !fx.contains("shadow")) fr.drawStringWithShadow(s, x, y, argb); else fr.drawString(s, x, y, argb);
    }

    private static float hash(int i, long seed) {
        long x = (i * 0x9E3779B97F4A7C15L) ^ seed;
        x ^= x >>> 31; x *= 0xBF58476D1CE4E5B9L; x ^= x >>> 29;
        return (x >>> 40) / (float) (1L << 24);
    }

    /**
     * A drawn piece of text (may hold § codes) whose first letter is letter `start` of a `len`-letter message, revealed
     * to progress p. typewriter: letters past the cursor are left out; dissolve: hidden letters (random order) are
     * scrambled §k glyphs; enchant: a purple §k sweep turns into the real text left to right. Returns s when settled.
     */
    static String reveal(String s, String kind, float p, int start, int len, long seed) {
        if (p >= 1 || !letters(kind)) return s;
        int shown = (int) (p * len);
        StringBuilder out = new StringBuilder(s.length() + 16);
        int gi = start;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) { out.append(c).append(s.charAt(++i)); continue; }
            boolean hidden = "dissolve".equals(kind) ? hash(gi, seed) >= p : gi >= shown;
            gi++;
            if (!hidden || c == ' ') { out.append(c); continue; }
            if ("typewriter".equals(kind)) break;
            String fmt = FontRenderer.getFormatFromString(out.toString());
            out.append("enchant".equals(kind) ? "§d§k" : "§k").append(c).append("§r").append(fmt);
        }
        return out.toString();
    }

    private Anim() {}
}
