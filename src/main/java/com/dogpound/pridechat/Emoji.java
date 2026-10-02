package com.dogpound.pridechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.util.ResourceLocation;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Full-colour emoji for a font that has none: 1,162 Twemoji pictures (CC-BY 4.0) in one sheet, found in text
 * either as :shortcodes: (what PrideChat's picker inserts, safe for any server) or as real emoji characters —
 * including the plain symbols (❤ ★ ✔ …) that PridePerms turns shortcodes into.
 */
public final class Emoji {
    private Emoji() {}

    public static final class E {
        public final int index, group;
        public final String unicode, label;
        public final List<String> codes;
        E(int index, String unicode, String label, int group, List<String> codes) {
            this.index = index; this.unicode = unicode; this.label = label; this.group = group; this.codes = codes;
        }
        public String code() { return codes.isEmpty() ? label : codes.get(0); }
    }

    public static final String[] GROUPS = {"Smileys", "People", "", "Animals", "Food", "Travel", "Activities", "Objects", "Symbols", "Flags"};
    public static final List<E> ALL = new ArrayList<>();
    private static final Map<String, E> BY_CODE = new HashMap<>();
    private static final Map<String, E> BY_CHARS = new HashMap<>();
    private static int size = 32, cols = 36, rows = 30, maxChars = 1;
    private static boolean loaded;
    private static final ResourceLocation SHEET = new ResourceLocation(PrideChat.MODID, "textures/emoji.png");

    static void load() {
        if (loaded) return;
        loaded = true;
        try (InputStreamReader r = new InputStreamReader(Minecraft.getMinecraft().getResourceManager()
                .getResource(new ResourceLocation(PrideChat.MODID, "emoji.json")).getInputStream(), StandardCharsets.UTF_8)) {
            JsonObject root = new JsonParser().parse(r).getAsJsonObject();
            size = root.get("size").getAsInt(); cols = root.get("cols").getAsInt(); rows = root.get("rows").getAsInt();
            JsonArray a = root.getAsJsonArray("emoji");
            for (int i = 0; i < a.size(); i++) {
                JsonObject o = a.get(i).getAsJsonObject();
                List<String> codes = new ArrayList<>();
                for (JsonElement c : o.getAsJsonArray("c")) codes.add(c.getAsString().toLowerCase(Locale.ROOT));
                E e = new E(i, o.get("u").getAsString(), o.get("n").getAsString(), o.get("g").isJsonNull() ? 8 : o.get("g").getAsInt(), codes);
                ALL.add(e);
                for (String c : codes) BY_CODE.putIfAbsent(c, e);
                String key = strip(e.unicode);
                BY_CHARS.putIfAbsent(key, e);
                maxChars = Math.max(maxChars, key.length());
            }
            // a few friendly extra names people type
            alias("heart", "red_heart"); alias("love", "heart"); alias("lol", "joy"); alias("pride", "rainbow_flag"); alias("trans", "transgender_flag");
        } catch (Exception ex) {
            PrideChat.LOG.warn("emoji list couldn't be read: " + ex);
        }
    }

    private static void alias(String name, String to) { if (!BY_CODE.containsKey(name) && BY_CODE.containsKey(to)) BY_CODE.put(name, BY_CODE.get(to)); }

    /** emoji characters without the "show as emoji" selector, so ❤ and ❤️ are the same */
    static String strip(String s) { return s.replace("️", ""); }

    public static E byCode(String code) { load(); return BY_CODE.get(code.toLowerCase(Locale.ROOT)); }

    public static List<E> search(String q, int group) {
        load();
        String s = q.toLowerCase(Locale.ROOT).trim();
        List<E> out = new ArrayList<>();
        for (E e : ALL) {
            if (group >= 0 && e.group != group) continue;
            if (!s.isEmpty() && !e.label.toLowerCase(Locale.ROOT).contains(s) && !String.join(" ", e.codes).contains(s)) continue;
            out.add(e);
        }
        return out;
    }

    // ------------------------------------------------------------------ finding them in text
    /** a piece of a line: text (with its § codes) or an emoji */
    public static final class Piece {
        public final String text, raw;                                    // raw = the characters the emoji replaced
        public final E emoji;
        Piece(String text, E emoji) { this(text, emoji, null); }
        Piece(String text, E emoji, String raw) { this.text = text; this.emoji = emoji; this.raw = raw; }
    }

    /** split formatted text into text and emoji pieces */
    public static List<Piece> split(String s) {
        load();
        List<Piece> out = new ArrayList<>();
        if (!ChatConfig.emoji || BY_CODE.isEmpty()) { out.add(new Piece(s, null)); return out; }
        StringBuilder buf = new StringBuilder();
        int i = 0, n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < n) { buf.append(c).append(s.charAt(i + 1)); i += 2; continue; }
            if (c == ':') {                                                  // :shortcode:
                int end = s.indexOf(':', i + 1);
                if (end > i + 1 && end - i <= 40) {
                    E e = BY_CODE.get(s.substring(i + 1, end).toLowerCase(Locale.ROOT));
                    if (e != null) { flush(out, buf); out.add(new Piece(null, e, s.substring(i, end + 1))); i = end + 1; continue; }
                }
            }
            if (c >= 0x2000) {                                               // a real emoji character (longest match)
                E best = null;
                int bestLen = 0;
                StringBuilder key = new StringBuilder();
                int j = i;
                while (j < n && key.length() < maxChars + 2) {
                    char d = s.charAt(j);
                    if (d == '§') break;
                    if (d != '️') key.append(d);
                    j++;
                    E e = BY_CHARS.get(key.toString());
                    if (e != null) { best = e; bestLen = j - i; }
                }
                if (best != null) {
                    int k = i + bestLen;
                    if (k < n && s.charAt(k) == '️') k++;
                    flush(out, buf);
                    out.add(new Piece(null, best, s.substring(i, k)));
                    i = k;
                    continue;
                }
            }
            buf.append(c);
            i++;
        }
        flush(out, buf);
        return out;
    }

    private static void flush(List<Piece> out, StringBuilder buf) {
        if (buf.length() > 0) { out.add(new Piece(buf.toString(), null)); buf.setLength(0); }
    }

    // ------------------------------------------------------------------ drawing
    public static void draw(E e, int x, int y, int px) {
        Minecraft.getMinecraft().getTextureManager().bindTexture(SHEET);
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.color(1f, 1f, 1f, 1f);
        int u = (e.index % cols) * size, v = (e.index / cols) * size;
        Gui.drawScaledCustomSizeModalRect(x, y, u, v, size, size, px, px, cols * size, rows * size);
    }
}
