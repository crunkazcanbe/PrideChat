package com.dogpound.pridechat;

import net.minecraft.command.ICommandSender;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.scoreboard.Team;
import net.minecraft.util.math.Vec3d;

import java.util.*;

/**
 * Chat targeting selectors for /msg and /announce (roadmap #45): @nearest @here @all @team @party @radius N.
 * (Vanilla's @a @p @r @e and plain names still work where the command allows them.)
 */
final class Selectors {
    static final String[] ALL = {"@nearest", "@here", "@all", "@team", "@party", "@radius"};

    static boolean is(String token) { return Arrays.asList(ALL).contains(token.toLowerCase(Locale.ROOT)); }

    /**
     * the players a selector means, seen from `who` (never including `who`); a[at] is the selector, @radius also takes
     * a[at+1]. Returns {players, number of words used} — or null when a[at] isn't one of ours.
     */
    static Object[] resolve(ICommandSender who, String[] a, int at) {
        if (at >= a.length || !is(a[at])) return null;
        String s = a[at].toLowerCase(Locale.ROOT);
        int used = 1;
        List<EntityPlayerMP> all = Router.server().getPlayerList().getPlayers(), out = new ArrayList<>();
        EntityPlayerMP me = who instanceof EntityPlayerMP ? (EntityPlayerMP) who : null;
        Vec3d pos = who.getPositionVector();
        int dim = who.getEntityWorld() == null ? 0 : who.getEntityWorld().provider.getDimension();
        double radius = -1;
        switch (s) {
            case "@all": for (EntityPlayerMP r : all) if (r != me) out.add(r); break;
            case "@here": radius = Router.rangeFor(Router.LOCAL, 0); break;
            case "@radius":
                try { radius = Double.parseDouble(a[at + 1]); used = 2; } catch (Exception e) { radius = Router.rangeFor(Router.LOCAL, 0); }
                break;
            case "@nearest": {
                EntityPlayerMP best = null;
                double bd = Double.MAX_VALUE;
                for (EntityPlayerMP r : all) {
                    if (r == me || r.dimension != dim) continue;
                    double d = r.getPositionVector().squareDistanceTo(pos);
                    if (d < bd) { bd = d; best = r; }
                }
                if (best != null) out.add(best);
                break;
            }
            case "@team": {
                Team t = me == null ? null : me.getTeam();
                if (t != null) for (EntityPlayerMP r : all) if (r != me && t.isSameTeam(r.getTeam())) out.add(r);
                break;
            }
            case "@party": {
                Channels.Ch g = me == null ? null : Channels.groupOf(me);
                if (g != null) for (EntityPlayerMP r : all) if (r != me && g.members.contains(r.getUniqueID())) out.add(r);
                break;
            }
            default: break;
        }
        if (radius >= 0) for (EntityPlayerMP r : all) if (r != me && r.dimension == dim && r.getPositionVector().squareDistanceTo(pos) <= radius * radius) out.add(r);
        return new Object[]{out, used};
    }

    private Selectors() {}
}
