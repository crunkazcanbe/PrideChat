package com.dogpound.pridechat;

import com.dogpound.pridechat.api.PrideChatEvent;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.*;

/**
 * NPCs that talk back (AI, Requested): any mob with the scoreboard tag "pridechat_ai" — made for Custom NPCs
 * (/tag @e[type=customnpcs:customnpc,name=Mira] add pridechat_ai), but any named mob works — answers spoken chat near
 * it in character through PrideMind / Buddy Builder's model. Named in the message = that NPC answers, else the nearest
 * one within npcHearRange. Rate-limited per NPC and per player, plus the global AI budget; never on the game thread.
 * Personas: config ai.npcPersonas "Name | who they are"; NPCs remember the last few lines of their conversation.
 */
public final class NpcChat {
    private static final Map<UUID, Long> NPC_LAST = new HashMap<>(), PLAYER_LAST = new HashMap<>();
    private static final Map<UUID, LinkedList<String>> MEMORY = new HashMap<>();

    @SubscribeEvent
    public static void talked(PrideChatEvent.Talked e) {
        Router.Msg m = e.msg;
        ChatConfig.Ai c = ChatConfig.ai;
        if (!c.enabled || !c.npcs || m.sender == null || m.range <= 0 || m.shadow || Router.DM.equals(m.mode)) return;
        EntityPlayerMP p = m.sender;
        long now = System.currentTimeMillis();
        Long pl = PLAYER_LAST.get(p.getUniqueID());
        if (pl != null && now - pl < 3000) return;
        double hear = Math.min(m.range, c.npcHearRange);
        EntityLiving best = null;
        double bd = Double.MAX_VALUE;
        String low = m.text.toLowerCase(Locale.ROOT);
        for (Entity en : p.world.getEntitiesWithinAABB(EntityLiving.class, p.getEntityBoundingBox().grow(hear))) {
            if (!en.getTags().contains(c.npcTag) || !en.isEntityAlive()) continue;
            double d = en.getDistanceSq(p);
            if (d > hear * hear) continue;
            if (low.contains(en.getName().toLowerCase(Locale.ROOT))) d -= 1e6;      // named = it answers
            if (d < bd) { bd = d; best = (EntityLiving) en; }
        }
        if (best == null) return;
        Long nl = NPC_LAST.get(best.getUniqueID());
        if (nl != null && now - nl < c.npcCooldownSeconds * 1000L) return;
        NPC_LAST.put(best.getUniqueID(), now);
        PLAYER_LAST.put(p.getUniqueID(), now);
        EntityLiving npc = best;
        String name = npc.getName();
        LinkedList<String> mem = MEMORY.computeIfAbsent(npc.getUniqueID(), k -> new LinkedList<>());
        mem.add(p.getName() + ": " + m.text);
        while (mem.size() > 8) mem.removeFirst();
        String system = "You are " + name + ", " + persona(name) + " You live in a Minecraft 1.12.2 world and talk with players who walk up to you. "
                + "Stay in character. Answer in one or two short sentences, plain text, no quotes, no markdown, never say you are an AI.";
        String prompt = "Recent conversation:\n" + String.join("\n", mem) + "\n\nReply as " + name + " to " + p.getName() + ".";
        npc.getLookHelper().setLookPositionWithEntity(p, 30, 30);
        AI.ask("npc", system, prompt, 120, 20000).thenAccept(a -> {
            if (a == null || a.trim().isEmpty()) return;
            String reply = a.trim().replaceAll("^\\\"|\\\"$", "").replaceAll("(?i)^" + java.util.regex.Pattern.quote(name) + ":\\s*", "");
            if (reply.length() > 400) reply = reply.substring(0, 400) + "…";
            String r = reply;
            Router.server().addScheduledTask(() -> say(npc, r, mem));
        });
    }

    static String persona(String name) {
        for (String s : ChatConfig.ai.npcPersonas) {
            String[] q = s.split("\\s*\\|\\s*", 2);
            if (q.length == 2 && q[0].trim().equalsIgnoreCase(name)) return q[1].trim();
        }
        return "a friendly local in this town.";
    }

    /** the NPC says it out loud: a local line (and a bubble above it) for the players near it */
    static void say(EntityLiving npc, String text, LinkedList<String> mem) {
        if (!npc.isEntityAlive()) return;
        mem.add(npc.getName() + ": " + text);
        while (mem.size() > 8) mem.removeFirst();
        Router.Msg m = new Router.Msg();
        m.mode = Router.LOCAL; m.text = text; m.range = Router.rangeFor(Router.LOCAL, 0);
        ITextComponent line = new TextComponentString(Router.colors(ChatConfig.server.localTag) + "<" + npc.getName() + "> ").appendSibling(new TextComponentString(text));
        String json = ITextComponent.Serializer.componentToJson(line);
        for (EntityPlayerMP r : Router.server().getPlayerList().getPlayers()) {
            if (r.dimension != npc.dimension || r.getDistance(npc) > m.range) continue;
            if (!Router.hasPrideChat(r)) { r.sendMessage(line); continue; }
            NBTTagCompound t = Router.meta(m, 0, r.getDistance(npc), false);
            t.setString("s", npc.getName());
            t.setBoolean("npc", true);
            t.setInteger("dim", npc.dimension);
            t.setFloat("x", (float) npc.posX); t.setFloat("y", (float) npc.posY); t.setFloat("z", (float) npc.posZ);
            Net.send(r, Net.S_LINE, t, json);
        }
    }

    private NpcChat() {}
}
