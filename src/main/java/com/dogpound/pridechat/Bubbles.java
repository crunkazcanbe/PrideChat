package com.dogpound.pridechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.model.ModelHumanoidHead;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.client.renderer.entity.RenderManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.opengl.GL11;

import java.util.*;

/**
 * Speech bubbles + floating skin heads above players who talk (roadmap #4/#5/#6/#9):
 * newest bubble at the bottom, older ones stacked above and fading out, the talker's head on top — a real 3D head
 * that turns to look at whoever is talking to them (or, while they talk, at the nearest listener / at you), with a
 * tiny expression emoji picked from what they said. 12 bubble styles; each player picks their own (it travels with
 * their messages) and you can force one style for everyone. Reactions show on the bubble they were made on.
 * Whisper = small bubble, shout = big one. Typing dots while someone types. Emoji draw inside bubbles too.
 */
final class Bubbles {
    static final String[] STYLES = {"rounded", "square", "mc", "comic", "speech", "thought", "dark", "transparent", "glass", "pixel", "holo", "minimal"};

    static final class B {
        final String text, style; final long time, life, id; final int volume, band; final boolean mention;
        final NBTTagCompound ap;                                          // the talker's own look (#7)
        String fx = "";                                                   // their text effects (#47)
        final Map<String, Integer> reactions = new LinkedHashMap<>();
        long reacted;
        B(String text, int volume, int band, boolean mention, long id, String style, NBTTagCompound ap) {
            this.text = text; this.volume = volume; this.band = band; this.mention = mention; this.id = id; this.style = style;
            this.ap = ap == null ? new NBTTagCompound() : ap;
            time = System.currentTimeMillis();
            life = (this.ap.getInteger("bl") > 0 ? this.ap.getInteger("bl") : ChatConfig.bubbleSeconds) * 1000L + Math.min(8000, text.length() * 60L);
        }
    }

    private static final Map<String, LinkedList<B>> ALL = new HashMap<>();
    private static final Map<String, Float> YAW = new HashMap<>();          // head turning, smoothed
    private static final ModelHumanoidHead HEAD = new ModelHumanoidHead();

