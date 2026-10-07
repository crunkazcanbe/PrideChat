package com.dogpound.pridechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.inventory.*;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.BufferBuilder;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.vertex.DefaultVertexFormats;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;

import java.util.*;

/**
 * "What are they up to" (0.4.1, our own take — no code or art from the mod of that name):
 *  - typing dots run faster and get wackier the faster someone really types (a 0-30 keys/s rate rides on the typing packet);
 *  - a player in a menu gets a little holo screen in front of them with the menu's icon, and their arm taps at it;
 *  - a player idle past their AFK time falls asleep: head droops, Zz float up, Zz next to them in the tab list,
 *    optional "fell asleep / woke up" lines.
 * Each part can be switched off on the sending side (nothing is sent) and on the viewing side; the server can switch
 * each one off for everybody. Client-only class; drawing is allocation-free and only for players within bubbleDistance.
 */
final class Activity {
    static final Activity I = new Activity();

    /** what the server allows (S_HELLO) */
    static boolean srvGui, srvIdle, srvRate;
    static int srvIdleMinutes;

    /** others, by name */
    static final Map<String, Integer> GUI = new HashMap<>();             // name → menu kind 1-6
    static final Set<String> IDLE = new HashSet<>();
    static final Map<String, Integer> RATE = new HashMap<>();            // name → keys per second while typing
    private static final Map<String, ITextComponent[]> TAB = new HashMap<>();   // name → {their own tab name, ours with Zz}

    static final int[] PRIDE = {0xE40303, 0xFF8C00, 0xFFED00, 0x008026, 0x24408E, 0x732982, 0x5BCEFA, 0xF5A9B8};

    // ------------------------------------------------------------------ my typing speed
    private static float ema;
    private static long lastKey;

    /** one printable key in the chat box */
    static void keystroke() {
        long now = System.currentTimeMillis(), dt = now - lastKey;
        lastKey = now;
        if (dt > 1500) { ema = Math.max(ema * 0.5f, 2f); return; }      // first key after a pause
        ema = ema * 0.75f + 1000f / Math.max(30, dt) * 0.25f;
    }

    /** keys per second right now, 0-30 (0 when they paused) */
    static int myRate() { return System.currentTimeMillis() - lastKey > 1500 ? 0 : MathHelper.clamp(Math.round(ema), 0, 30); }

    static int rate(String name) { Integer r = RATE.get(name); return r == null ? 0 : r; }

    /** ms per dot step: 300 at rest, ~35 flat out */
    static int period(int rate) { return (int) (300 / (1 + rate / 4f)); }

    // ------------------------------------------------------------------ my menu + idle → the server
    private static int sentGui;
    private static boolean sentIdle;
    private static long sentAt;
    private static int mouseX = -1, mouseY = -1;

    /** the menu kind: 0 none / chat, 1 inventory, 2 chest-like, 3 crafting, 4 furnace / brewing, 5 other (modded) container, 6 any other screen */
    static int kind(GuiScreen s) {
        if (s == null || s instanceof GuiChat) return 0;                 // chat has its own typing dots
        if (s instanceof GuiInventory || s instanceof GuiContainerCreative) return 1;
        if (s instanceof GuiChest || s instanceof GuiShulkerBox || s instanceof GuiDispenser || s instanceof GuiScreenHorseInventory) return 2;
        if (s instanceof GuiCrafting) return 3;
        if (s instanceof GuiFurnace || s instanceof GuiBrewingStand) return 4;
        if (s instanceof GuiContainer) return 5;
        return 6;
    }

