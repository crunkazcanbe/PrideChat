package com.dogpound.pridechat;

import com.dogpound.pridechat.api.PrideChatEvent;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumParticleTypes;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Emotes that DO something (roadmap #23/#48): "/emote waves" swings the arm, "laughs" sparkles, "screams" shakes the
 * screen of everyone who heard it, "whispers …" is a tiny bubble only close people hear, "shouts …" a giant one heard
 * far away. The table is the server config emoteEffects: regex | effects, effects = wave, clap, point, dance, swing,
 * particles:heart|note|happy|angry|splash|cloud|magic|flame, shake:N, tiny, giant, range:whisper|shout.
 */
public final class Emotes {
    private static final Map<String, Pattern> CACHE = new HashMap<>();
    /** arm swings still to play: {player, hands pattern, every N ticks, left, next tick} */
    private static final List<Object[]> SWINGS = new ArrayList<>();

    /** the effects for this action text (all matching lines) */
    static List<String> effects(String text) {
        List<String> out = new ArrayList<>();
        for (String line : ChatConfig.server.emoteEffects) {
            String[] p = line.split("\\s*\\|\\s*", 2);
            if (p.length < 2) continue;
            Pattern re = CACHE.computeIfAbsent(p[0].trim(), k -> { try { return Pattern.compile(k, Pattern.CASE_INSENSITIVE); } catch (Exception e) { return null; } });
            if (re == null || !re.matcher(text).find()) continue;
            for (String e : p[1].split("\\s*,\\s*")) if (!e.isEmpty()) out.add(e.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }

    /** before routing: range + bubble size (whispers → tiny + whisper range, shouts → giant + shout range) */
    static void prepare(Router.Msg m) {
        for (String e : effects(m.text)) {
            if ("tiny".equals(e)) m.volume = 0;
            else if ("giant".equals(e)) m.volume = 3;
            else if ("range:whisper".equals(e)) m.range = Router.rangeFor(Router.WHISPER, 0);
            else if ("range:shout".equals(e)) m.range = Router.rangeFor(Router.SHOUT, 0);
        }
    }

    /** after an emote went out: animations, particles, screen shake for the people who heard it */
    @SubscribeEvent
    public static void talked(PrideChatEvent.Talked ev) {
        Router.Msg m = ev.msg;
        if (!Router.EMOTE.equals(m.mode) || m.sender == null) return;
        EntityPlayerMP p = m.sender;
        List<String> fx = effects(m.text);
        if (fx.isEmpty()) fx = Collections.singletonList("swing");
        for (String e : fx) {
            String k = e.contains(":") ? e.substring(0, e.indexOf(':')) : e, v = e.contains(":") ? e.substring(e.indexOf(':') + 1) : "";
            switch (k) {
                case "swing": p.swingArm(EnumHand.MAIN_HAND); break;
                case "wave": swing(p, "M", 5, 5); break;
                case "clap": swing(p, "MO", 3, 8); break;
                case "dance": swing(p, "MO", 5, 10); particles(p, "note"); break;
                case "point": {
                    p.swingArm(EnumHand.MAIN_HAND);
                    Vec3d eye = p.getPositionEyes(1), look = p.getLook(1);
                    for (int i = 1; i <= 8; i++) {
                        Vec3d at = eye.add(look.scale(i * 0.6));
                        p.getServerWorld().spawnParticle(EnumParticleTypes.CRIT_MAGIC, at.x, at.y, at.z, 2, 0.02, 0.02, 0.02, 0);
                    }
                    break;
                }
                case "particles": if (ChatConfig.server.emoteParticles) particles(p, v); break;
                case "shake": {
                    int n = 6;
                    try { n = Math.max(1, Math.min(20, Integer.parseInt(v))); } catch (NumberFormatException ignored) {}
                    NBTTagCompound t = new NBTTagCompound();
                    t.setString("kind", "shake"); t.setString("s", p.getName()); t.setInteger("n", n);
                    for (EntityPlayerMP r : ev.heard) if (r != p && Router.hasPrideChat(r)) Net.send(r, Net.S_EFFECT, t, "");
                    break;
                }
                default: break;
            }
        }
    }

    static void particles(EntityPlayerMP p, String kind) {
        EnumParticleTypes type;
        switch (kind) {
            case "heart": type = EnumParticleTypes.HEART; break;
            case "note": type = EnumParticleTypes.NOTE; break;
            case "happy": type = EnumParticleTypes.VILLAGER_HAPPY; break;
            case "angry": type = EnumParticleTypes.VILLAGER_ANGRY; break;
            case "splash": type = EnumParticleTypes.WATER_SPLASH; break;
            case "cloud": type = EnumParticleTypes.CLOUD; break;
            case "flame": type = EnumParticleTypes.FLAME; break;
            default: type = EnumParticleTypes.SPELL_WITCH;
        }
        p.getServerWorld().spawnParticle(type, p.posX, p.posY + p.height + 0.3, p.posZ, 8, 0.4, 0.3, 0.4, type == EnumParticleTypes.CLOUD ? 0.01 : 0.05);
    }

    /** swing these hands (M main, O off) in turn, one every `every` ticks, `times` times */
    static void swing(EntityPlayerMP p, String hands, int every, int times) {
        SWINGS.removeIf(s -> s[0] == p);
        SWINGS.add(new Object[]{p, hands, every, times, 0});
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || SWINGS.isEmpty()) return;
        for (Iterator<Object[]> it = SWINGS.iterator(); it.hasNext(); ) {
            Object[] s = it.next();
            EntityPlayerMP p = (EntityPlayerMP) s[0];
            int wait = (int) s[4];
            if (wait > 0) { s[4] = wait - 1; continue; }
            int left = (int) s[3];
            if (left <= 0 || p.isDead || p.hasDisconnected()) { it.remove(); continue; }
            String hands = (String) s[1];
            p.swingArm(hands.charAt(left % hands.length()) == 'O' ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND);
            s[3] = left - 1;
            s[4] = (int) s[2] - 1;
        }
    }

    private Emotes() {}
}
