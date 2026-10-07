package com.dogpound.pridechat;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppedEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.server.permission.DefaultPermissionLevel;
import net.minecraftforge.server.permission.PermissionAPI;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.function.Consumer;

/**
 * PrideChat (requested feature): a prettier, tabbed chat with emoji, speech bubbles + floating heads,
 * unlimited message length, local / whisper / shout / global / channel / party chat, DMs with ✓✓, mail, moderation
 * and optional AI helpers. Works on ANY server (client features only); with PrideChat on the server too you get the
 * ranged chat, channels, long messages in one piece, typing dots, reactions and read ticks.
 */
@Mod(modid = PrideChat.MODID, name = "PrideChat", version = PrideChat.VERSION, acceptableRemoteVersions = "*")
public class PrideChat {
    public static final String MODID = "pridechat", VERSION = "0.4.1";
    public static final Logger LOG = LogManager.getLogger("PrideChat");
    /** set by ClientSide on a client; a dedicated server never loads any client class */
    static Consumer<Net.Pkt> clientPackets;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        Net.init();
        MinecraftForge.EVENT_BUS.register(Devices.class);
        MinecraftForge.EVENT_BUS.register(Rooms.class);
        MinecraftForge.EVENT_BUS.register(Emotes.class);
        MinecraftForge.EVENT_BUS.register(NpcChat.class);
        MinecraftForge.EVENT_BUS.register(CrossServer.class);
        if (e.getSide().isClient()) {
            try { Class.forName("com.dogpound.pridechat.ClientSide").getMethod("init").invoke(null); }
            catch (Throwable t) { LOG.error("client side didn't start", t); }
        }
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
        PermissionAPI.registerNode("pridechat.color", DefaultPermissionLevel.ALL, "Use &colour codes in chat");
        PermissionAPI.registerNode("pridechat.shout", DefaultPermissionLevel.ALL, "Shout (long range)");
        PermissionAPI.registerNode("pridechat.global.always", DefaultPermissionLevel.OP, "Talk to everyone even when global chat is off");
        PermissionAPI.registerNode("pridechat.mention.everyone", DefaultPermissionLevel.OP, "Ping everyone with @everyone");
        PermissionAPI.registerNode("pridechat.channel.staff", DefaultPermissionLevel.OP, "Staff channel + moderation notices");
        PermissionAPI.registerNode("pridechat.anonymous", DefaultPermissionLevel.OP, "/anon messages");
        PermissionAPI.registerNode("pridechat.pa", DefaultPermissionLevel.OP, "Use a PA system block (server-wide announcement)");
        PermissionAPI.registerNode("pridechat.zones", DefaultPermissionLevel.OP, "Make zones (talk to everyone in a town / area with /zone)");
        PermissionAPI.registerNode("pridechat.appearance", DefaultPermissionLevel.ALL, "Own bubble colour / border / lifetime / animation and head size (roadmap #7)");
        PermissionAPI.registerNode("pridechat.appearance.colors", DefaultPermissionLevel.OP, "Own name / message / channel-tag colours");
        PermissionAPI.registerNode("pridechat.effects", DefaultPermissionLevel.ALL, "Text effects [glow] [shadow] [color] [type] (roadmap #47)");
        PermissionAPI.registerNode("pridechat.nick", DefaultPermissionLevel.OP, "/chatnick disguises");
        LOG.info("[PrideChat] loaded OK");
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent e) { ChatCommands.register(e); Router.loadHistory(); CrossServer.start(); }

    @Mod.EventHandler
    public void serverStopping(net.minecraftforge.fml.common.event.FMLServerStoppingEvent e) { Router.saveHistory(); Stats.snapshot(); CrossServer.stop(); }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent e) { Mail.reset(); Router.HISTORY.clear(); Stats.reset(); }

    /** chat history to disk every 5 minutes (when something was said) */
    @SubscribeEvent
    public void tick(net.minecraftforge.fml.common.gameevent.TickEvent.ServerTickEvent e) {
        if (e.phase == net.minecraftforge.fml.common.gameevent.TickEvent.Phase.END && Router.server().getTickCounter() % 6000 == 0) { Router.saveHistory(); Stats.snapshot(); }
    }

    static void proxyClientPacket(Net.Pkt m) { if (clientPackets != null) clientPackets.accept(m); }

    @SubscribeEvent
    public void login(PlayerEvent.PlayerLoggedInEvent e) {
        if (e.player instanceof EntityPlayerMP) Router.joined((EntityPlayerMP) e.player);
    }

    /** someone comes into view: tell the viewer what they're up to (menu / asleep) */
    @SubscribeEvent
    public void tracking(net.minecraftforge.event.entity.player.PlayerEvent.StartTracking e) {
        if (e.getEntityPlayer() instanceof EntityPlayerMP && e.getTarget() instanceof EntityPlayerMP) Router.tracking((EntityPlayerMP) e.getEntityPlayer(), (EntityPlayerMP) e.getTarget());
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent e) {
        if (e.player instanceof EntityPlayerMP) Router.left((EntityPlayerMP) e.player);
    }

    /** the vanilla chat path (players without PrideChat, or short global lines): same mutes, slow mode and rules */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void vanillaChat(ServerChatEvent e) {
        EntityPlayerMP p = e.getPlayer();
        if (p == null || Router.inRoute) return;                          // our own router already checked it
        if (!Router.gate(p)) { e.setCanceled(true); return; }
        Moderation.Verdict v = Router.filter(null, p, e.getMessage());
        if (v.block) { e.setCanceled(true); if (v.tell != null) Router.tell(p, v.tell); Stats.blocked("moderation"); return; }
        if (v.shadow) { e.setCanceled(true); p.sendMessage(e.getComponent()); return; }
        if (!v.text.equals(e.getMessage())) e.setComponent(new net.minecraft.util.text.TextComponentTranslation("chat.type.text", p.getDisplayName(), new TextComponentString(v.text)));
        AI.secondOpinion(p, e.getMessage());
    }
}