    static boolean hidden(String name) {
        for (String s : ChatConfig.hiddenBubbles) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** the last look each talker sent (their head keeps it between bubbles) */
    private static final Map<String, NBTTagCompound> LOOK = new HashMap<>();

    static int hex(NBTTagCompound ap, String k, int def) {
        String v = ap.getString(k);
        if (v.length() != 6) return def;
        try { return Integer.parseInt(v, 16); } catch (NumberFormatException e) { return def; }
    }

    static void add(String name, String text, int volume, int band, boolean mention, long id, String style, NBTTagCompound ap, String fx) {
        if (!ChatConfig.bubbles || text == null || text.trim().isEmpty()) return;
        com.dogpound.pridechat.api.BubbleEvent ev = new com.dogpound.pridechat.api.BubbleEvent(name, text, style, volume, id);
        if (net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(ev) || ev.text == null || ev.text.trim().isEmpty()) return;
        text = ev.text; style = ev.style; volume = ev.volume;
        String st = !"theirs".equals(ChatConfig.bubbleStyleOthers) ? ChatConfig.bubbleStyleOthers : style == null || style.isEmpty() ? ChatConfig.bubbleStyle : style;
        if (!Arrays.asList(STYLES).contains(st)) st = "rounded";
        LinkedList<B> l = ALL.computeIfAbsent(name.toLowerCase(Locale.ROOT), k -> new LinkedList<>());
        B nb = new B(text.length() > 600 ? text.substring(0, 600) + "…" : text, volume, band, mention, id, st, ap);
        nb.fx = fx == null ? "" : fx;
        l.add(nb);
        if (ap != null) LOOK.put(name.toLowerCase(Locale.ROOT), ap);
        while (l.size() > ChatConfig.bubbleStack) l.removeFirst();
    }

    /** a reaction on a message: show it on that message's bubble (and keep the bubble a little longer) */
    static void react(long id, String code) {
        if (id == 0) return;
        for (LinkedList<B> l : ALL.values()) for (B b : l) if (b.id == id) { b.reactions.merge(code, 1, Integer::sum); b.reacted = System.currentTimeMillis(); return; }
    }

    /** the little face next to the head, from how they talk */
    private static String expression(String t) {
        String s = t.toLowerCase(Locale.ROOT);
        if (s.matches(".*\\b(lol|lmao|haha+|xd|rofl)\\b.*") || s.contains(":joy:")) return "joy";
        if (s.contains(":(") || s.contains(":'(") || s.contains(":cry:") || s.contains(":sob:")) return "cry";
        if (s.contains("<3") || s.contains(":heart:") || s.contains("❤")) return "red_heart";
        if (s.contains("???")) return "question";
        if (s.contains("!!!")) return "open_mouth";
        if (s.contains(":)") || s.contains(":smile:")) return "slightly_smiling_face";
        return null;
    }

    /** who is talking near this player right now (the newest bubble that isn't theirs), or null */
    private static EntityPlayer talkingNear(EntityPlayer p, long now) {
        Minecraft mc = Minecraft.getMinecraft();
        EntityPlayer best = null;
        long bt = 0;
        for (EntityPlayer o : mc.world.playerEntities) {
            if (o == p || o.getDistanceSq(p) > (double) ChatConfig.bubbleDistance * ChatConfig.bubbleDistance) continue;
            LinkedList<B> l = ALL.get(o.getName().toLowerCase(Locale.ROOT));
            if (l == null || l.isEmpty() || now - l.getLast().time > 4000) continue;
            if (l.getLast().time > bt) { bt = l.getLast().time; best = o; }
        }
        return best;
    }

    private static EntityPlayer nearest(EntityPlayer p) {
        EntityPlayer best = null;
        double bd = (double) ChatConfig.bubbleDistance * ChatConfig.bubbleDistance;
        for (EntityPlayer o : Minecraft.getMinecraft().world.playerEntities) {
            if (o == p) continue;
            double d = o.getDistanceSq(p);
            if (d < bd) { bd = d; best = o; }
        }
        return best;
    }

    static boolean talking(String name) { return ALL.containsKey(name.toLowerCase(Locale.ROOT)); }

    static void render(net.minecraft.entity.EntityLivingBase p, double x, double y, double z) {
        Minecraft mc = Minecraft.getMinecraft();
        if (!ChatConfig.enabled) return;
        if (p == mc.player && !ChatConfig.bubbleOwn) return;
        if (mc.player != null && p.getDistanceSq(mc.player) > (double) ChatConfig.bubbleDistance * ChatConfig.bubbleDistance) return;
        String key = p.getName().toLowerCase(Locale.ROOT);
        LinkedList<B> list = ALL.get(key);
        long now = System.currentTimeMillis();
        if (list != null) { list.removeIf(b -> now - Math.max(b.time, b.reacted) > b.life); if (list.isEmpty()) { ALL.remove(key); list = null; } }
        boolean typing = ChatConfig.typingBubble && ClientSide.TYPING.containsKey(p.getName());
        boolean head = p instanceof EntityPlayer && (ChatConfig.bubbleHead && (list != null || typing) || ChatConfig.headAlways);   // NPCs: just the bubble
        if (list == null && !typing && !head) return;
        if (ChatView.ignored(p.getName()) || hidden(p.getName())) return;

        RenderManager rm = mc.getRenderManager();
        FontRenderer fr = mc.fontRenderer;
        float lift = p.height + 0.5f + 0.3f - (p.isSneaking() ? 0.25f : 0);
        float s = 0.025f * ChatConfig.bubbleScale / 100f;
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y + lift, (float) z);
        GL11.glNormal3f(0, 1, 0);
        GlStateManager.rotate(-rm.playerViewY, 0, 1, 0);
        GlStateManager.rotate((rm.options.thirdPersonView == 2 ? -1 : 1) * rm.playerViewX, 1, 0, 0);
        GlStateManager.scale(-s, -s, s);
        GlStateManager.disableLighting();
        GlStateManager.depthMask(false);
        if (ChatConfig.bubbleSeeThrough) GlStateManager.disableDepth();
        GlStateManager.enableBlend();
        GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);

