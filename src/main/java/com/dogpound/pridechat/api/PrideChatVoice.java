package com.dogpound.pridechat.api;

import com.dogpound.pridechat.Router;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.common.MinecraftForge;

import java.util.*;

/**
 * The proximity engine for VOICE chat (roadmap #49), server side. A voice mod (e.g. a Simple Voice Chat plugin) asks
 * who hears a speaker and how loud — with exactly the same rules as PrideChat's text chat: whisper / local / shout
 * ranges, walls, floors, tunnels, water, per-dimension rules, rooms + soundproof rooms + any mod's
 * {@link PrideChatEvent.Deliver} hook, linked Nether portals, and radios / intercoms / phones.
 * PrideChat sends no audio itself. Cheap enough to call a few times a second per speaker; cache it between packets.
 * See docs/API.md → "Voice chat hook".
 */
public final class PrideChatVoice {
    public static final String WHISPER = Router.WHISPER, LOCAL = Router.LOCAL, SHOUT = Router.SHOUT;

    /** one listener: how loud (0-1], how muffled (0 clear, 1 through walls / a portal, 2 barely), and the way it got there */
    public static final class Heard {
        public final EntityPlayerMP listener;
        public final float volume;
        public final int band;
        /** "direct", "portal", or a device: "radio 7", "intercom A", "phone 555" ("!" in front = encrypted, play static) */
        public final String via;
        Heard(EntityPlayerMP listener, float volume, int band, String via) { this.listener = listener; this.volume = volume; this.band = band; this.via = via; }
    }

    /** blocks a voice reaches in this mode before walls etc. (the server's whisper / local / shout ranges) */
    public static int range(String mode) { return Router.rangeFor(mode, 0); }

    /** how loud `speaker` is for `listener` right now (0 = can't hear); devices not included */
    public static float volume(EntityPlayerMP speaker, EntityPlayerMP listener, String mode) {
        Heard h = hear(speaker, listener, mode, range(mode));
        return h == null ? 0 : h.volume;
    }

    /** everyone who hears `speaker` talking in this mode — directly, through a linked portal, or by radio / intercom / phone */
    public static List<Heard> listeners(EntityPlayerMP speaker, String mode) {
        List<Heard> out = new ArrayList<>();
        Set<EntityPlayerMP> got = new HashSet<>();
        int range = range(mode);
        for (EntityPlayerMP r : speaker.getServer().getPlayerList().getPlayers()) {
            if (r == speaker) continue;
            Heard h = hear(speaker, r, mode, range);
            if (h != null) { out.add(h); got.add(r); }
        }
        for (Map.Entry<EntityPlayerMP, String> e : Router.devicesFor(speaker, got).entrySet())
            out.add(new Heard(e.getKey(), e.getValue().startsWith("!") ? 0.5f : 0.8f, 1, e.getValue()));
        return out;
    }

    private static Heard hear(EntityPlayerMP p, EntityPlayerMP r, String mode, int range) {
        if (range <= 0 || r == p || r.isSpectator() && !p.isSpectator()) return null;
        Router.Msg m = new Router.Msg();
        m.sender = p; m.mode = mode; m.range = range; m.text = "";
        float dist = (float) Math.sqrt(r.getDistanceSq(p));
        if (r.dimension != p.dimension) {                                 // only through a linked portal
            if (!Router.portalLinked(p, r)) return null;
            PrideChatEvent.Deliver dv = new PrideChatEvent.Deliver(m, r, 1, dist);
            return MinecraftForge.EVENT_BUS.post(dv) ? null : new Heard(r, 0.35f, Math.max(1, dv.band), "portal");
        }
        double eff = Router.reach(p, r, range);
        if (dist > eff) return null;
        double f = dist / Math.max(0.001, eff);
        int band = f <= 0.6 ? 0 : f <= 0.85 ? 1 : 2;
        PrideChatEvent.Deliver dv = new PrideChatEvent.Deliver(m, r, band, dist);
        if (MinecraftForge.EVENT_BUS.post(dv)) return null;
        float vol = (float) (1 - f * 0.7) * (dv.band >= 2 ? 0.4f : dv.band == 1 ? 0.7f : 1f);
        return new Heard(r, Math.max(0.05f, Math.min(1f, vol)), dv.band, "direct");
    }

    private PrideChatVoice() {}
}
