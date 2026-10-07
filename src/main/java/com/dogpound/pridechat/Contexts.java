package com.dogpound.pridechat;

import com.dogpound.pridechat.api.PrideChatEvent;
import net.minecraft.block.material.Material;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;

import java.util.*;

/**
 * Context-aware chat (roadmap #25): how you are changes how you sound. Underwater "Hllooo… *blub*", on fire "AH! hot!",
 * sprinting "wait… up… *huff*", riding "he-ey (bumpy)", badly hurt "help… *cough*". Other mods add their own contexts
 * (or remove ours) through {@link PrideChatEvent.Context}; only the built-in ones listed in contextEffects change text.
 */
final class Contexts {
    static void apply(Router.Msg m) {
        EntityPlayerMP p = m.sender;
        if (p == null || m.range <= 0) return;                             // only spoken (ranged) talk
        List<String> ctx = new ArrayList<>();
        if (p.isInsideOfMaterial(Material.WATER)) ctx.add("underwater");
        if (p.isBurning() && !p.isImmuneToFire()) ctx.add("fire");
        if (p.isSprinting()) ctx.add("sprint");
        if (p.isRiding()) ctx.add("riding");
        if (p.getHealth() <= p.getMaxHealth() * 0.25f) ctx.add("hurt");
        if (p.isSneaking()) ctx.add("sneak");
        PrideChatEvent.Context ev = new PrideChatEvent.Context(m, ctx);
        MinecraftForge.EVENT_BUS.post(ev);
        List<String> on = Arrays.asList(ChatConfig.server.contextEffects);
        Random r = new Random(m.text.hashCode() ^ p.getEntityId());
        for (String c : ev.contexts) {
            if (!on.contains(c)) continue;
            switch (c) {
                case "underwater": m.text = blub(m.text, r) + " *blub*"; break;
                case "fire": m.text = "AH! " + m.text.replaceAll("[.?]*$", "") + "!! :fire:"; break;
                case "sprint": m.text = pant(m.text, r) + " *huff*"; break;
                case "riding": m.text = bumpy(m.text, r); break;
                case "hurt": m.text = m.text.toLowerCase(Locale.ROOT).replaceAll("[.!?]*$", "") + "… *cough*"; break;
                default: break;
            }
        }
    }

    private static boolean vowel(char c) { return "aeiouAEIOU".indexOf(c) >= 0; }

    /** "Hello there" → "Hllooo thrree": the first vowel of a word goes, the last one stretches */
    static String blub(String s, Random r) {
        StringBuilder out = new StringBuilder();
        for (String w : s.split("(?<= )")) {
            int first = -1, last = -1;
            for (int i = 0; i < w.length(); i++) if (vowel(w.charAt(i))) { if (first < 0) first = i; last = i; }
            for (int i = 0; i < w.length(); i++) {
                char c = w.charAt(i);
                if (i == first && first != last && w.trim().length() > 3) continue;
                out.append(c);
                if (i == last) for (int k = 1 + r.nextInt(3); k > 0; k--) out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }

    /** out of breath: "…" between some words */
    static String pant(String s, Random r) {
        String[] w = s.split(" ");
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < w.length; i++) {
            out.append(w[i]);
            if (i < w.length - 1) out.append(r.nextInt(3) == 0 ? "… " : " ");
        }
        return out.toString();
    }

    /** bumpy ride: some words stutter "he-hey" */
    static String bumpy(String s, Random r) {
        StringBuilder out = new StringBuilder();
        for (String w : s.split("(?<= )")) {
            if (w.trim().length() > 2 && Character.isLetter(w.charAt(0)) && r.nextInt(3) == 0) out.append(w, 0, Math.min(2, w.length())).append('-');
            out.append(w);
        }
        return out.toString();
    }

    private Contexts() {}
}