        int bottom = 0;                                                   // y grows downwards; we stack upwards from 0
        if (typing && (list == null || now - list.getLast().time > 600)) bottom = typingDots(bottom, now, Activity.rate(p.getName()), p.getEntityId());
        String face = null;
        if (list != null) {
            for (Iterator<B> it = list.descendingIterator(); it.hasNext(); ) {
                B b = it.next();
                if (face == null) face = expression(b.text);
                long left = Math.max(b.time, b.reacted) + b.life - now;
                int a = left < 600 ? (int) (255 * left / 600) : 255;
                if (b != list.getLast()) a = a * 70 / 100;                  // older bubbles are fainter
                bottom = bubble(fr, b, bottom, Math.max(8, a));
            }
        }
        float bob = (float) Math.sin(now / 400.0 + p.getEntityId()) * 1.2f;
        boolean head3d = head && ChatConfig.head3d;
        NBTTagCompound look = LOOK.getOrDefault(key, new NBTTagCompound());
        float hs = look.hasKey("hs") ? MathHelper.clamp(look.getShort("hs"), 50, 300) / 100f : 1f;
        if (head) {
            GlStateManager.pushMatrix();
            GlStateManager.translate(0, bob, 0);
            int hb = hex(look, "hb", -1), half = Math.round(8 * hs), hh = Math.round(16 * hs);
            if (hb >= 0) {                                                // a frame around the head (#7)
                int x1 = -half - 2, y1 = bottom - 3 - hh - 2, x2 = half + 2, y2 = bottom - 1, c = 0xFF000000 | hb;
                rect(x1, y1, x2, y1 + 1, c); rect(x1, y2 - 1, x2, y2, c); rect(x1, y1, x1 + 1, y2, c); rect(x2 - 1, y1, x2, y2, c);
            }
            if (!head3d) ChatView.drawHead(p.getName(), -half, bottom - 3 - hh, hh, 255);
            if (face != null) { Emoji.E e = Emoji.byCode(face); if (e != null) { GlStateManager.color(1, 1, 1, 1); Emoji.draw(e, 6, bottom - 23, 9); } }
            GlStateManager.popMatrix();
        }
        if (mc.currentScreen instanceof GuiPrideChat) {                   // where it is on screen, for right-click (#43)
            int half = ChatConfig.bubbleWidth / 2 + 6;
            float[] a = project(-half, bottom - 22 * hs), b = project(half, 4);
            if (a != null && b != null) SCREEN.put(p.getName(), new float[]{Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.max(a[0], b[0]), Math.max(a[1], b[1]), now});
        }
        GlStateManager.enableDepth();
        GlStateManager.depthMask(true);
        GlStateManager.enableLighting();
        GlStateManager.disableBlend();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.popMatrix();
        if (head3d) head3d((EntityPlayer) p, x, y + lift + (-bottom + 3 - bob) * s, z, s * 32 * hs, now);
    }

    /** a real head (face + hat) that turns: toward whoever talks near them, else (while talking) toward a listener / you */
    private static void head3d(EntityPlayer p, double x, double y, double z, float size, long now) {
        Minecraft mc = Minecraft.getMinecraft();
        Entity cam = mc.getRenderViewEntity() == null ? mc.player : mc.getRenderViewEntity();
        double dx, dz;
        EntityPlayer target = null;
        if ("smart".equals(ChatConfig.headLook)) {
            target = talkingNear(p, now);
            if (target == null && ALL.containsKey(p.getName().toLowerCase(Locale.ROOT))) target = nearest(p);
        }
        if ("forward".equals(ChatConfig.headLook)) { dx = -MathHelper.sin(p.rotationYawHead * 0.017453292f); dz = MathHelper.cos(p.rotationYawHead * 0.017453292f); }
        else if (target != null && target != cam) { dx = target.posX - p.posX; dz = target.posZ - p.posZ; }
        else { dx = -x; dz = -z; }                                        // toward you (the camera)
        float want = (float) Math.toDegrees(Math.atan2(-dx, dz)) + 180f;
        String key = p.getName();
        Float cur = YAW.get(key);
        float yaw = cur == null ? want : cur + MathHelper.wrapDegrees(want - cur) * 0.15f;
        YAW.put(key, yaw);
        ResourceLocation skin = null;
        if (mc.getConnection() != null) { NetworkPlayerInfo info = mc.getConnection().getPlayerInfo(p.getName()); if (info != null) skin = info.getLocationSkin(); }
        if (skin == null) skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
        GlStateManager.pushMatrix();
        GlStateManager.translate((float) x, (float) y, (float) z);
        GlStateManager.scale(-size, -size, size);
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableAlpha();
        GlStateManager.enableCull();
        GlStateManager.color(1, 1, 1, 1);
        mc.getTextureManager().bindTexture(skin);
        HEAD.render(null, 0, 0, 0, yaw, 0, 0.0625f);
        GlStateManager.disableRescaleNormal();
        GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ right-click on a bubble / head (#43)
    /** name → {x1, y1, x2, y2, when} in GUI coords, while the chat is open */
    static final Map<String, float[]> SCREEN = new HashMap<>();
    private static final java.nio.FloatBuffer MV = org.lwjgl.BufferUtils.createFloatBuffer(16), PR = org.lwjgl.BufferUtils.createFloatBuffer(16);

    /** a point of the current (bubble) space → GUI coords, or null when behind the camera */
    private static float[] project(float x, float y) {
        GlStateManager.getFloat(GL11.GL_MODELVIEW_MATRIX, MV);
        GlStateManager.getFloat(GL11.GL_PROJECTION_MATRIX, PR);
        float[] v = new float[4], c = new float[4];
        for (int i = 0; i < 4; i++) v[i] = MV.get(i) * x + MV.get(4 + i) * y + MV.get(12 + i);
        for (int i = 0; i < 4; i++) c[i] = PR.get(i) * v[0] + PR.get(4 + i) * v[1] + PR.get(8 + i) * v[2] + PR.get(12 + i) * v[3];
        if (c[3] <= 0.01f) return null;
        Minecraft mc = Minecraft.getMinecraft();
        int f = new net.minecraft.client.gui.ScaledResolution(mc).getScaleFactor();
        return new float[]{(c[0] / c[3] + 1) / 2 * mc.displayWidth / f, (1 - c[1] / c[3]) / 2 * mc.displayHeight / f};
    }

    /** the talker whose bubble / floating head is under the mouse (drawn this last moment), or null */
    static String at(int mx, int my) {
        long now = System.currentTimeMillis();
        String best = null;
        float area = Float.MAX_VALUE;
        for (Map.Entry<String, float[]> e : SCREEN.entrySet()) {
            float[] r = e.getValue();
            if (now - r[4] > 250 || mx < r[0] || mx > r[2] || my < r[1] || my > r[3]) continue;
            float a = (r[2] - r[0]) * (r[3] - r[1]);
            if (a < area) { area = a; best = e.getKey(); }        // the nearest (smallest) one wins
        }
        return best;
    }

    // ------------------------------------------------------------------ one bubble in its style
    private static void rect(int x1, int y1, int x2, int y2, int c) { Gui.drawRect(x1, y1, x2, y2, c); }

    /** one bubble whose tail sits at y = bottom; returns the y above it */
    private static int bubble(FontRenderer fr, B b, int bottom, int a) {
        float size = b.volume == 0 ? 0.75f : b.volume == 2 ? 1.3f : b.volume >= 3 ? 1.8f : 1f;
        long now = System.currentTimeMillis(), age = now - b.time;
        String text = b.text;
        List<List<Object>> rows = wrap(fr, text.isEmpty() ? " " : text, (int) (ChatConfig.bubbleWidth / size));
        String kind = ChatConfig.bubbleAnimation;                         // mine, or the talker's own choice (#7/#8)
        if (!"none".equals(kind) && ChatConfig.othersLook && b.ap.hasKey("an")) kind = b.ap.getString("an");
        if ("theirs".equals(kind)) kind = "pop";
        int len = 0;
        for (List<Object> r : rows) for (Object o : r) len += o instanceof String ? ((String) o).length() : 1;
        float prog = Anim.progress(kind, age, len);
        int w = 0;
        for (List<Object> r : rows) w = Math.max(w, width(fr, r));
        int reactW = b.reactions.size() * 20;
        int lineH = 10, h = rows.size() * lineH + 6, bw = Math.max(w, reactW) + 10;
        GlStateManager.pushMatrix();
        GlStateManager.translate(0, bottom, 0);
        GlStateManager.scale(size, size, 1);
        int top = -h - 4, left = -bw / 2, right = left + bw, bot = top + h;
        if (prog < 1) {                                                   // the entrance (roadmap #8), pivot = the tail
            float[] mo = Anim.motion(kind, prog, bw, h);
            a = Math.max(8, (int) (a * mo[4]));
            GlStateManager.translate(mo[0], mo[1], 0);
            GlStateManager.scale(mo[2], mo[3], 1);
        }
        NBTTagCompound ap = b.ap;
        int al = (ap.hasKey("ba") ? ap.getByte("ba") : ChatConfig.bubbleOpacity) * 255 / 100 * a / 255;            // background alpha
        int edge = b.mention ? 0xFF4FB0 : hex(ap, "be", b.volume >= 2 ? 0xFFD700 : b.volume == 0 ? 0x8A8499 : 0xF5A9B8);
        int fill = hex(ap, "bc", b.volume == 0 ? 0x2A2438 : 0x140E22);
        int paper = hex(ap, "bc", -1);                                    // a chosen colour for the light styles too
        int textCol = 0xFFFFFF;
        boolean shadow = false;
        switch (b.style) {
            case "square":
                rect(left, top, right, bot, al << 24 | fill);
                rect(left, top, right, top + 1, a << 24 | edge); rect(left, bot - 1, right, bot, a << 24 | edge);
                rect(left, top, left + 1, bot, a << 24 | edge); rect(right - 1, top, right, bot, a << 24 | edge);
                tail(bot, al << 24 | fill);
                break;
            case "mc": {                                                  // Minecraft's tooltip
                int bg = Math.min(240, al) << 24 | 0x100010;
                rect(left + 1, top, right - 1, bot, bg); rect(left, top + 1, right, bot - 1, bg);
                int b1 = a * 80 / 255 << 24 | 0x5000FF, b2 = a * 80 / 255 << 24 | 0x28007F;
                rect(left + 1, top + 1, right - 1, top + 2, b1); rect(left + 1, bot - 2, right - 1, bot - 1, b2);
                rect(left + 1, top + 2, left + 2, bot - 2, b1); rect(right - 2, top + 2, right - 1, bot - 2, b1);
                tail(bot, bg);
                break;
            }
            case "comic": {
                int ink = a << 24;
                int pc = paper >= 0 ? paper : 0xFFFFFF;
                rect(left - 1, top - 1, right + 1, bot + 1, ink); rect(left, top, right, bot, a << 24 | pc);
                for (int i = 0; i < 4; i++) rect(-5 + i, bot + i, 2 - i, bot + i + 1, ink);
                for (int i = 0; i < 3; i++) rect(-4 + i, bot + i, 1 - i, bot + i + 1, a << 24 | pc);
                textCol = light(pc) ? 0x111111 : 0xFFFFFF;
                break;
            }
            case "speech": case "thought": {
                int pc = paper >= 0 ? paper : 0xF4F0FA;
                int c = Math.max(al, 200 * a / 255) << 24 | pc;
                rect(left + 2, top, right - 2, bot, c); rect(left + 1, top + 1, right - 1, bot - 1, c); rect(left, top + 2, right, bot - 2, c);
                if ("speech".equals(b.style)) tail(bot, c);
                else { rect(-2, bot + 2, 2, bot + 5, c); rect(-4, bot + 7, -2, bot + 9, c); }
                textCol = light(pc) ? 0x2A2438 : 0xFFFFFF;
                break;
            }
            case "dark":
                rect(left + 1, top, right - 1, bot, Math.min(255, al + 40) << 24); rect(left, top + 1, right, bot - 1, Math.min(255, al + 40) << 24);
                tail(bot, Math.min(255, al + 40) << 24);
                textCol = 0xD8D8D8;
                break;
            case "transparent":
                shadow = true;
                break;
            case "glass": {
                int c = al * 35 / 100 << 24 | 0xFFFFFF;
                rect(left + 1, top, right - 1, bot, c); rect(left, top + 1, right, bot - 1, c);
                rect(left + 1, top, right - 1, top + 1, a * 70 / 100 << 24 | 0xFFFFFF);
                rect(left + 1, bot - 1, right - 1, bot, a * 35 / 100 << 24 | 0xFFFFFF);
                tail(bot, c);
                shadow = true;
                break;
            }
            case "pixel": {
                int c = al << 24 | fill, e = a << 24 | edge;
                rect(left + 2, top - 2, right - 2, bot + 2, e); rect(left, top, right, bot, e);
                rect(left + 2, top, right - 2, bot, c); rect(left, top + 2, right, bot - 2, c);
                rect(-4, bot + 2, 4, bot + 4, e); rect(-2, bot + 4, 2, bot + 6, e);
                break;
            }
            case "holo": {
                float flick = 0.85f + 0.15f * (float) Math.sin(now / 90.0 + b.id);
                int fa = (int) (al * 45 / 100 * flick);
                rect(left, top, right, bot, fa << 24 | 0x00E5FF);
                for (int yy = top + 1; yy < bot; yy += 2) rect(left, yy, right, yy + 1, fa / 2 << 24 | 0x003040);
                int e = (int) (a * flick) << 24 | 0x7FF7FF;
                rect(left, top, right, top + 1, e); rect(left, bot - 1, right, bot, e);
                rect(left, top, left + 1, top + 3, e); rect(right - 1, top, right, top + 3, e);
                rect(left, bot - 3, left + 1, bot, e); rect(right - 1, bot - 3, right, bot, e);
                textCol = 0xB8FBFF;
                shadow = true;
                break;
            }
            case "minimal":
                rect(-w / 2, bot - 1, w / 2, bot, a * 60 / 100 << 24 | edge);
                shadow = true;
                break;
            default:                                                       // rounded
                rect(left + 1, top, right - 1, bot, al << 24 | fill);
                rect(left, top + 1, right, bot - 1, al << 24 | fill);
                rect(left + 1, top, right - 1, top + (b.mention ? 2 : 1), a << 24 | edge);   // top edge (thicker when it's about you)
                tail(bot, al << 24 | fill);
        }
        int bt = ap.hasKey("be") ? Math.max(1, ap.getByte("bt")) : 0;     // a chosen border, any style (#7)
        if (bt > 0) {
            int e = a << 24 | edge;
            rect(left - bt, top - bt, right + bt, top, e); rect(left - bt, bot, right + bt, bot + bt, e);
            rect(left - bt, top, left, bot, e); rect(right, top, right + bt, bot, e);
        }
        if (paper < 0 && ap.hasKey("bc") && light(fill) && !"comic".equals(b.style)) textCol = 0x1A1A1A;
        GlStateManager.enableBlend();
        if (prog < 1 && "hologram".equals(kind)) {
            rect(left, top, right, bot, (a * 60 / 255) << 24 | 0x00E5FF);
            int sy = top + (int) (prog * h * 3) % h;
            rect(left, sy, right, sy + 1, (a * 150 / 255) << 24 | 0x7FF7FF);
        }
        int ty = top + 4, gi = 0;
        long seed = b.id ^ b.time;
        int textA = Math.max(8, b.band >= 2 ? a * 50 / 100 : b.band == 1 ? a * 75 / 100 : a);
        for (List<Object> r : rows) {
            int x = -width(fr, r) / 2;
            for (Object o : r) {
                if (o instanceof String) {
                    String s = prog < 1 ? Anim.reveal((String) o, kind, prog, gi, len, seed) : (String) o;
                    if (b.fx.contains("type")) s = Anim.reveal(s, "typewriter", Math.min(1f, age / (45f * Math.max(1, len))), gi, len, seed);
                    Anim.text(fr, s, x, ty, textA << 24 | textCol, shadow, b.fx, gi);
                    gi += ((String) o).length();
                    x += fr.getStringWidth((String) o);
                }
                else {
                    if (!Anim.hidden(kind, prog, gi, len, seed)) { GlStateManager.color(1, 1, 1, textA / 255f); Emoji.draw((Emoji.E) o, x, ty - 1, 9); GlStateManager.enableBlend(); }
                    gi++; x += 10;
                }
            }
            ty += lineH;
        }
        if (!b.reactions.isEmpty()) {                                      // ❤ 2 😂 1 sitting on the bubble's bottom edge
            float pop = now - b.reacted < 200 ? 1.3f - 0.3f * (now - b.reacted) / 200f : 1f;
            int rx = right - reactW - 2, ry = bot - 5;
            for (Map.Entry<String, Integer> en : b.reactions.entrySet()) {
                rect(rx, ry, rx + 19, ry + 10, a * 90 / 100 << 24 | 0x3A2A58);
                Emoji.E e = Emoji.byCode(en.getKey());
                GlStateManager.pushMatrix();
                GlStateManager.translate(rx + 5, ry + 5, 0);
                GlStateManager.scale(pop, pop, 1);
                GlStateManager.color(1, 1, 1, a / 255f);
                if (e != null) Emoji.draw(e, -4, -4, 8);
                GlStateManager.popMatrix();
                GlStateManager.enableBlend();
                fr.drawString(String.valueOf(en.getValue()), rx + 11, ry + 1, a << 24 | 0xFFFFFF);
                rx += 20;
            }
        }
        GlStateManager.popMatrix();
        return bottom + (int) ((top - 3 - ("thought".equals(b.style) ? 4 : 0)) * size);
    }

    /** a light colour (dark text reads better on it) */
    static boolean light(int c) { return ((c >> 16 & 255) * 299 + (c >> 8 & 255) * 587 + (c & 255) * 114) / 1000 > 150; }

    private static void tail(int bot, int c) {
        rect(-3, bot, 3, bot + 1, c);
        rect(-2, bot + 1, 2, bot + 2, c);
        rect(-1, bot + 2, 1, bot + 3, c);
    }

    /** the typing dots; rate = their real keys per second — faster dots, and (typingWacky) bouncier, shakier, rainbow */
    private static int typingDots(int bottom, long now, int rate, int seed) {
        int period = Activity.period(rate);
        int phase = (int) (now / period % 3);
        boolean wacky = ChatConfig.typingWacky && rate >= 6;
        int amp = wacky ? Math.min(4, (rate - 3) / 3) : 0;
        int w = wacky && rate >= 18 ? 28 : 22, h = 10, top = bottom - h - 4;
        GlStateManager.pushMatrix();
        if (wacky && rate >= 12) {                                        // flat out: the whole bubble wobbles
            GlStateManager.translate(0, top + h, 0);
            GlStateManager.rotate((float) Math.sin(now / 45.0 + seed) * Math.min(12, rate - 10), 0, 0, 1);
            GlStateManager.translate(0, -(top + h), 0);
        }
        Gui.drawRect(-w / 2 + 1, top, w / 2 - 1, top + h, 0xC0140E22);
        Gui.drawRect(-w / 2, top + 1, w / 2, top + h - 1, 0xC0140E22);
        int step = w == 22 ? 6 : 8;
        for (int i = 0; i < 3; i++) {
            int dx = -step + i * step;
            int dy = amp == 0 ? 0 : (int) Math.round(Math.sin(now / (period / 2.0) + i * 2.1) * amp);
            int c = !wacky ? (i == phase ? 0xFFF5A9B8 : 0xFF8A8499)
                    : rate >= 18 ? 0xFF000000 | Activity.PRIDE[(int) ((now / period + i) % Activity.PRIDE.length)]
                    : i == phase ? 0xFFF5A9B8 : 0xFF5BCEFA;
            Gui.drawRect(dx - 1, top + 4 + dy, dx + 1, top + 6 + dy, c);
        }
        GlStateManager.popMatrix();
        return top - 3;
    }

    private static int width(FontRenderer fr, List<Object> r) {
        int w = 0;
        for (Object o : r) w += o instanceof String ? fr.getStringWidth((String) o) : 10;
        return w;
    }

    /** words and emoji into rows no wider than max; at most bubbleMaxLines rows (the last ends in …) */
    private static List<List<Object>> wrap(FontRenderer fr, String text, int max) {
        List<List<Object>> rows = new ArrayList<>();
        List<Object> cur = new ArrayList<>();
        int cw = 0;
        for (Emoji.Piece p : Emoji.split(text.replace('\n', ' '))) {
            if (p.emoji != null) {
                if (cw + 10 > max && cw > 0) { rows.add(cur); cur = new ArrayList<>(); cw = 0; }
                cur.add(p.emoji); cw += 10;
                continue;
            }
            for (String word : p.text.split("(?<= )")) {
                int ww = fr.getStringWidth(word);
                if (cw + ww > max && cw > 0) { rows.add(cur); cur = new ArrayList<>(); cw = 0; }
                while (ww > max) {
                    String part = fr.trimStringToWidth(word, max);
                    if (part.isEmpty()) break;
                    cur.add(part); rows.add(cur); cur = new ArrayList<>(); cw = 0;
                    word = word.substring(part.length()); ww = fr.getStringWidth(word);
                }
                cur.add(word); cw += ww;
            }
        }
        if (!cur.isEmpty()) rows.add(cur);
        if (rows.size() > ChatConfig.bubbleMaxLines) {
            rows = new ArrayList<>(rows.subList(0, ChatConfig.bubbleMaxLines));
            rows.get(rows.size() - 1).add("…");
        }
        return rows;
    }

    private Bubbles() {}
}
