package com.dogpound.pridechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.PositionedSoundRecord;
import net.minecraft.client.gui.*;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.SoundEvents;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.SoundEvent;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.ChatType;
import net.minecraftforge.client.event.GuiOpenEvent;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.event.ForgeEventFactory;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.network.FMLNetworkEvent;
import org.lwjgl.input.Keyboard;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Everything PrideChat does on the client that isn't drawing the chat box: installing it, the chat screen, the
 * Options button, sending (modes, prefixes, long messages, vanilla-server fallback), packets from the server, sounds,
 * pop-ups, friends, AFK + auto-reply, typing dots, chat logs and saved input history.
 */
public final class ClientSide {
    static final ClientSide I = new ClientSide();
    static final int OPTIONS_BUTTON = 0x50C4A7;
    static KeyBinding replyKey, modeKey;

    // what the server told us (S_HELLO)
    static boolean server;
    static int serverMaxLength = 256, serverMaxRange = 0;
    static boolean serverGlobal = true, serverColors, serverAI;
    static final List<String[]> CHANNELS = new ArrayList<>();          // {name, range, group}

    // how we're talking right now
    static String mode = ChatConfig.sendMode, modeTarget = "";
    static String lastDm = "";
    /** the message being answered in a thread (#32): {id, name, text} — sent with the next message */
    static long replyId;
    static String replyName = "", replyText = "";
    static String draft = "";
    private static boolean warnedNoServer;

    // queues and timers
    private static final Deque<String> VANILLA_QUEUE = new ArrayDeque<>();
    private static int queueWait;
    static final Map<String, Long> TYPING = new HashMap<>();             // name → until
    private static long typingSent;
    private static boolean typingOn;
    private static int typingRate;
    static long lastActive = System.currentTimeMillis();
    private static double lx, ly, lz;
    private static float lyaw, lpitch;
    private static Set<String> online = new HashSet<>();
    private static final Map<String, Long> AUTO_REPLIED = new HashMap<>();
    static final LinkedList<String> MY_LINES = new LinkedList<>();       // for "reply in my style"

    public static void init() {
        migrate();
        MinecraftForge.EVENT_BUS.register(I);
        MinecraftForge.EVENT_BUS.register(Activity.I);
        replyKey = new KeyBinding("Reply to last private message", Keyboard.KEY_R, "PrideChat");
        modeKey = new KeyBinding("Chat range: whisper → local → shout → everyone", Keyboard.KEY_NONE, "PrideChat");
        ClientRegistry.registerKeyBinding(replyKey);
        ClientRegistry.registerKeyBinding(modeKey);
        PrideChat.clientPackets = m -> Minecraft.getMinecraft().addScheduledTask(() -> packet(m));
    }

    /** settings from PrideChat 0.1 */
    private static void migrate() {
        String[] old = {"All | F5A9B8 | @all", "Chat | 5BCEFA | @player", "Town | FFD700 | (?i)^\\[(town|t|nation|n|ally|a|region|r)\\]",
                "Private | B070FF | (?i)(whispers to you|-> ?me\\]|→ ?me|^from |\\[msg\\]|\\bwhispers\\b)", "Mentions | FF8C00 | @mention",
                "Staff | E40303 | (?i)^\\[(staff|helpop|ticket|mod|admin)\\]", "System | 8A8499 | @system"};
        boolean changed = false;
        if (Arrays.equals(old, ChatConfig.tabList)) { ChatConfig.tabList = ChatConfig.defaultTabs(); changed = true; }
        if ("true".equals(ChatConfig.mentionSound)) { ChatConfig.mentionSound = "ding"; changed = true; }
        if ("false".equals(ChatConfig.mentionSound)) { ChatConfig.mentionSound = "none"; changed = true; }
        if (changed) try { ConfigManager.sync(PrideChat.MODID, Config.Type.INSTANCE); } catch (Throwable ignored) {}
    }

    private static boolean helloSent;

