package com.dogpound.pridechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.GuiSleepMP;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * PrideChat (her ask 2026-09-29: "build a mod that changes the way the chat looks and adds tabs … real nice with
 * emojis and colors"). Client-side only — works on any server. Replaces Minecraft's chat box with ChatView and the
 * chat screen with GuiPrideChat. Turn it off in the settings (or config enabled=false) to get the normal chat back.
 */
@Mod(modid = PrideChat.MODID, name = "PrideChat", version = "0.1.0", clientSideOnly = true, acceptableRemoteVersions = "*")
public class PrideChat {
    public static final String MODID = "pridechat";
    public static final Logger LOG = LogManager.getLogger("PrideChat");

    @Mod.EventHandler
    public void init(FMLInitializationEvent e) {
        MinecraftForge.EVENT_BUS.register(this);
        LOG.info("[PrideChat] loaded OK");
    }

    /** put our chat box into the HUD once the HUD exists (and again if something swapped it back) */
    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI == null || mc.ingameGUI.getChatGUI() instanceof ChatView) return;
        try {
            GuiNewChat old = mc.ingameGUI.getChatGUI();
            ChatView view = new ChatView(mc);
            for (String s : old.getSentMessages()) view.addToSentMessages(s);          // keep your ↑ history
            ObfuscationReflectionHelper.setPrivateValue(GuiIngame.class, mc.ingameGUI, view, "field_73840_e");
            Emoji.load();
            LOG.info("PrideChat chat box is in" + (net.minecraftforge.fml.common.Loader.isModLoaded("tabbychat") ? " (TabbyChat is also installed — only one chat mod should be on)" : ""));
        } catch (Throwable t) {
            LOG.warn("couldn't install the chat box: " + t);
        }
    }

    /** the chat screen: ours (not in bed — that screen has its own buttons) */
    @SubscribeEvent
    public void open(GuiOpenEvent e) {
        if (!ChatConfig.enabled || !(e.getGui() instanceof GuiChat) || e.getGui() instanceof GuiSleepMP || e.getGui() instanceof GuiPrideChat) return;
        String start = "";
        try { start = ObfuscationReflectionHelper.getPrivateValue(GuiChat.class, (GuiChat) e.getGui(), "field_146409_v"); } catch (Throwable ignored) {}
        e.setGui(new GuiPrideChat(start == null ? "" : start));
    }

    @SubscribeEvent
    public void config(ConfigChangedEvent.OnConfigChangedEvent e) {
        if (!MODID.equals(e.getModID())) return;
        ConfigManager.sync(MODID, Config.Type.INSTANCE);
        ChatView.loadTabs();
        for (ChatView.Line l : ChatView.LINES) l.rows = null;
    }

    /** save settings changed from our own menu and re-lay out */
    static void saved() {
        ConfigManager.sync(MODID, Config.Type.INSTANCE);
        ChatView.loadTabs();
        for (ChatView.Line l : ChatView.LINES) l.rows = null;
    }
}
