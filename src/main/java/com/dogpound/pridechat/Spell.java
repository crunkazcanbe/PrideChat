package com.dogpound.pridechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.util.ResourceLocation;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * A simple spell check for the chat input: ~275,000 English words (words.txt.gz, MIT) + your own words + everyone
 * online. Capitalised words (names), :emoji:, links, &codes and words with digits are left alone. Loaded on first use
 * in the background, so the chat never waits for it.
 */
final class Spell {
    private static volatile Set<String> words;
    private static boolean loading;
    private static final Pattern WORD = Pattern.compile("(?<![&§:/\\w])[A-Za-z']{3,}(?![\\w:/])");

    private static void load() {
        if (loading) return;
        loading = true;
        Thread t = new Thread(() -> {
            Set<String> s = new HashSet<>(300_000);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Minecraft.getMinecraft().getResourceManager()
                    .getResource(new ResourceLocation(PrideChat.MODID, "words.txt.gz")).getInputStream()), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) s.add(line);
            } catch (Exception e) { PrideChat.LOG.warn("spell check word list: " + e); }
            words = s;
        }, "PrideChat-spell");
        t.setDaemon(true);
        t.start();
    }

    static boolean known(String w) {
        String l = w.toLowerCase(Locale.ROOT).replace("'", "");
        if (words.contains(l) || l.endsWith("s") && words.contains(l.substring(0, l.length() - 1))) return true;
        for (String m : ChatConfig.myWords) if (m.trim().equalsIgnoreCase(w)) return true;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getConnection() != null) for (NetworkPlayerInfo i : mc.getConnection().getPlayerInfoMap()) if (i.getGameProfile().getName().equalsIgnoreCase(w)) return true;
        return false;
    }

    /** {start, end} of every misspelled word in the text */
    static List<int[]> check(String text) {
        if (!ChatConfig.spellCheck || text.startsWith("/")) return Collections.emptyList();
        if (words == null) { load(); return Collections.emptyList(); }
        List<int[]> out = new ArrayList<>();
        Matcher m = WORD.matcher(text);
        while (m.find()) {
            String w = m.group().replaceAll("^'+|'+$", "");
            if (w.length() < 3 || Character.isUpperCase(w.charAt(0)) && !w.equals(w.toUpperCase(Locale.ROOT))) continue;   // Names
            if (w.equals(w.toUpperCase(Locale.ROOT)) && w.length() <= 5) continue;                                          // LOL, BRB
            if (!known(w)) out.add(new int[]{m.start(), m.end()});
        }
        return out;
    }

    private Spell() {}
}