    // ------------------------------------------------------------------ install the chat box, tick jobs
    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null && !(mc.ingameGUI.getChatGUI() instanceof ChatView)) {
            try {
                GuiNewChat old = mc.ingameGUI.getChatGUI();
                ChatView view = new ChatView(mc);
                for (String s : loadHistory()) view.addToSentMessages(s);
                for (String s : old.getSentMessages()) view.addToSentMessages(s);
                ObfuscationReflectionHelper.setPrivateValue(GuiIngame.class, mc.ingameGUI, view, "field_73840_e");
                Emoji.load();
                PrideChat.LOG.info("PrideChat chat box is in" + (net.minecraftforge.fml.common.Loader.isModLoaded("tabbychat") ? " (TabbyChat is also installed — only one chat mod should be on)" : ""));
            } catch (Throwable t) {
                PrideChat.LOG.warn("couldn't install the chat box: " + t);
            }
        }
        if (mc.player == null) return;
        if (!helloSent && mc.getConnection() != null) { helloSent = true; Net.toServer(Net.C_HELLO, null, ""); }   // "I have PrideChat"
        // split pieces for a server without PrideChat, one at a time so it doesn't kick us for spam
        if (!VANILLA_QUEUE.isEmpty() && --queueWait <= 0) { mc.player.sendChatMessage(VANILLA_QUEUE.poll()); queueWait = ChatConfig.splitDelayTicks; }
        // AFK
        if (mc.player.posX != lx || mc.player.posY != ly || mc.player.posZ != lz || mc.player.rotationYaw != lyaw || mc.player.rotationPitch != lpitch) {
            lx = mc.player.posX; ly = mc.player.posY; lz = mc.player.posZ; lyaw = mc.player.rotationYaw; lpitch = mc.player.rotationPitch;
            lastActive = System.currentTimeMillis();
        }
        if (mc.player.ticksExisted % 20 == 0) friends(mc);
        TYPING.values().removeIf(t -> t < System.currentTimeMillis());
        while (replyKey.isPressed() && mc.currentScreen == null) {
            if (!lastDm.isEmpty()) ChatView.openDm(lastDm);
            mc.displayGuiScreen(new GuiPrideChat(lastDm.isEmpty() || ChatConfig.dmTabs ? "" : "/" + ChatConfig.msgCommand + " " + lastDm + " "));
        }
        while (modeKey.isPressed()) cycleMode();
    }

    static boolean afk() { return ChatConfig.afkMinutes > 0 && System.currentTimeMillis() - lastActive > ChatConfig.afkMinutes * 60000L; }

    static void cycleMode() {
        String[] order = {Router.WHISPER, Router.LOCAL, Router.SHOUT, Router.GLOBAL};
        int i = Arrays.asList(order).indexOf(mode);
        mode = order[(i + 1) % order.length];
        modeTarget = "";
    }

    private static void friends(Minecraft mc) {
        if (mc.getConnection() == null) return;
        Set<String> now = new HashSet<>();
        for (NetworkPlayerInfo i : mc.getConnection().getPlayerInfoMap()) now.add(i.getGameProfile().getName());
        if (!online.isEmpty()) for (String f : ChatConfig.friends) {
            boolean was = online.contains(f), is = now.contains(f);
            if (was != is && ChatConfig.toastFriends) { toast("§a" + f, is ? "joined ♥" : "left", 0x7CFC9A); if (is) sound(ChatConfig.friendSound); }
        }
        online = now;
    }

    static boolean isOnline(String name) { return online.contains(name); }

    /** item + block models for the radios and devices */
    @SubscribeEvent
    public void models(net.minecraftforge.client.event.ModelRegistryEvent e) {
        for (net.minecraft.item.Item i : Devices.ITEMS)
            net.minecraftforge.client.model.ModelLoader.setCustomModelResourceLocation(i, 0, new net.minecraft.client.renderer.block.model.ModelResourceLocation(i.getRegistryName(), "inventory"));
    }

    /** the Room Marker's selection, drawn as a pink box while you hold it */
    @SubscribeEvent
    public void world(net.minecraftforge.client.event.RenderWorldLastEvent e) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer p = mc.player;
        if (p == null || Rooms.wand == null) return;
        net.minecraft.item.ItemStack s = p.getHeldItemMainhand().getItem() == Rooms.wand ? p.getHeldItemMainhand() : p.getHeldItemOffhand();
        if (s.getItem() != Rooms.wand || !s.hasTagCompound() || s.getTagCompound().getInteger("d") != p.dimension) return;
        int[] a = s.getTagCompound().getIntArray("a"), b = s.getTagCompound().getIntArray("b");
        if (a.length < 3) a = b;
        if (b.length < 3) b = a;
        if (a.length < 3) return;
        float pt = e.getPartialTicks();
        double px = p.lastTickPosX + (p.posX - p.lastTickPosX) * pt, py = p.lastTickPosY + (p.posY - p.lastTickPosY) * pt, pz = p.lastTickPosZ + (p.posZ - p.lastTickPosZ) * pt;
        net.minecraft.util.math.AxisAlignedBB box = new net.minecraft.util.math.AxisAlignedBB(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]) + 1, Math.max(a[1], b[1]) + 1, Math.max(a[2], b[2]) + 1).offset(-px, -py, -pz).grow(0.003);
        net.minecraft.client.renderer.GlStateManager.disableTexture2D();
        net.minecraft.client.renderer.GlStateManager.disableDepth();
        net.minecraft.client.renderer.GlStateManager.glLineWidth(2.5f);
        net.minecraft.client.renderer.RenderGlobal.drawSelectionBoundingBox(box, 0.96f, 0.66f, 0.72f, 1f);
        net.minecraft.client.renderer.GlStateManager.enableDepth();
        net.minecraft.client.renderer.GlStateManager.enableTexture2D();
    }

    // ------------------------------------------------------------------ the chat screen + the Options button
    @SubscribeEvent
    public void open(GuiOpenEvent e) {
        if (!ChatConfig.enabled || !(e.getGui() instanceof GuiChat) || e.getGui() instanceof GuiSleepMP || e.getGui() instanceof GuiPrideChat) return;
        String start = "";
        try { start = ObfuscationReflectionHelper.getPrivateValue(GuiChat.class, (GuiChat) e.getGui(), "field_146409_v"); } catch (Throwable ignored) {}
        e.setGui(new GuiPrideChat(start == null ? "" : start));
    }

    @SubscribeEvent
    public void optionsInit(GuiScreenEvent.InitGuiEvent.Post e) {
        if (!(e.getGui() instanceof GuiOptions)) return;
        GuiScreen g = e.getGui();
        for (GuiButton b : e.getButtonList()) if (b.id == OPTIONS_BUTTON) return;
        e.getButtonList().add(new GuiButton(OPTIONS_BUTTON, g.width / 2 - 155, g.height / 6 + 144 - 6, 150, 20, "✦ PrideChat…"));
        if (g.getClass() != GuiOptions.class) {                          // PrideCanvas' Options: let it restyle + reflow
            try {
                Class.forName("com.dogpound.canvas.DPSkin").getMethod("reskinAll", List.class).invoke(null, e.getButtonList());
                Class.forName("com.dogpound.canvas.DPChrome").getMethod("reflowOptions", List.class, int.class, int.class).invoke(null, e.getButtonList(), g.width, g.height);
            } catch (Throwable ignored) {}
        }
    }

    @SubscribeEvent
    public void optionsClick(GuiScreenEvent.ActionPerformedEvent.Pre e) {
        if (e.getGui() instanceof GuiOptions && e.getButton().id == OPTIONS_BUTTON) {
            Minecraft.getMinecraft().displayGuiScreen(new GuiChatSettings(e.getGui()));
            e.setCanceled(true);
        }
    }

    // ------------------------------------------------------------------ sending
    /** everything typed into the chat comes here (from GuiPrideChat.sendChatMessage) */
    static void submit(String typed, String forceMode) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null) return;
        String text = expand(typed.replace('⏎', '\n').trim());
        if (text.isEmpty()) return;
        draft = "";
        typing(false);
        saveHistory(typed);
        sound(ChatConfig.sendSound);
        if (text.startsWith("/")) { command(text); return; }
        MY_LINES.add(text);
        while (MY_LINES.size() > 12) MY_LINES.removeFirst();

        String m = forceMode != null ? forceMode : mode, target = forceMode != null ? "" : modeTarget;
        String rule = ChatView.activeRule();
        if (forceMode == null) {
            if (rule.startsWith("@dm:")) { m = Router.DM; target = rule.substring(4); }
            else if (rule.startsWith("#")) { m = Router.CHANNEL; target = rule.substring(1); }
            else if ("@global".equals(rule)) m = Router.GLOBAL;
            else if ("@local".equals(rule) && Router.GLOBAL.equals(m)) m = Router.LOCAL;
        }
        // prefixes win over everything
        if (!ChatConfig.globalPrefix.isEmpty() && text.startsWith(ChatConfig.globalPrefix)) { m = Router.GLOBAL; text = text.substring(ChatConfig.globalPrefix.length()).trim(); target = ""; }
        else if (!ChatConfig.shoutPrefix.isEmpty() && text.startsWith(ChatConfig.shoutPrefix)) { m = Router.SHOUT; text = text.substring(ChatConfig.shoutPrefix.length()).trim(); target = ""; }
        else if (!ChatConfig.whisperPrefix.isEmpty() && text.startsWith(ChatConfig.whisperPrefix)) { m = Router.WHISPER; text = text.substring(ChatConfig.whisperPrefix.length()).trim(); target = ""; }
        if (text.isEmpty()) return;

        if (server) { chunked(text, m, target); return; }
        // a server without PrideChat: private messages become /msg, everything else is plain chat everyone hears
        if (Router.DM.equals(m)) {
            for (String piece : split(text.replace('\n', ' '), 250 - ChatConfig.msgCommand.length() - target.length())) VANILLA_QUEUE.add("/" + ChatConfig.msgCommand + " " + target + " " + piece);
            queueWait = Math.min(queueWait, 1);
            return;
        }
        if (!Router.GLOBAL.equals(m) && !warnedNoServer) {
            warnedNoServer = true;
            info("§eThis server doesn't have PrideChat, so everyone hears you (no local/whisper/shout/channels here).");
        }
        vanilla(text);
    }

    /** macros (exact trigger → text) and %x %y %z %dim %biome %health %name %time (roadmap #35) */
    static String expand(String text) {
        for (String m : ChatConfig.macros) {
            String[] p = m.split("\\s*\\|\\s*", 2);
            if (p.length == 2 && p[0].trim().equalsIgnoreCase(text)) { text = p[1].trim(); break; }
        }
        Minecraft mc = Minecraft.getMinecraft();
        if (!ChatConfig.macroVars || text.indexOf('%') < 0 || mc.player == null) return text;
        net.minecraft.util.math.BlockPos pos = mc.player.getPosition();
        String biome = "";
        try { biome = mc.world.getBiome(pos).getBiomeName(); } catch (Throwable ignored) {}
        String dim = "";
        try { dim = mc.world.provider.getDimensionType().getName(); } catch (Throwable ignored) {}
        return text.replace("%x", String.valueOf(pos.getX())).replace("%y", String.valueOf(pos.getY())).replace("%z", String.valueOf(pos.getZ()))
                .replace("%dim", dim).replace("%biome", biome).replace("%health", String.valueOf((int) Math.ceil(mc.player.getHealth())))
                .replace("%name", mc.player.getName()).replace("%time", new SimpleDateFormat("HH:mm").format(new Date()));
    }

    private static void command(String text) {
        Minecraft mc = Minecraft.getMinecraft();
        String flat = text.replace('\n', ' ');
        if (flat.length() <= 256) { mc.player.sendChatMessage(flat); return; }
        if (server && flat.length() <= serverMaxLength) { chunked(flat, "", ""); return; }
        info("§cThat command is " + flat.length() + " letters — this server only takes 256. Not sent.");
    }

    /** plain chat to a vanilla server: lines and ≤256-letter pieces, a second apart */
    private static void vanilla(String text) {
        List<String> pieces = new ArrayList<>();
        for (String line : text.split("\n")) if (!line.trim().isEmpty()) pieces.addAll(split(line.trim(), 256));
        if (pieces.size() > 1 && !ChatConfig.splitOnVanilla) { info("§cToo long for this server (256 letters) — turn on 'split long messages' in PrideChat settings."); return; }
        if (pieces.size() > ChatConfig.maxSplitPieces) {
            info("§eThat would be " + pieces.size() + " messages here; sending the first " + ChatConfig.maxSplitPieces + " (change it in settings).");
            pieces = pieces.subList(0, ChatConfig.maxSplitPieces);
        }
        Minecraft.getMinecraft().player.sendChatMessage(pieces.get(0));
        VANILLA_QUEUE.addAll(pieces.subList(1, pieces.size()));
        queueWait = ChatConfig.splitDelayTicks;
    }

    /** split at spaces into pieces of at most n letters, never through an emoji (surrogate pair) */
    static List<String> split(String s, int n) {
        List<String> out = new ArrayList<>();
        n = Math.max(20, n);
        while (s.length() > n) {
            int cut = s.lastIndexOf(' ', n);
            if (cut < n / 2) cut = n;
            if (Character.isHighSurrogate(s.charAt(cut - 1))) cut--;
            out.add(s.substring(0, cut).trim());
            s = s.substring(cut).trim();
        }
        if (!s.isEmpty()) out.add(s);
        return out;
    }

    /** a whole message to a PrideChat server, in 8,000-letter chunks */
    static void chunked(String text, String m, String target) {
        if (text.length() > serverMaxLength) { info("§eThe server takes " + serverMaxLength + " letters — the rest was cut."); text = text.substring(0, serverMaxLength); }
        List<String> pieces = new ArrayList<>();
        for (int at = 0; at < text.length(); ) {
            int end = Math.min(text.length(), at + Net.CHUNK);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;   // never cut an emoji in half
            pieces.add(text.substring(at, end));
            at = end;
        }
        int id = new Random().nextInt();
        for (int i = 0; i < pieces.size(); i++) {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("id", id); t.setInteger("i", i); t.setInteger("n", pieces.size());
            t.setString("mode", m); t.setString("target", target);
            t.setString("bs", ChatConfig.bubbleStyle);
            t.setTag("ap", appearance());
            t.setString("rp", ChatConfig.rpMode);
            if (replyId != 0) { t.setLong("re", replyId); t.setString("rs", replyName); t.setString("rt", replyText.length() > 80 ? replyText.substring(0, 80) + "…" : replyText); }
            t.setInteger("range", rangeOf(m));
            Net.toServer(Net.C_CHUNK, t, pieces.get(i));
        }
        replyId = 0;
    }

    /** my look (#7) — the server checks permissions + limits and passes it on with my lines */
    static NBTTagCompound appearance() {
        NBTTagCompound a = new NBTTagCompound();
        a.setString("bc", ChatConfig.myBubbleColor.replace("#", "").trim());
        a.setString("be", ChatConfig.myBubbleBorder.replace("#", "").trim());
        a.setByte("bt", (byte) ChatConfig.myBubbleBorderSize);
        a.setByte("ba", (byte) ChatConfig.myBubbleAlpha);
        a.setInteger("bl", ChatConfig.myBubbleSeconds);
        a.setString("an", ChatConfig.myBubbleAnimation.trim());
        a.setShort("hs", (short) ChatConfig.myHeadSize);
        a.setString("hb", ChatConfig.myHeadBorder.replace("#", "").trim());
        a.setString("nc", ChatConfig.myNameColor.replace("#", "").trim());
        a.setString("mc", ChatConfig.myMessageColor.replace("#", "").trim());
        a.setString("cc", ChatConfig.myChannelColor.replace("#", "").trim());
        return a;
    }

    static int rangeOf(String m) {
        switch (m) {
            case Router.WHISPER: return ChatConfig.whisperRange;
            case Router.SHOUT: return ChatConfig.shoutRange;
            case Router.LOCAL: return ChatConfig.localRange;
            default: return 0;
        }
    }

    /** typing dots for the people who'd hear us (PrideChat servers only) */
    static void typing(boolean on) {
        if (!server || !ChatConfig.sendTyping) return;
        long now = System.currentTimeMillis();
        if (on) lastActive = now;
        int r = on && ChatConfig.sendTypingSpeed && Activity.srvRate ? Activity.myRate() : 0;
        boolean faster = on && Math.abs(r - typingRate) >= 2 && now - typingSent >= 600;   // the speed changed (throttled)
        if (on == typingOn && (!on || now - typingSent < 2500) && !faster) return;
        typingOn = on;
        typingSent = now;
        typingRate = r;
        NBTTagCompound t = new NBTTagCompound();
        t.setBoolean("on", on);
        if (r > 0) t.setByte("r", (byte) r);
        String rule = ChatView.activeRule();
        if (rule.startsWith("@dm:")) { t.setString("mode", Router.DM); t.setString("target", rule.substring(4)); }
        else if (rule.startsWith("#")) { t.setString("mode", Router.CHANNEL); t.setString("target", rule.substring(1)); }
        else { t.setString("mode", mode); t.setString("target", modeTarget); t.setInteger("range", rangeOf(mode)); }
        Net.toServer(Net.C_TYPING, t, "");
    }

    static void ai(String kind, NBTTagCompound t, String text) {
        if (!server) { info("§7The AI helpers need PrideChat on the server (singleplayer counts)."); return; }
        t.setString("kind", kind);
        Net.toServer(Net.C_AI, t, text);
    }

    // ------------------------------------------------------------------ packets from the server
    static void packet(Net.Pkt m) {
        Minecraft mc = Minecraft.getMinecraft();
        NBTTagCompound t = m.tag;
        switch (m.type) {
            case Net.S_HELLO:
                server = true;
                serverMaxLength = t.getInteger("maxLength");
                serverMaxRange = t.getInteger("maxRange");
                serverGlobal = t.getBoolean("global");
                serverColors = t.getBoolean("colors");
                serverAI = t.getBoolean("ai");
                Activity.hello(t);
                mode = ChatConfig.sendMode;
                break;
            case Net.S_LINE: {
                ITextComponent c;
                try { c = ITextComponent.Serializer.jsonToComponent(m.text); } catch (Throwable ex) { c = new TextComponentString(m.text); }
                if (c == null) return;
                if (!t.getBoolean("h")) { c = ForgeEventFactory.onClientChat(ChatType.CHAT, c); if (c == null) return; }
                ChatView.meta = t;
                try { mc.ingameGUI.getChatGUI().printChatMessage(c); } finally { ChatView.meta = null; }
                break;
            }
            case Net.S_TYPING:
                if (!ChatConfig.showTyping || ChatView.ignored(t.getString("s")) || ChatView.muted(t.getString("s")) || ChatView.hiddenTyping(t.getString("s"))) return;
                if (t.getBoolean("on")) { TYPING.put(t.getString("s"), System.currentTimeMillis() + 4000); Activity.RATE.put(t.getString("s"), (int) t.getByte("r")); }
                else TYPING.remove(t.getString("s"));
                break;
            case Net.S_STATUS: Activity.status(t); break;
            case Net.S_READ: ChatView.markRead(t.getString("by")); break;
            case Net.S_REACT: ChatView.react(t.getLong("id"), m.text, t.getString("s")); break;
            case Net.S_MODE: mode = t.getString("mode"); modeTarget = t.getString("target"); break;
            case Net.S_CHANNELS: {
                CHANNELS.clear();
                net.minecraft.nbt.NBTTagList l = t.getTagList("list", 10);
                for (int i = 0; i < l.tagCount(); i++) {
                    NBTTagCompound c = l.getCompoundTagAt(i);
                    CHANNELS.add(new String[]{c.getString("n"), String.valueOf(c.getInteger("r")), String.valueOf(c.getBoolean("g"))});
                }
                ChatView.loadTabs();
                break;
            }
            case Net.S_AI: aiReply(t, m.text); break;
            case Net.S_EFFECT:
                if ("rpmode".equals(t.getString("kind"))) { ChatConfig.rpMode = t.getString("v"); saved(); break; }
                if ("shake".equals(t.getString("kind")) && ChatConfig.screenShake && !ChatView.ignored(t.getString("s")) && !ChatView.hiddenEmotes(t.getString("s")))
                    shakeUntil = System.currentTimeMillis() + t.getInteger("n") * 60L;
                break;
            case Net.S_ASK: mc.displayGuiScreen(new GuiAskText(null, t.getString("q"), t.getString("v"), s -> Net.toServer(Net.C_ANSWER, null, s))); break;
            default: break;
        }
    }

    private static void aiReply(NBTTagCompound t, String text) {
        if (t.getBoolean("off")) { info("§7The AI helpers are off on this server (AI page in the chat settings, singleplayer)."); return; }
        switch (t.getString("kind")) {
            case "translate": info(text.isEmpty() ? "§7(no translation — the AI is busy or off)" : "§b🌐 §f" + text); break;
            case "tone": GuiPrideChat.toneAnswer(text); break;
            case "autoreply":
                if (!text.isEmpty()) chunked(text, Router.DM, t.getString("to"));
                else chunked(ChatConfig.autoReplyText, Router.DM, t.getString("to"));
                break;
            default: break;
        }
    }

    /** a private message came in: pop-up, sound, auto-reply */
    static void incomingDm(String from, String text, boolean history) {
        lastDm = from;
        if (history) return;
        if (ChatConfig.toastDMs) toast("§d✉ " + from, text, 0xB070FF);
        sound(ChatConfig.dmSound);
        if (ChatConfig.autoReply && (!ChatConfig.autoReplyOnlyAfk || afk())) {
            Long last = AUTO_REPLIED.get(from);
            if (last != null && System.currentTimeMillis() - last < 120000) return;
            AUTO_REPLIED.put(from, System.currentTimeMillis());
            if (server && ChatConfig.aiAutoReply && serverAI) {
                NBTTagCompound t = new NBTTagCompound();
                t.setString("to", from);
                t.setString("style", String.join("\n", MY_LINES));
                ai("autoreply", t, text);
            } else if (server) chunked(ChatConfig.autoReplyText, Router.DM, from);
            else VANILLA_QUEUE.add("/" + ChatConfig.msgCommand + " " + from + " " + ChatConfig.autoReplyText);
        }
    }

    @SubscribeEvent
    public void disconnect(FMLNetworkEvent.ClientDisconnectionFromServerEvent e) {
        server = false; serverAI = false; helloSent = false; serverMaxLength = 256; CHANNELS.clear(); TYPING.clear(); VANILLA_QUEUE.clear(); warnedNoServer = false;
        Activity.reset();
        Minecraft.getMinecraft().addScheduledTask(ChatView::loadTabs);
    }

    // ------------------------------------------------------------------ screen shake (a scream near you, #48)
    static long shakeUntil;

    @SubscribeEvent
    public void camera(net.minecraftforge.client.event.EntityViewRenderEvent.CameraSetup e) {
        long left = shakeUntil - System.currentTimeMillis();
        if (left <= 0) return;
        float k = Math.min(1f, left / 400f) * 1.6f;
        double t = System.nanoTime() / 1.6e7;
        e.setYaw(e.getYaw() + (float) Math.sin(t * 1.7) * k);
        e.setPitch(e.getPitch() + (float) Math.cos(t * 2.3) * k);
        e.setRoll(e.getRoll() + (float) Math.sin(t * 3.1) * k * 0.8f);
    }

    // ------------------------------------------------------------------ drawing: bubbles + pop-ups
    @SubscribeEvent
    public void specials(RenderLivingEvent.Specials.Post<?> e) {
        if (e.getEntity() instanceof EntityPlayer || e.getEntity().hasCustomName() && Bubbles.talking(e.getEntity().getName()))   // players + talking NPCs
            Bubbles.render(e.getEntity(), e.getX(), e.getY(), e.getZ());
        if (e.getEntity() instanceof EntityPlayer) Activity.render((EntityPlayer) e.getEntity(), e.getX(), e.getY(), e.getZ());
    }

    private static final LinkedList<Object[]> TOASTS = new LinkedList<>();   // {title, text, colour, time}
    private static final LinkedList<Object[]> TALKERS = new LinkedList<>();  // {name, kind, x, z, time} far / unseen talkers (#15)

    static void talked(String name, String kind, int dim, double x, double z, double dist) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ChatConfig.talkIndicator || dist < 0 || dim != mc.player.dimension) return;
        double rel = net.minecraft.util.math.MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-(x - mc.player.posX), z - mc.player.posZ)) - mc.player.rotationYaw);
        if (dist < ChatConfig.talkIndicatorDistance && Math.abs(rel) < 70) return;   // close and in front: the bubble says it all
        TALKERS.removeIf(o -> name.equals(o[0]));
        TALKERS.add(new Object[]{name, kind, x, z, System.currentTimeMillis()});
        while (TALKERS.size() > 4) TALKERS.removeFirst();
    }

    private static void drawTalkers(int sw) {
        Minecraft mc = Minecraft.getMinecraft();
        if (TALKERS.isEmpty() || mc.player == null) return;
        long now = System.currentTimeMillis(), life = ChatConfig.talkIndicatorSeconds * 1000L;
        TALKERS.removeIf(o -> now - (long) o[4] > life);
        FontRenderer fr = mc.fontRenderer;
        int y = 22;
        for (Object[] o : TALKERS) {
            double x = (double) o[2], z = (double) o[3];
            double dx = x - mc.player.posX, dz = z - mc.player.posZ, dist = Math.sqrt(dx * dx + dz * dz);
            float rel = (float) net.minecraft.util.math.MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(-dx, dz)) - mc.player.rotationYaw);
            String kind = (String) o[1];
            String icon = Devices.RADIO.equals(kind) ? "§b📻 " : Router.SHOUT.equals(kind) ? "§6📢 " : Router.WHISPER.equals(kind) ? "§7🤫 " : "§d💬 ";
            String d = dist >= 1000 ? String.format("%.1fkm", dist / 1000) : (int) dist + "m";
            String s = icon + "§f" + o[0] + " §7talking " + d;
            int w = fr.getStringWidth(s) + 22, bx = sw / 2 - w / 2;
            long age = now - (long) o[4];
            int a = age > life - 500 ? (int) (255 * (life - age) / 500) : 255;
            if (a <= 4) continue;
            Gui.drawRect(bx, y, bx + w, y + 13, (a * 200 / 255) << 24 | 0x140E22);
            Gui.drawRect(bx, y + 12, bx + w, y + 13, a << 24 | 0xF5A9B8);
            fr.drawStringWithShadow(s, bx + 4, y + 3, a << 24 | 0xFFFFFF);
            net.minecraft.client.renderer.GlStateManager.pushMatrix();               // an arrow pointing to them
            net.minecraft.client.renderer.GlStateManager.translate(bx + w - 9, y + 6.5f, 0);
            net.minecraft.client.renderer.GlStateManager.rotate(rel, 0, 0, 1);
            int c = a << 24 | 0x5BCEFA;
            Gui.drawRect(0, -5, 1, -4, c); Gui.drawRect(-1, -4, 2, -3, c); Gui.drawRect(-2, -3, 3, -2, c); Gui.drawRect(-3, -2, 4, -1, c);
            Gui.drawRect(-1, -1, 2, 5, c);
            net.minecraft.client.renderer.GlStateManager.popMatrix();
            y += 15;
        }
        net.minecraft.client.renderer.GlStateManager.color(1, 1, 1, 1);
    }

    /** blink the taskbar when the game is in the background (Cleanroom / LWJGL 3 only; nothing to ask for on LWJGL 2) */
    /** taskbar flash on a mention. OFF for good: glfwRequestWindowAttention segfaulted the whole JVM on her X11
     *  (Xwayland) window 2026-10-05 - a native crash can't be caught, so no native call here at all. The mention
     *  sound + toast still tell her. */
    static void flash() { }

    static void toast(String title, String text, int color) {
        if (ChatConfig.dnd) return;
        TOASTS.add(new Object[]{title, text, color, System.currentTimeMillis()});
        while (TOASTS.size() > 4) TOASTS.removeFirst();
    }

    @SubscribeEvent
    public void overlay(RenderGameOverlayEvent.Post e) {
        if (e.getType() != RenderGameOverlayEvent.ElementType.ALL) return;
        ChatView.drawWindows(e.getResolution().getScaledWidth(), e.getResolution().getScaledHeight());
        drawTalkers(e.getResolution().getScaledWidth());
        if (TOASTS.isEmpty()) return;
        Minecraft mc = Minecraft.getMinecraft();
        FontRenderer fr = mc.fontRenderer;
        long now = System.currentTimeMillis(), life = ChatConfig.toastSeconds * 1000L;
        TOASTS.removeIf(t -> now - (long) t[3] > life);
        int y = 4, sw = e.getResolution().getScaledWidth();
        for (Object[] t : TOASTS) {
            String title = (String) t[0], body = fr.trimStringToWidth((String) t[1], 150);
            int w = Math.max(fr.getStringWidth(title), fr.getStringWidth(body)) + 14, x = sw - w - 4;
            long age = now - (long) t[3];
            int slide = age < 200 ? (int) ((200 - age) * w / 200) : 0;
            x += slide;
            Gui.drawRect(x, y, x + w, y + 24, 0xE8140E22);
            Gui.drawRect(x, y, x + 2, y + 24, 0xFF000000 | (int) t[2]);
            fr.drawStringWithShadow(title, x + 7, y + 3, 0xFFFFFF);
            fr.drawStringWithShadow("§7" + body, x + 7, y + 13, 0xFFFFFF);
            y += 27;
        }
    }

    // ------------------------------------------------------------------ sounds, info lines, logs, history
    static void sound(String name) {
        if (ChatConfig.dnd || name == null || name.isEmpty() || "none".equals(name)) return;
        SoundEvent s;
        float pitch = 1.6f;
        switch (name) {
            case "ding": s = SoundEvents.BLOCK_NOTE_PLING; break;
            case "bell": s = SoundEvents.BLOCK_NOTE_BELL; pitch = 1.2f; break;
            case "chime": s = SoundEvents.BLOCK_NOTE_CHIME; pitch = 1.3f; break;
            case "pop": s = SoundEvents.ENTITY_ITEM_PICKUP; pitch = 1.4f; break;
            case "xylophone": s = SoundEvents.BLOCK_NOTE_XYLOPHONE; pitch = 1.5f; break;
            case "flute": s = SoundEvents.BLOCK_NOTE_FLUTE; pitch = 1.2f; break;
            case "guitar": s = SoundEvents.BLOCK_NOTE_GUITAR; pitch = 1.0f; break;
            case "harp": s = SoundEvents.BLOCK_NOTE_HARP; pitch = 1.4f; break;
            case "xp": s = SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP; pitch = 1.2f; break;
            case "tick": s = SoundEvents.BLOCK_NOTE_HAT; pitch = 1.8f; break;
            default: return;
        }
        Minecraft.getMinecraft().getSoundHandler().playSound(new PositionedSoundRecord(s.getSoundName(), net.minecraft.util.SoundCategory.MASTER, ChatConfig.volume / 100f, pitch, false, 0, net.minecraft.client.audio.ISound.AttenuationType.NONE, 0, 0, 0));
    }

    static final String[] SOUNDS = {"none", "ding", "bell", "chime", "pop", "xylophone", "flute", "guitar", "harp", "xp", "tick"};

    /** a grey PrideChat line only you see */
    static void info(String s) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.ingameGUI != null) mc.ingameGUI.getChatGUI().printChatMessage(new TextComponentString("§d✦ §r" + s));
    }

    private static final SimpleDateFormat DAY = new SimpleDateFormat("yyyy-MM-dd"), CLOCK = new SimpleDateFormat("HH:mm:ss");

    static void log(String plain) {
        if (!ChatConfig.logToFile) return;
        try {
            File dir = new File(Minecraft.getMinecraft().mcDataDir, "logs/pridechat");
            dir.mkdirs();
            Date d = new Date();
            Files.write(new File(dir, DAY.format(d) + ".log").toPath(), ("[" + CLOCK.format(d) + "] " + plain + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static File historyFile() { return new File(Minecraft.getMinecraft().mcDataDir, "config/pridechat-history.txt"); }

    static List<String> loadHistory() {
        if (!ChatConfig.saveInputHistory) return Collections.emptyList();
        try { return Files.readAllLines(historyFile().toPath(), StandardCharsets.UTF_8); } catch (Exception e) { return Collections.emptyList(); }
    }

    private static final LinkedList<String> SAVED = new LinkedList<>();

    static void saveHistory(String s) {
        if (!ChatConfig.saveInputHistory) return;
        if (SAVED.isEmpty()) SAVED.addAll(loadHistory());
        SAVED.remove(s);
        SAVED.add(s.replace('\n', '⏎'));
        while (SAVED.size() > 200) SAVED.removeFirst();
        try { Files.write(historyFile().toPath(), String.join("\n", SAVED).getBytes(StandardCharsets.UTF_8)); } catch (Exception ignored) {}
    }

    // ------------------------------------------------------------------ settings changed
    @SubscribeEvent
    public void config(ConfigChangedEvent.OnConfigChangedEvent e) { if (PrideChat.MODID.equals(e.getModID())) saved(); }

    static void saved() {
        ConfigManager.sync(PrideChat.MODID, Config.Type.INSTANCE);
        ChatView.loadTabs();
        ChatView.relayout();
    }

    private ClientSide() {}
}