    static boolean idleNow() {
        int min = srvIdleMinutes > 0 ? srvIdleMinutes : ChatConfig.afkMinutes;
        return min > 0 && System.currentTimeMillis() - ClientSide.lastActive > min * 60000L;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) return;
        long now = System.currentTimeMillis();
        if (mc.currentScreen != null && (Mouse.getX() != mouseX || Mouse.getY() != mouseY)) {   // busy in a menu = not idle
            if (mouseX >= 0) ClientSide.lastActive = now;
            mouseX = Mouse.getX(); mouseY = Mouse.getY();
        }
        if (ClientSide.server) {
            int g = ChatConfig.sendGui && srvGui ? kind(mc.currentScreen) : 0;
            boolean idle = ChatConfig.sendIdle && srvIdle && idleNow();
            if ((g != sentGui || idle != sentIdle) && now - sentAt > 250) {   // at most 4 a second, only on change
                sentGui = g; sentIdle = idle; sentAt = now;
                NBTTagCompound t = new NBTTagCompound();
                t.setByte("g", (byte) g);
                t.setBoolean("i", idle);
                Net.toServer(Net.C_STATUS, t, "");
            }
        }
        RATE.keySet().retainAll(ClientSide.TYPING.keySet());
        if (ChatConfig.showGuiHolo && ChatConfig.guiArmSwing && !GUI.isEmpty()) for (EntityPlayer p : mc.world.playerEntities) {
            if (p == mc.player || !GUI.containsKey(p.getName()) || p.getDistanceSq(mc.player) > (double) ChatConfig.bubbleDistance * ChatConfig.bubbleDistance) continue;
            int beat = 7 + (p.getEntityId() & 3);                        // each player taps to their own beat
            if ((p.ticksExisted + p.getEntityId()) % beat == 0 && mc.world.rand.nextInt(4) != 0) p.swingArm(EnumHand.MAIN_HAND);
        }
        if (mc.player.ticksExisted % 20 == 0) tabList(mc);
    }

    @SubscribeEvent
    public void keys(GuiScreenEvent.KeyboardInputEvent.Pre e) { ClientSide.lastActive = System.currentTimeMillis(); }

    // ------------------------------------------------------------------ from the server
    static void hello(NBTTagCompound t) {
        srvGui = t.getBoolean("aGui"); srvIdle = t.getBoolean("aIdle"); srvRate = t.getBoolean("aRate"); srvIdleMinutes = t.getInteger("idleMin");
        sentGui = 0; sentIdle = false; sentAt = 0;
    }

    static void status(NBTTagCompound t) {
        String n = t.getString("s");
        if (n.isEmpty() || ChatView.ignored(n)) return;
        int g = t.getByte("g");
        if (g > 0) GUI.put(n, g); else GUI.remove(n);
        boolean was = IDLE.contains(n), idle = t.getBoolean("i");
        if (idle) IDLE.add(n); else IDLE.remove(n);
        if (was != idle && ChatConfig.idleNotices && !t.getBoolean("q") && !ChatView.muted(n))
            ClientSide.info(idle ? "§7💤 " + n + " fell asleep" : "§7☀ " + n + " woke up");
    }

    static void reset() {
        GUI.clear(); IDLE.clear(); RATE.clear(); TAB.clear();
        srvGui = srvIdle = srvRate = false; srvIdleMinutes = 0;
        sentGui = 0; sentIdle = false;
    }

    // ------------------------------------------------------------------ tab list Zz (only touches names when it changes)
    private static void tabList(Minecraft mc) {
        if (mc.getConnection() == null) return;
        for (NetworkPlayerInfo info : mc.getConnection().getPlayerInfoMap()) {
            String n = info.getGameProfile().getName();
            ITextComponent[] st = TAB.get(n);
            if (ChatConfig.sleepTabList && IDLE.contains(n)) {
                if (st != null && info.getDisplayName() == st[1]) continue;
                ITextComponent own = info.getDisplayName();
                ITextComponent ours = (own != null ? own.createCopy() : new TextComponentString(ScorePlayerTeam.formatPlayerName(info.getPlayerTeam(), n)));
                ITextComponent zz = new TextComponentString(" Zz");
                zz.getStyle().setColor(TextFormatting.AQUA).setItalic(true);
                ours.appendSibling(zz);
                info.setDisplayName(ours);
                TAB.put(n, new ITextComponent[]{own, ours});
            } else if (st != null) {
                if (info.getDisplayName() == st[1]) info.setDisplayName(st[0]);
                TAB.remove(n);
            }
        }
    }

    // ------------------------------------------------------------------ sleeping pose (head droops while drawn, then put back)
    private static EntityPlayer drooped;
    private static float pitch, prevPitch;

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void pre(RenderPlayerEvent.Pre e) {
        EntityPlayer p = e.getEntityPlayer();
        if (!ChatConfig.showSleep || p == Minecraft.getMinecraft().player || !IDLE.contains(p.getName()) || GUI.containsKey(p.getName())) return;
        drooped = p; pitch = p.rotationPitch; prevPitch = p.prevRotationPitch;
        float nod = 50 + (float) Math.sin(System.currentTimeMillis() / 900.0 + p.getEntityId()) * 6;   // slow breathing nod
        p.rotationPitch = p.prevRotationPitch = nod;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void post(RenderPlayerEvent.Post e) {
        if (drooped != e.getEntityPlayer()) return;
        drooped.rotationPitch = pitch; drooped.prevRotationPitch = prevPitch;
        drooped = null;
    }

    // ------------------------------------------------------------------ drawing (from RenderLivingEvent.Specials.Post, main thread)
    static void render(EntityPlayer p, double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ChatConfig.enabled || p == mc.player || GUI.isEmpty() && IDLE.isEmpty()) return;
        if (p.getDistanceSq(mc.player) > (double) ChatConfig.bubbleDistance * ChatConfig.bubbleDistance) return;
        String n = p.getName();
        Integer g = GUI.get(n);
        boolean asleep = ChatConfig.showSleep && g == null && IDLE.contains(n);
        if (g == null && !asleep) return;
        float bx = OpenGlHelper.lastBrightnessX, by = OpenGlHelper.lastBrightnessY;
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, 240, 240);
        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
        GlStateManager.depthMask(false);
        if (g != null && ChatConfig.showGuiHolo) holo(mc, p, x, y, z, g);
        if (asleep) zzz(mc, p, x, y, z);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
        GlStateManager.enableCull();
        GlStateManager.color(1, 1, 1, 1);
        OpenGlHelper.setLightmapTextureCoords(OpenGlHelper.lightmapTexUnit, bx, by);
    }

    /** the little holo screen in front of their chest, facing them (readable over their shoulder) */
    private static void holo(Minecraft mc, EntityPlayer p, double x, double y, double z, int kind) {
        float pt = mc.getRenderPartialTicks();
        long now = System.currentTimeMillis();
        float yaw = p.prevRenderYawOffset + MathHelper.wrapDegrees(p.renderYawOffset - p.prevRenderYawOffset) * pt;
        float s = 1 / 64f;
        float flick = 0.85f + 0.15f * (float) Math.sin(now / 80.0 + p.getEntityId());
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + (p.isSneaking() ? 0.95f : 1.2f), (float) z);
        GlStateManager.rotate(-yaw, 0, 1, 0);
        GlStateManager.translate(0, 0, 0.55f);
        GlStateManager.rotate(-20, 1, 0, 0);                              // tipped back like a laptop lid
        GlStateManager.scale(-s, -s, s);
        GlStateManager.disableCull();
        int a = (int) (200 * flick);
        int w = 18, h = 12;
        Gui.drawRect(-w, -h, w, h, (int) (a * 0.35f) << 24 | 0x00E5FF);   // glass
        for (int yy = -h + 1; yy < h; yy += 3) Gui.drawRect(-w, yy, w, yy + 1, (int) (a * 0.15f) << 24 | 0x003040);   // scan lines
        int sweep = -h + (int) (now / 40 % (2 * h));
        Gui.drawRect(-w, sweep, w, sweep + 1, (int) (a * 0.5f) << 24 | 0x7FF7FF);
        int edge = a << 24 | 0x7FF7FF, pink = a << 24 | 0xF5A9B8;
        Gui.drawRect(-w, -h, w, -h + 1, edge); Gui.drawRect(-w, h - 1, w, h, edge);
        Gui.drawRect(-w, -h, -w + 1, h, pink); Gui.drawRect(w - 1, -h, w, h, pink);
        GlStateManager.translate(0, 0, -0.5f);                           // icon just in front of the glass
        pixels(ICONS[MathHelper.clamp(kind, 1, ICONS.length) - 1], -8, -8, 2, a);
        float sw = p.getSwingProgress(pt);                                // the cursor hops where they tap
        int cx = (int) (Math.sin(now / 700.0 + p.getEntityId()) * 12), cy = (int) (Math.cos(now / 530.0) * 7);
        int ca = sw > 0 ? 255 : a;
        Gui.drawRect(cx - 1, cy - 1, cx + 2, cy + 2, ca << 24 | 0xFFFFFF);
        if (sw > 0) { int r = 2 + (int) (sw * 6); int ra = (int) ((1 - sw) * 200) << 24 | 0xF5A9B8;
            Gui.drawRect(cx - r, cy - r, cx + r + 1, cy - r + 1, ra); Gui.drawRect(cx - r, cy + r, cx + r + 1, cy + r + 1, ra);
            Gui.drawRect(cx - r, cy - r, cx - r + 1, cy + r + 1, ra); Gui.drawRect(cx + r, cy - r, cx + r + 1, cy + r + 1, ra); }
        GlStateManager.popMatrix();
    }

    /** three Z's drifting up off their head, Pride blue / pink / white */
    private static final int[] ZCOL = {0x5BCEFA, 0xF5A9B8, 0xFFFFFF};

    private static void zzz(Minecraft mc, EntityPlayer p, double x, double y, double z) {
        FontRenderer fr = mc.fontRenderer;
        long now = System.currentTimeMillis();
        float lift = p.height + (p.isSneaking() ? 0.1f : 0.35f);
        float s = 0.025f;
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + lift, (float) z);
        GlStateManager.rotate(-mc.getRenderManager().playerViewY, 0, 1, 0);
        GlStateManager.rotate((mc.getRenderManager().options.thirdPersonView == 2 ? -1 : 1) * mc.getRenderManager().playerViewX, 1, 0, 0);
        GlStateManager.scale(-s, -s, s);
        GlStateManager.enableTexture2D();
        for (int i = 0; i < 3; i++) {
            float t = (float) ((now / 1800.0 + i / 3.0 + p.getEntityId() * 0.17) % 1.0);
            int alpha = (int) (255 * Math.min(1f, Math.min(t * 4, (1 - t) * 2)));
            if (alpha < 8) continue;
            float sc = 0.6f + t * 0.9f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(8 + t * 14 + (float) Math.sin(t * 6.3 + i) * 3, -t * 26, 0);
            GlStateManager.scale(sc, sc, 1);
            fr.drawString("Z", -2, -4, alpha << 24 | ZCOL[i]);
            GlStateManager.popMatrix();
        }
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ 8×8 pixel icons (our own), one draw call each
    private static final String[][] ICONS = {
            {"..pppp..", ".p....p.", "pppppppp", "pbbbbbbp", "pbbyybbp", "pbbbbbbp", "pbbbbbbp", "pppppppp"},   // 1 inventory: a satchel
            {"oooooooo", "onnnnnno", "onnnnnno", "ooowwooo", "onnwwnno", "onnnnnno", "onnnnnno", "oooooooo"},   // 2 chest
            {"ww.ww.ww", "ww.ww.ww", "........", "ww.yy.ww", "ww.yy.ww", "........", "ww.ww.ww", "ww.ww.ww"},   // 3 crafting grid
            {"gggggggg", "gddddddg", "gggggggg", "gd....dg", "gd.yo.dg", "gdyooydg", "gdoroodg", "gggggggg"},   // 4 furnace
            {"...cc...", ".c.cc.c.", "..cccc..", "cccwwccc", "cccwwccc", "..cccc..", ".c.cc.c.", "...cc..."},   // 5 any other container (modded)
            {"........", ".ww..ww.", ".ww..ww.", ".ww..ww.", ".ww..ww.", ".ww..ww.", ".ww..ww.", "........"},   // 6 some other screen
    };

    private static int pal(char c) {
        switch (c) {
            case 'p': return 0xF5A9B8; case 'b': return 0x5BCEFA; case 'y': return 0xFFED00; case 'o': return 0xFF8C00;
            case 'n': return 0x8B5A2B; case 'w': return 0xFFFFFF; case 'g': return 0x8A8499; case 'd': return 0x2A2438;
            case 'r': return 0xE40303; case 'c': return 0x7FF7FF;
            default: return -1;
        }
    }

    private static void pixels(String[] rows, int x0, int y0, int px, int a) {
        Tessellator t = Tessellator.getInstance();
        BufferBuilder b = t.getBuffer();
        GlStateManager.disableTexture2D();
        b.begin(GL11.GL_QUADS, DefaultVertexFormats.POSITION_COLOR);
        for (int r = 0; r < rows.length; r++) for (int c = 0; c < rows[r].length(); c++) {
            int col = pal(rows[r].charAt(c));
            if (col < 0) continue;
            int R = col >> 16 & 255, G = col >> 8 & 255, B = col & 255;
            double x = x0 + c * px, y = y0 + r * px;
            b.pos(x, y + px, 0).color(R, G, B, a).endVertex();
            b.pos(x + px, y + px, 0).color(R, G, B, a).endVertex();
            b.pos(x + px, y, 0).color(R, G, B, a).endVertex();
            b.pos(x, y, 0).color(R, G, B, a).endVertex();
        }
        t.draw();
        GlStateManager.enableTexture2D();
    }

    private Activity() {}
}
