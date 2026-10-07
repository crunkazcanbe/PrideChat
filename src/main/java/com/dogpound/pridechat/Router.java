package com.dogpound.pridechat;

import com.dogpound.pridechat.api.PrideChatEvent;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.play.client.CPacketChatMessage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatAllowedCharacters;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.*;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.common.network.handshake.NetworkDispatcher;
import net.minecraftforge.server.permission.PermissionAPI;

import java.util.*;

/**
 * The server half of "chat awareness" (roadmap #50): every message becomes a Msg with sender, mode, channel,
 * dimension, position, range, volume and priority; this router decides WHO gets it and HOW (distance band, walls,
 * garbling), and ships the metadata with the line so each PrideChat client draws it its own way (bubbles, faded
 * text, tabs). Players without PrideChat get an ordinary chat line, so mixed servers just work.
 */
public final class Router {
    public static final String WHISPER = "whisper", LOCAL = "local", SHOUT = "shout", GLOBAL = "global", CHANNEL = "channel",
            DM = "dm", EMOTE = "emote", ANNOUNCE = "announce", SYSTEM = "system", BOT = "bot",
            ZONE = "zone", DIM = "dim";

    /** one message on its way (roadmap #50) */
    public static final class Msg {
        public final long id = NEXT_ID++;
        public EntityPlayerMP sender;
        public String text, mode, channel = "", target = "";
        public int range, priority;
        public boolean ping;            // @everyone / @here
        public boolean shadow;          // only the sender sees it (shadow mute)
        public ITextComponent formatted; // after ServerChatEvent (ranks, colours from other mods)
        public boolean anonymous;       // /anon (roadmap #26)
        public long re;                 // answers message `re` (threads, roadmap #32)
        public String reName = "", reText = "";
        public int volume = -1;
        public String fx = "";          // text effects the sender may use: glow, shadow, color, type (roadmap #47)
        public Collection<EntityPlayerMP> only;   // if set, only these players get it (selectors like @radius, roadmap #45)
        boolean quiet;                  // the Mention event was cancelled for the listener being sent to right now         // bubble size for listeners: 0 tiny, 1 normal, 2 big, 3 giant (-1 = from the mode)
    }

    /** true while our own ServerChatEvent fires (so PrideChat's vanilla-path checks don't run twice) */
    static boolean inRoute;
    private static long NEXT_ID = System.currentTimeMillis() & 0xFFFFFFFFFFL;
    /** who got a message, for reactions (last 500) */
    static final LinkedHashMap<Long, List<UUID>> RECIPIENTS = new LinkedHashMap<Long, List<UUID>>() {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, List<UUID>> e) { return size() > 500; }
    };
    /** chunks being put back together: player → {id, next index, total, mode, range, target, text} */
    private static final Map<UUID, Object[]> PARTIAL = new HashMap<>();
    /** recent public lines for players who join later (roadmap #14) */
    static final LinkedList<Object[]> HISTORY = new LinkedList<>();
    private static final Map<UUID, Long> LAST_SENT = new HashMap<>();
    private static final Map<UUID, LinkedList<Long>> RATE = new HashMap<>();

    /** players whose client said hello (singleplayer / when FML's mod list isn't there) */
    private static final Set<UUID> HELLO = new HashSet<>();

    public static boolean hasPrideChat(EntityPlayerMP p) {
        if (HELLO.contains(p.getUniqueID())) return true;
        try {
            NetworkDispatcher d = NetworkDispatcher.get(p.connection.netManager);
            return d != null && d.getModList() != null && d.getModList().containsKey(PrideChat.MODID);
        } catch (Throwable t) { return false; }
    }

    static MinecraftServer server() { return FMLCommonHandler.instance().getMinecraftServerInstance(); }

    // ------------------------------------------------------------------ packets from clients
    static void fromClient(EntityPlayerMP p, Net.Pkt m) {
        NBTTagCompound t = m.tag;
        switch (m.type) {
            case Net.C_CHUNK: chunk(p, t, m.text); break;
            case Net.C_TYPING: typing(p, t); break;
            case Net.C_READ: {
                EntityPlayerMP from = server().getPlayerList().getPlayerByUsername(t.getString("from"));
                if (from != null && hasPrideChat(from)) { NBTTagCompound o = new NBTTagCompound(); o.setString("by", p.getName()); Net.send(from, Net.S_READ, o, ""); }
                break;
            }
            case Net.C_REACT: react(p, t.getLong("id"), m.text); break;
            case Net.C_AI: AI.fromClient(p, t, m.text); break;
            case Net.C_HELLO: if (HELLO.add(p.getUniqueID())) hello(p); break;
            case Net.C_ANSWER: Devices.answered(p, m.text); break;
            case Net.C_STATUS: status(p, t); break;
            default: break;
        }
    }

    private static void chunk(EntityPlayerMP p, NBTTagCompound t, String piece) {
        int id = t.getInteger("id"), idx = t.getInteger("i"), total = t.getInteger("n");
        int maxChunks = ChatConfig.server.maxLength / Net.CHUNK + 2;
        if (total < 1 || total > maxChunks || piece.length() > Net.CHUNK) { PARTIAL.remove(p.getUniqueID()); return; }
        Object[] st = PARTIAL.get(p.getUniqueID());
        if (idx == 0) { st = new Object[]{id, 0, total, new StringBuilder()}; PARTIAL.put(p.getUniqueID(), st); }
        if (st == null || (int) st[0] != id || (int) st[1] != idx) { PARTIAL.remove(p.getUniqueID()); return; }
        ((StringBuilder) st[3]).append(piece);
        st[1] = idx + 1;
        if (idx + 1 < total) return;
        PARTIAL.remove(p.getUniqueID());
        String bs = t.getString("bs");
        if (bs.matches("[a-z]{2,12}")) STYLE.put(p.getUniqueID(), bs);
        String rp = t.getString("rp");
        if (Arrays.asList(RP_MODES).contains(rp)) RPMODE.put(p.getUniqueID(), rp);
        if (t.hasKey("ap", 10)) APPEAR.put(p.getUniqueID(), appearance(p, t.getCompoundTag("ap")));
        reply = t.getLong("re") == 0 ? null : t;
        String text = ((StringBuilder) st[3]).toString();
        if (text.length() > ChatConfig.server.maxLength) text = text.substring(0, ChatConfig.server.maxLength);
        try { handle(p, text, t.getString("mode"), t.getInteger("range"), t.getString("target")); } finally { reply = null; }
    }

    /** the reply fields of the message being handled right now (set by chunk) */
    private static NBTTagCompound reply;

    /** strip what the vanilla server would kick for (control characters, §) — keeps new lines */
    static String clean(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int lines = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\n') { if (++lines <= 30) b.append(c); continue; }
            if (ChatAllowedCharacters.isAllowedCharacter(c)) b.append(c);
        }
        return b.toString().trim();
    }

    static final java.util.regex.Pattern FX = java.util.regex.Pattern.compile("^\\[(glow|shadow|color|anim|type|typewriter)\\]\\s*", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** leading [glow] [shadow] [color] [type] tags → {effects allowed for this player, the text without them} (#47) */
    static String[] effects(EntityPlayerMP p, String text) {
        Set<String> fx = new LinkedHashSet<>();
        java.util.regex.Matcher mt;
        while ((mt = FX.matcher(text)).find()) {
            String k = mt.group(1).toLowerCase(Locale.ROOT);
            fx.add("anim".equals(k) ? "color" : "typewriter".equals(k) ? "type" : k);
            text = text.substring(mt.end());
        }
        if (fx.isEmpty()) return new String[]{"", text};
        if (!ChatConfig.server.textEffects || !perm(p, "pridechat.effects")) { tell(p, "§8(text effects aren't allowed for you here — sent plain)"); return new String[]{"", text}; }
        fx.retainAll(Arrays.asList(ChatConfig.server.allowedEffects));
        return new String[]{String.join(",", fx), text};
    }

    /** a whole message from a PrideChat client */
    static void handle(EntityPlayerMP p, String raw, String mode, int range, String target) {
        String text = clean(raw);
        if (text.isEmpty()) return;
        String[] fx = text.startsWith("/") ? new String[]{"", text} : effects(p, text);
        text = fx[1].trim();
        if (text.isEmpty()) return;
        p.markPlayerActive();
        if (text.startsWith("/")) { runCommand(p, text.replace('\n', ' ')); return; }
        if (!gate(p)) return;
        if (mode == null || mode.isEmpty()) mode = LOCAL;
        if (GLOBAL.equals(mode) && !ChatConfig.server.allowGlobal && !perm(p, "pridechat.global.always")) {
            tell(p, "§cEveryone-chat is off on this server — your message went to the people near you.");
            mode = LOCAL;
        }
        if (SHOUT.equals(mode) && !perm(p, "pridechat.shout")) mode = LOCAL;
        if (LOCAL.equals(mode) && ChatConfig.server.sneakWhisper && p.isSneaking()) mode = WHISPER;
        Msg m = new Msg();
        m.sender = p; m.text = text; m.mode = mode; m.target = target == null ? "" : target; m.fx = fx[0];
        m.range = rangeFor(mode, range);
        if (reply != null) {
            m.re = reply.getLong("re");
            m.reName = clean(reply.getString("rs")).replace('\n', ' ');
            m.reText = clean(reply.getString("rt")).replace('\n', ' ');
            if (m.reName.length() > 32) m.reName = m.reName.substring(0, 32);
            if (m.reText.length() > 90) m.reText = m.reText.substring(0, 90);
        }
        if (CHANNEL.equals(mode)) {
            Channels.Ch ch = Channels.get(m.target);
            if (ch == null || !ch.isMember(p)) { tell(p, "§cYou're not in #" + m.target + " (/ch join " + m.target + ")"); return; }
            m.channel = ch.name;
            if (ch.range > 0) m.range = ch.range;
        }
        if (ZONE.equals(mode)) {                                       // everyone in the same zone (roadmap #16)
            m.channel = Rooms.zoneOf(p);
            if (m.channel.isEmpty()) { tell(p, "§cYou're not in a zone (a town or area made with the Room Marker)."); return; }
        }
        if (DIM.equals(mode)) m.channel = "dim" + p.dimension;           // everyone in this dimension
        if (WHISPER.equals(m.mode) && ChatConfig.server.whisperFacing) {   // looking at someone close: whisper to just them (#21)
            EntityPlayerMP f = facing(p, ChatConfig.server.whisperFacingRange);
            m.target = f == null ? "" : f.getName();
        }
        Contexts.apply(m);                                              // underwater blub, on fire, sprinting… (#25)
        Devices.megaphone(m);
        if (m.range > 0) { PrideChatEvent.Range rv = new PrideChatEvent.Range(m, m.range); MinecraftForge.EVENT_BUS.post(rv); m.range = Math.max(0, rv.range); }
        route(m);
    }

    /** the player this one is looking at, within range (a 20° cone from the eyes), or null */
    static EntityPlayerMP facing(EntityPlayerMP p, int range) {
        Vec3d eye = p.getPositionEyes(1), look = p.getLook(1);
        EntityPlayerMP best = null;
        double bestDot = Math.cos(Math.toRadians(20));
        for (EntityPlayerMP r : server().getPlayerList().getPlayers()) {
            if (r == p || r.dimension != p.dimension || r.isSpectator()) continue;
            Vec3d to = r.getPositionEyes(1).subtract(eye);
            double d = to.lengthVector();
            if (d > range + 0.6 || d < 0.1) continue;
            double dot = to.normalize().dotProduct(look);
            if (dot > bestDot) { bestDot = dot; best = r; }
        }
        return best;
    }

    /** a long command (>256): straight into the normal command path, with the full text */
    static void runCommand(EntityPlayerMP p, String cmd) {
        CPacketChatMessage pkt = new CPacketChatMessage("/");
        ObfuscationReflectionHelper.setPrivateValue(CPacketChatMessage.class, pkt, cmd, "field_149440_a");
        p.connection.processChatMessage(pkt);
    }

    /** mutes, slow mode and the flood guard — false = don't send */
    static boolean gate(EntityPlayerMP p) {
        if (Moderation.muted(p)) { tell(p, "§cYou're muted" + Moderation.mutedFor(p) + "."); Stats.blocked("muted"); return false; }
        boolean op = server().getPlayerList().canSendCommands(p.getGameProfile());
        long now = System.currentTimeMillis();
        if (!op && ChatConfig.server.slowMode > 0) {
            Long last = LAST_SENT.get(p.getUniqueID());
            if (last != null && now - last < ChatConfig.server.slowMode * 1000L) {
                tell(p, "§eSlow mode: wait " + (ChatConfig.server.slowMode - (now - last) / 1000) + " s.");
                Stats.blocked("slowmode");
                return false;
            }
        }
        LinkedList<Long> r = RATE.computeIfAbsent(p.getUniqueID(), k -> new LinkedList<>());
        while (!r.isEmpty() && now - r.getFirst() > 5000) r.removeFirst();
        if (!op && r.size() >= ChatConfig.server.floodLimit) { tell(p, "§eSlow down a little ♥"); Stats.blocked("flood"); return false; }
        r.add(now);
        LAST_SENT.put(p.getUniqueID(), now);
        return true;
    }

    public static int rangeFor(String mode, int asked) {
        int max = ChatConfig.server.maxRange;
        int def;
        switch (mode) {
            case WHISPER: def = ChatConfig.whisperRange; break;
            case SHOUT: def = ChatConfig.shoutRange; break;
            case LOCAL: case EMOTE: def = ChatConfig.localRange; break;
            default: return 0;                                         // 0 = no distance limit
        }
        int r = asked > 0 ? asked : def;
        return MathHelper.clamp(r, 1, max);
    }

    // ------------------------------------------------------------------ the router
    public static void route(Msg m) {
        EntityPlayerMP p = m.sender;
        PrideChatEvent.Send ev = new PrideChatEvent.Send(m);
        if (MinecraftForge.EVENT_BUS.post(ev)) return;
        Moderation.Verdict v = filter(m, p, m.text);
        if (v.block) { if (v.tell != null) tell(p, v.tell); Stats.blocked("moderation"); return; }
        if (v.shadow) Stats.blocked("shadow");
        m.text = v.text;
        m.shadow |= v.shadow;
        if (AI.holdForReview()) {                                      // optional: let the AI look first (never on this thread)
            AI.review(p, m.text, ok -> { if (ok) deliver(m); else tell(p, "§eYour message is waiting for a moderator."); });
            return;
        }
        deliver(m);
        AI.secondOpinion(p, m.text);
    }

    /** moderation + the Filter event (other mods may change the verdict or wave it through) */
    public static Moderation.Verdict filter(Msg m, EntityPlayerMP p, String text) {
        Moderation.Verdict v = Moderation.check(p, text);
        PrideChatEvent.Filter fe = new PrideChatEvent.Filter(m, p, text, v);
        if (MinecraftForge.EVENT_BUS.post(fe)) { Moderation.Verdict ok = new Moderation.Verdict(); ok.text = text; return ok; }
        return fe.verdict;
    }

    /** is this player named in the text (@name, the name as a word, @here, @everyone)? */
    static boolean mentions(String text, EntityPlayerMP r, boolean ping) {
        if (ping) return true;
        String t = text.toLowerCase(Locale.ROOT);
        if (t.contains("@here")) return true;
        return java.util.regex.Pattern.compile("(?<![a-z0-9_])@?" + java.util.regex.Pattern.quote(r.getName().toLowerCase(Locale.ROOT)) + "(?![a-z0-9_])").matcher(t).find();
    }

    static void deliver(Msg m) {
        EntityPlayerMP p = m.sender;
        MinecraftServer srv = server();
        if (DM.equals(m.mode)) { dm(m); return; }
        // the line everyone sees (other mods may restyle or cancel it through ServerChatEvent)
        ITextComponent body = body(p, m.text);
        if (!look(p, "mc").isEmpty()) body.getStyle().setColor(nearest(look(p, "mc")));
        if (ChatConfig.server.rangeStyle && WHISPER.equals(m.mode)) body.getStyle().setItalic(true);
        if (ChatConfig.server.rangeStyle && SHOUT.equals(m.mode)) body.getStyle().setBold(true);
        ITextComponent name = nameOf(m);
        ITextComponent line;
        if (EMOTE.equals(m.mode)) line = new TextComponentTranslation("chat.type.emote", name, body);
        else if (ANNOUNCE.equals(m.mode)) line = new TextComponentString(colors(ChatConfig.server.announceTag)).appendSibling(body);
        else {
            inRoute = true;
            ITextComponent rp = rpLine(m, name);
            ITextComponent plainLine = rp != null ? rp : new TextComponentTranslation("chat.type.text", name, body);
            try { line = m.anonymous || NICKS.containsKey(p.getUniqueID()) ? plainLine : ForgeHooks.onServerChatEvent(p.connection, m.text, plainLine); }
            finally { inRoute = false; }
            if (line == null) return;                                  // cancelled (muted by another mod…)
        }
        String tag = tagFor(m);
        if (m.only != null && !ANNOUNCE.equals(m.mode)) tag = "§d[✉ " + m.only.size() + "] " + tag;   // to a selector's people (#45)
        if (!tag.isEmpty() && !look(p, "cc").isEmpty()) tag = nearest(look(p, "cc")) + TextFormatting.getTextWithoutFormattingCodes(tag);
        ITextComponent full = tag.isEmpty() ? line : new TextComponentString(tag).appendSibling(line);
        m.formatted = full;
        if (m.text.toLowerCase(Locale.ROOT).contains("@everyone") && perm(p, "pridechat.mention.everyone")) m.ping = true;

        List<EntityPlayerMP> to = new ArrayList<>();
        int heard = 0;
        boolean muffled = p.isInsideOfMaterial(Material.WATER);
        BlockPos myPortal = null;
        for (EntityPlayerMP r : srv.getPlayerList().getPlayers()) {
            boolean self = r == p;
            if (m.shadow && !self) continue;
            if (m.only != null && !self && !m.only.contains(r)) continue;
            float dist = (float) Math.sqrt(r.getDistanceSq(p));
            int band = 0;
            if (CHANNEL.equals(m.mode)) {
                Channels.Ch ch = Channels.get(m.channel);
                if (ch == null || !ch.isMember(r)) continue;
                if (ch.range > 0 && !self && (r.dimension != p.dimension || dist > ch.range)) continue;
                if (ch.dim != Integer.MIN_VALUE && r.dimension != ch.dim) continue;
                if (!ch.key.isEmpty() && !self && !carries(r, ch.key)) { send(r, m, scrambled(tag, full), 2, dist, false); to.add(r); continue; }
            } else if (ZONE.equals(m.mode)) {
                if (!self && !m.channel.equals(Rooms.zoneOf(r))) continue;
            } else if (DIM.equals(m.mode)) {
                if (!self && r.dimension != p.dimension) continue;
            } else if (m.range > 0 && !self) {                         // ranged: same world, inside the (wall-reduced) range
                if (r.dimension != p.dimension) {                      // …or through a linked Nether portal (#38)
                    if (!ChatConfig.server.portalChat || WHISPER.equals(m.mode) || r.dimension + p.dimension != -1 || r.dimension * p.dimension != 0) continue;
                    if (myPortal == null) myPortal = portalNear(p);
                    BlockPos theirs = myPortal == NOWHERE ? null : portalNear(r);
                    if (theirs == null || theirs == NOWHERE || !linked(myPortal, p.dimension, theirs)) continue;
                    PrideChatEvent.Deliver dv = new PrideChatEvent.Deliver(m, r, 1, dist);
                    if (MinecraftForge.EVENT_BUS.post(dv)) continue;
                    heard++;
                    to.add(r);
                    ITextComponent through = new TextComponentString("§5[🌀] ").appendSibling(dv.band >= 2 ? garbled(tag, name, m.text, false) : full.createCopy());
                    if (dv.band < 2) through.getStyle().setColor(TextFormatting.GRAY);
                    send(r, m, through, Math.max(1, dv.band), dist, false);
                    continue;
                }
                if (WHISPER.equals(m.mode) && !m.target.isEmpty() && !r.getName().equals(m.target)) continue;   // a facing whisper
                double eff = reach(p, r, m.range);
                if (dist > eff) continue;
                double f = dist / eff;
                band = !ChatConfig.server.falloff ? 0 : f <= 0.6 ? 0 : f <= 0.85 ? 1 : 2;
                if (muffled) band = Math.max(band, 1);
            }
            PrideChatEvent.Deliver dv = new PrideChatEvent.Deliver(m, r, band, dist);
            if (MinecraftForge.EVENT_BUS.post(dv)) continue;
            band = dv.band;
            if (!self) heard++;
            to.add(r);
            ITextComponent mine = band >= 2 ? garbled(tag, name, m.text, muffled) : full;
            if (band == 1) { mine = mine.createCopy(); mine.getStyle().setColor(TextFormatting.GRAY); }
            m.quiet = !self && band < 2 && mentions(m.text, r, m.ping) && MinecraftForge.EVENT_BUS.post(new PrideChatEvent.Mention(m, r));
            send(r, m, mine, band, dist, self);
            m.quiet = false;
        }
        if (m.range > 0) heard += Devices.relay(m, line, to);
        srv.sendMessage(m.anonymous ? new TextComponentString("[anon: " + p.getName() + "] ").appendSibling(full) : full);   // console / server log
        Stats.message(m);
        if (m.range > 0 && heard == 0 && ChatConfig.server.tellNobodyHeard && !m.shadow)
            tell(p, "§8§o(nobody was close enough to hear you — the G button or /g talks to everyone)");
        List<UUID> ids = new ArrayList<>();
        for (EntityPlayerMP r : to) ids.add(r.getUniqueID());
        RECIPIENTS.put(m.id, ids);
        if (m.range == 0 && !m.shadow && m.only == null) {
            HISTORY.add(new Object[]{meta(m, 0, 0, false), ITextComponent.Serializer.componentToJson(full), m.channel});
            historyDirty = true;
            pruneHistory();
        }
        Bots.heard(m);
        if (p != null && !m.shadow) MinecraftForge.EVENT_BUS.post(new PrideChatEvent.Talked(m, to));
    }

    /** the message text as a component: &colours (by permission), clickable links, [item] */
    static ITextComponent body(EntityPlayerMP p, String text) {
        String t = text;
        if (ChatConfig.server.allowColors && perm(p, "pridechat.color")) t = colors(t);
        if (ChatConfig.server.rpFormat) t = t.replaceAll("(?<![*\\w])\\*([^*\\n]{1,120})\\*(?![*\\w])",
                p != null && "actions".equals(RPMODE.get(p.getUniqueID())) ? "§d§o*$1*§r" : "§o$1§r");
        ItemStack held = p.getHeldItemMainhand();
        if (ChatConfig.server.itemLinks && !held.isEmpty() && (t.contains("[item]") || t.contains("[hand]"))) {
            String[] parts = t.split("\\[(?:item|hand)\\]", -1);
            ITextComponent out = new TextComponentString("");
            for (int i = 0; i < parts.length; i++) {
                if (!parts[i].isEmpty()) out.appendSibling(ForgeHooks.newChatWithLinks(parts[i]));
                if (i < parts.length - 1) out.appendSibling(held.getTextComponent());
            }
            return out;
        }
        return ForgeHooks.newChatWithLinks(t);
    }

    /** each player's own bubble style (it travels with their lines, roadmap #4) */
    static final Map<UUID, String> STYLE = new HashMap<>();
    /** each player's own look (bubble colours, head size, name colours…, roadmap #7), already checked */
    static final Map<UUID, NBTTagCompound> APPEAR = new HashMap<>();

    private static boolean hex(String s) { return s.matches("[0-9A-Fa-f]{6}"); }

    /** what a player may show of the look they asked for (permissions + the server's limits) */
    static NBTTagCompound appearance(EntityPlayerMP p, NBTTagCompound in) {
        NBTTagCompound o = new NBTTagCompound();
        if (!ChatConfig.server.appearance) return o;
        if (perm(p, "pridechat.appearance")) {
            for (String k : new String[]{"bc", "be", "hb"}) if (hex(in.getString(k))) o.setString(k, in.getString(k));
            if (in.hasKey("ba")) o.setByte("ba", (byte) MathHelper.clamp(in.getByte("ba"), ChatConfig.server.minBubbleAlpha, 100));
            if (in.hasKey("bt")) o.setByte("bt", (byte) MathHelper.clamp(in.getByte("bt"), 0, 4));
            if (in.getInteger("bl") > 0) o.setInteger("bl", MathHelper.clamp(in.getInteger("bl"), 2, ChatConfig.server.maxBubbleSeconds));
            if (in.getString("an").matches("[a-z]{2,12}")) o.setString("an", in.getString("an"));
            if (in.hasKey("hs")) o.setShort("hs", (short) MathHelper.clamp(in.getShort("hs"), 50, ChatConfig.server.maxHeadSize));
        }
        if (perm(p, "pridechat.appearance.colors")) for (String k : new String[]{"nc", "mc", "cc"}) if (hex(in.getString(k))) o.setString(k, in.getString(k));
        return o;
    }

    /** the chat colour (only 16 exist) closest to a hex colour */
    static TextFormatting nearest(String hex) {
        int c = Integer.parseInt(hex, 16);
        int[] pal = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
        int best = 15;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < 16; i++) {
            long dr = ((c >> 16) & 255) - ((pal[i] >> 16) & 255), dg = ((c >> 8) & 255) - ((pal[i] >> 8) & 255), db = (c & 255) - (pal[i] & 255);
            long d = dr * dr * 3 + dg * dg * 4 + db * db * 2;
            if (d < bd) { bd = d; best = i; }
        }
        return TextFormatting.fromColorIndex(best);
    }

    /** a look field of the sender ("" = none) */
    static String look(EntityPlayerMP p, String k) {
        NBTTagCompound a = p == null ? null : APPEAR.get(p.getUniqueID());
        return a == null ? "" : a.getString(k);
    }

    /** roleplay styles (roadmap #24): per player (their setting or /rpmode), channels can force one */
    static final String[] RP_MODES = {"chat", "actions", "novel", "script"};
    static final Map<UUID, String> RPMODE = new HashMap<>();

    static String rpModeOf(Msg m) {
        if (CHANNEL.equals(m.mode)) for (String s : ChatConfig.server.rpModeChannels) {
            String[] q = s.split("\\s*\\|\\s*");
            if (q.length >= 2 && q[0].trim().equalsIgnoreCase(m.channel) && Arrays.asList(RP_MODES).contains(q[1].trim())) return q[1].trim();
        }
        String r = m.sender == null ? null : RPMODE.get(m.sender.getUniqueID());
        return r == null ? "chat" : r;
    }

    /** the "Name: text" line in the talker's roleplay style; null = the normal chat line */
    static ITextComponent rpLine(Msg m, ITextComponent name) {
        String mode = rpModeOf(m), t = m.text;
        EntityPlayerMP p = m.sender;
        switch (mode) {
            case "script": {
                ITextComponent out = new TextComponentString("[").appendSibling(name).appendText("]: ");
                return out.appendSibling(body(p, t));
            }
            case "actions": {
                if (t.matches("\\*[^*\\n]+\\*")) {                       // the whole message is an action: an emote line
                    ITextComponent b = body(p, t.substring(1, t.length() - 1));
                    b.getStyle().setColor(TextFormatting.LIGHT_PURPLE).setItalic(true);
                    return new TextComponentString("* ").appendSibling(name).appendText(" ").appendSibling(b);
                }
                return null;                                                // inline *actions* are coloured by body()
            }
            case "novel": {
                if (!t.contains("\"")) {                                   // no quotes: all of it is speech
                    ITextComponent b = body(p, "\"" + t + "\"");
                    return new TextComponentString("").appendSibling(name).appendText(" says, ").appendSibling(b);
                }
                ITextComponent out = new TextComponentString("").appendSibling(name).appendText(" ");
                boolean speech = false;
                for (String part : t.split("\"", -1)) {
                    if (!part.isEmpty()) {
                        ITextComponent c = body(p, speech ? "\"" + part + "\"" : part);
                        if (!speech) c.getStyle().setColor(TextFormatting.GRAY).setItalic(true);
                        out.appendSibling(c);
                    }
                    speech = !speech;
                }
                return out;
            }
            default: return null;
        }
    }

    /** /chatnick disguises (roadmap #27) */
    static final Map<UUID, String> NICKS = new HashMap<>();

    static ITextComponent nameOf(Msg m) {
        if (m.anonymous) { ITextComponent a = new TextComponentString("Anonymous"); a.getStyle().setColor(TextFormatting.DARK_GRAY).setItalic(true); return a; }
        String nick = NICKS.get(m.sender.getUniqueID());
        if (nick != null) { ITextComponent a = new TextComponentString(colors(nick)); a.getStyle().setInsertion(TextFormatting.getTextWithoutFormattingCodes(colors(nick))); return a; }
        ITextComponent n = m.sender.getDisplayName();
        if (!look(m.sender, "nc").isEmpty()) n.getStyle().setColor(nearest(look(m.sender, "nc")));
        return n;
    }

    /** does this player carry the item (registry name like minecraft:compass) anywhere in their inventory? */
    static boolean carries(EntityPlayerMP p, String key) {
        for (ItemStack s : p.inventory.mainInventory) if (!s.isEmpty() && key.equals(String.valueOf(s.getItem().getRegistryName()))) return true;
        for (ItemStack s : p.inventory.offHandInventory) if (!s.isEmpty() && key.equals(String.valueOf(s.getItem().getRegistryName()))) return true;
        return false;
    }

    /** an encrypted channel line for someone without the key (#34) */
    static ITextComponent scrambled(String tag, ITextComponent full) {
        String plain = full.getUnformattedText();
        StringBuilder b = new StringBuilder();
        Random r = new Random(plain.hashCode());
        String glyphs = "░▒▓";
        for (char c : plain.toCharArray()) b.append(c == ' ' ? ' ' : glyphs.charAt(r.nextInt(3)));
        ITextComponent t = new TextComponentString(b.toString());
        t.getStyle().setColor(TextFormatting.DARK_GRAY);
        return tag.isEmpty() ? t : new TextComponentString(tag).appendSibling(t);
    }

    static String colors(String s) { return s.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1"); }

    static String tagFor(Msg m) {
        switch (m.mode) {
            case WHISPER: return m.target.isEmpty() ? colors(ChatConfig.server.whisperTag) : colors(ChatConfig.server.whisperTag).replace("]", "→" + m.target + "]");
            case LOCAL: return colors(ChatConfig.server.localTag);
            case SHOUT: return colors(ChatConfig.server.shoutTag);
            case CHANNEL: {
                Channels.Ch ch = Channels.get(m.channel);
                return colors(String.format(ch != null && !ch.tag.isEmpty() ? ch.tag : ChatConfig.server.channelTag, m.channel));
            }
            case ZONE: return colors(String.format(ChatConfig.server.zoneTag, m.channel));
            case DIM: return colors(String.format(ChatConfig.server.dimTag, m.sender == null ? "" : m.sender.world.provider.getDimensionType().getName()));
            default: return "";
        }
    }

    /** too far to hear it all: "...llo th..." (roadmap #2/#3) */
    static ITextComponent garbled(String tag, ITextComponent name, String text, boolean muffled) {
        Random r = new Random(text.hashCode());
        StringBuilder b = new StringBuilder();
        boolean dots = false;
        for (char c : text.toCharArray()) {
            boolean keep = c == ' ' ? true : r.nextFloat() < (muffled ? 0.55f : 0.4f);
            if (keep) { b.append(c); dots = false; }
            else if (!dots) { b.append(muffled ? "~" : ".."); dots = true; }
        }
        ITextComponent t = new TextComponentTranslation("chat.type.text", name, new TextComponentString(b.toString()));
        t.getStyle().setColor(TextFormatting.DARK_GRAY).setItalic(true);
        return tag.isEmpty() ? t : new TextComponentString(tag).appendSibling(t);
    }

    /**
     * "Chat physics" (roadmap #3): how far a voice of `range` blocks carries from p to r. Walls take wallDamping % each,
     * floors/ceilings floorDamping % each, a clear line through a tunnel or under a roof (both under cover) carries
     * undergroundBonus % further, water halves it, and each dimension has its own multiplier. 0 = a different world.
     * The same numbers drive chat, typing dots and the voice API ({@link com.dogpound.pridechat.api.PrideChatVoice}).
     */
    public static double reach(EntityPlayerMP p, EntityPlayerMP r, int range) {
        if (r.dimension != p.dimension) return 0;
        double eff = range * dimFactor(p.dimension);
        if (r == p) return eff;
        if (ChatConfig.server.occlusion) {
            double[] wf = walls(p, p.getPositionEyes(1), r.getPositionEyes(1));
            eff *= Math.pow(1 - ChatConfig.server.wallDamping / 100.0, wf[0]) * Math.pow(1 - ChatConfig.server.floorDamping / 100.0, wf[1]);
            if (wf[0] + wf[1] == 0 && ChatConfig.server.undergroundBonus > 0 && covered(p) && covered(r)) eff *= 1 + ChatConfig.server.undergroundBonus / 100.0;
        }
        if (p.isInsideOfMaterial(Material.WATER)) eff *= 0.5;
        return eff;
    }

    /** are these two players (Overworld ⇄ Nether) each standing at the two ends of one portal link? (#38, voice API) */
    public static boolean portalLinked(EntityPlayerMP p, EntityPlayerMP r) {
        if (!ChatConfig.server.portalChat || r.dimension + p.dimension != -1 || r.dimension * p.dimension != 0) return false;
        BlockPos a = portalNear(p);
        if (a == NOWHERE) return false;
        BlockPos b = portalNear(r);
        return b != NOWHERE && linked(a, p.dimension, b);
    }

    /** who radios / intercoms / phones carry this player's voice to (voice API, #49) */
    public static Map<EntityPlayerMP, String> devicesFor(EntityPlayerMP p, Collection<EntityPlayerMP> already) { return Devices.reached(p, already); }

    /** "no portal here" (so we look only once per message) */
    static final BlockPos NOWHERE = new BlockPos(0, -999, 0);

    /** the nearest Nether portal block within portalHearRange of the player, or NOWHERE */
    static BlockPos portalNear(EntityPlayerMP p) {
        int r = ChatConfig.server.portalHearRange;
        BlockPos c = new BlockPos(p), best = NOWHERE;
        double bd = Double.MAX_VALUE;
        BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) for (int dz = -r; dz <= r; dz++) {
            at.setPos(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
            if (at.getY() < 0 || at.getY() > 255 || !p.world.isBlockLoaded(at)) continue;
            if (p.world.getBlockState(at).getBlock() != net.minecraft.init.Blocks.PORTAL) continue;
            double d = dx * dx + dy * dy + dz * dz;
            if (d < bd) { bd = d; best = at.toImmutable(); }
        }
        return best;
    }

    /** are these two portals (one in the Nether, one in the Overworld) each other's other end? (compared in Nether blocks) */
    static boolean linked(BlockPos a, int dimA, BlockPos b) {
        double ax = dimA == -1 ? a.getX() : a.getX() / 8.0, az = dimA == -1 ? a.getZ() : a.getZ() / 8.0;
        double bx = dimA == -1 ? b.getX() / 8.0 : b.getX(), bz = dimA == -1 ? b.getZ() / 8.0 : b.getZ();
        double lr = ChatConfig.server.portalLinkRange;
        return (ax - bx) * (ax - bx) + (az - bz) * (az - bz) <= lr * lr;
    }

    /** under a roof / in a tunnel (no sky above the head) */
    static boolean covered(EntityPlayerMP p) { return !p.world.canSeeSky(new BlockPos(p.posX, p.posY + p.getEyeHeight(), p.posZ)); }

    /** the range multiplier for a dimension (config dimensionRules "id | multiplier") */
    static double dimFactor(int dim) {
        for (String s : ChatConfig.server.dimensionRules) {
            String[] q = s.split("\\s*\\|\\s*");
            if (q.length < 2) continue;
            try { if (Integer.parseInt(q[0].trim()) == dim) return Math.max(0.05, Math.min(20, Double.parseDouble(q[1].trim()))); } catch (NumberFormatException ignored) {}
        }
        return 1;
    }

    /** {walls, floors} between two eyes; glass, leaves, doors count half; a block crossed going straight up/down is a floor */
    static double[] walls(EntityPlayerMP p, Vec3d a, Vec3d b) {
        double d = a.distanceTo(b);
        double[] out = {0, 0};
        if (d < 1.5) return out;
        int steps = (int) Math.min(256, d * 2);
        BlockPos last = new BlockPos(a);
        for (int i = 1; i < steps; i++) {
            Vec3d at = a.add(b.subtract(a).scale(i / (double) steps));
            BlockPos bp = new BlockPos(at);
            if (bp.equals(last)) continue;
            boolean vertical = bp.getY() != last.getY() && bp.getX() == last.getX() && bp.getZ() == last.getZ();
            last = bp;
            if (!p.world.isBlockLoaded(bp)) continue;
            IBlockState s = p.world.getBlockState(bp);
            if (s.getMaterial() == Material.AIR) continue;
            double w = s.isOpaqueCube() ? 1 : s.getMaterial().blocksMovement() ? 0.5 : 0;
            out[vertical ? 1 : 0] += w;
            if (out[0] + out[1] >= 8) break;
        }
        return out;
    }

    // ------------------------------------------------------------------ private messages (with ✓ delivered, ✓✓ read)
    static void dm(Msg m) {
        EntityPlayerMP p = m.sender;
        EntityPlayerMP to = server().getPlayerList().getPlayerByUsername(m.target);
        if (to == null) {
            if (ChatConfig.server.mail && Mail.send(p.getName(), m.target, m.text))
                tell(p, "§d✉ " + m.target + " is offline — saved as mail. They'll get it when they join.");
            else tell(p, "§c" + m.target + " isn't online.");
            return;
        }
        ITextComponent in = new TextComponentString("§d✉ " + p.getName() + " → you: §r").appendSibling(body(p, m.text));
        ITextComponent out = new TextComponentString("§d✉ you → " + to.getName() + ": §r").appendSibling(body(p, m.text));
        m.channel = "";
        if (!m.shadow) send(to, m, in, 0, 0, false);
        NBTTagCompound o = meta(m, 0, 0, true);
        o.setString("dm", to.getName());
        o.setBoolean("out", true);
        o.setBoolean("delivered", !m.shadow);
        if (hasPrideChat(p)) Net.send(p, Net.S_LINE, o, ITextComponent.Serializer.componentToJson(out));
        else p.sendMessage(out);
        server().sendMessage(new TextComponentString("[DM] " + p.getName() + " -> " + to.getName() + ": " + m.text));
        RECIPIENTS.put(m.id, Arrays.asList(p.getUniqueID(), to.getUniqueID()));
    }

    // ------------------------------------------------------------------ sending one line to one player
    static NBTTagCompound meta(Msg m, int band, float dist, boolean self) {
        NBTTagCompound t = new NBTTagCompound();
        t.setLong("id", m.id);
        t.setLong("t", System.currentTimeMillis());
        EntityPlayerMP p = m.sender;
        if (p != null && !m.anonymous) {
            String nick = NICKS.get(p.getUniqueID());
            t.setString("s", nick != null ? TextFormatting.getTextWithoutFormattingCodes(colors(nick)) : p.getName());
            t.setString("u", p.getUniqueID().toString());
            t.setInteger("dim", p.dimension);
            String bs = STYLE.get(p.getUniqueID());
            if (bs != null) t.setString("bs", bs);
            NBTTagCompound ap = APPEAR.get(p.getUniqueID());
            if (ap != null && !ap.hasNoTags()) t.setTag("ap", ap.copy());
            t.setFloat("x", (float) p.posX); t.setFloat("y", (float) p.posY); t.setFloat("z", (float) p.posZ);
        }
        t.setString("m", m.mode);
        t.setString("c", m.channel);
        if (DM.equals(m.mode) && p != null) t.setString("dm", p.getName());
        t.setInteger("r", m.range);
        t.setFloat("d", dist);
        t.setByte("b", (byte) band);
        t.setByte("v", (byte) (m.volume >= 0 ? m.volume : WHISPER.equals(m.mode) ? 0 : SHOUT.equals(m.mode) ? 2 : 1));
        t.setByte("p", (byte) (ANNOUNCE.equals(m.mode) ? 2 : m.ping ? 1 : 0));
        if (m.re != 0) { t.setLong("re", m.re); t.setString("rs", m.reName); t.setString("rt", m.reText); }
        if (!m.fx.isEmpty()) t.setString("fx", m.fx);
        t.setBoolean("ping", m.ping && !m.quiet);
        if (m.quiet) t.setBoolean("nm", true);
        t.setBoolean("self", self);
        return t;
    }

    static void send(EntityPlayerMP r, Msg m, ITextComponent c, int band, float dist, boolean self) {
        if (hasPrideChat(r)) Net.send(r, Net.S_LINE, meta(m, band, dist, self), ITextComponent.Serializer.componentToJson(c));
        else vanillaSend(r, c);
    }

    /** a line that came another way (radio, intercom, phone…): its own mode + label for the client's tabs */
    static void sendAs(EntityPlayerMP r, Msg m, ITextComponent c, String mode, String label, float dist) {
        if (!hasPrideChat(r)) { vanillaSend(r, c); return; }
        NBTTagCompound t = meta(m, 0, dist, false);
        t.setString("m", mode);
        t.setString("f", label);
        Net.send(r, Net.S_LINE, t, ITextComponent.Serializer.componentToJson(c));
    }

    /** a plain chat line for a player without PrideChat — split so no packet goes over the 32 K limit */
    static void vanillaSend(EntityPlayerMP r, ITextComponent c) {
        String json = ITextComponent.Serializer.componentToJson(c);
        if (json.length() < 30000) { r.sendMessage(c); return; }
        String flat = c.getFormattedText();
        for (int i = 0; i < flat.length(); i += 8000) r.sendMessage(new TextComponentString(flat.substring(i, Math.min(flat.length(), i + 8000))));
    }

    /** a grey line from PrideChat to one player */
    public static void tell(EntityPlayerMP p, String s) { p.sendMessage(new TextComponentString(s)); }

    /** a line from the "Pride" helper bot (AI answers, catch-up, bots) to some players */
    public static void bot(Collection<EntityPlayerMP> to, String name, String text) {
        Msg m = new Msg();
        m.mode = BOT; m.text = text;
        ITextComponent c = new TextComponentString("§d✦ " + name + " §8» §f").appendSibling(ForgeHooks.newChatWithLinks(colors(text)));
        for (EntityPlayerMP r : to) {
            NBTTagCompound t = meta(m, 0, 0, false);
            t.setString("s", name);
            t.setBoolean("bot", true);
            if (hasPrideChat(r)) Net.send(r, Net.S_LINE, t, ITextComponent.Serializer.componentToJson(c));
            else vanillaSend(r, c);
        }
    }

    // ------------------------------------------------------------------ typing dots, reactions
    private static void typing(EntityPlayerMP p, NBTTagCompound t) {
        String mode = t.getString("mode");
        if (MinecraftForge.EVENT_BUS.post(new PrideChatEvent.Typing(p, t.getBoolean("on"), mode, t.getString("target")))) return;
        int range = rangeFor(mode.isEmpty() ? LOCAL : mode, t.getInteger("range"));
        NBTTagCompound o = new NBTTagCompound();
        o.setString("s", p.getName());
        o.setString("u", p.getUniqueID().toString());
        o.setBoolean("on", t.getBoolean("on"));
        o.setString("m", mode);
        o.setString("c", t.getString("target"));
        if (ChatConfig.server.activityTypingSpeed) o.setByte("r", (byte) MathHelper.clamp(t.getByte("r"), 0, 30));   // how fast (0.4.1)
        if (DM.equals(mode)) {
            EntityPlayerMP to = server().getPlayerList().getPlayerByUsername(t.getString("target"));
            if (to != null && hasPrideChat(to)) Net.send(to, Net.S_TYPING, o, "");
            return;
        }
        for (EntityPlayerMP r : server().getPlayerList().getPlayers()) {
            if (r == p || !hasPrideChat(r)) continue;
            if (range > 0 && (r.dimension != p.dimension || Math.sqrt(r.getDistanceSq(p)) > reach(p, r, range))) continue;
            if (CHANNEL.equals(mode)) { Channels.Ch ch = Channels.get(t.getString("target")); if (ch == null || !ch.isMember(r)) continue; }
            Net.send(r, Net.S_TYPING, o, "");
        }
    }

    // ------------------------------------------------------------------ what they're up to (0.4.1): in a menu / asleep
    /** player → {menu kind 1-6 or 0, asleep 0/1}; only players with something going on */
    private static final Map<UUID, int[]> STATUS = new HashMap<>();

    private static NBTTagCompound statusTag(EntityPlayerMP p, int g, int i, boolean quiet) {
        NBTTagCompound o = new NBTTagCompound();
        o.setString("s", p.getName());
        o.setByte("g", (byte) g);
        o.setBoolean("i", i != 0);
        if (quiet) o.setBoolean("q", true);
        return o;
    }

    private static void status(EntityPlayerMP p, NBTTagCompound t) {
        int g = ChatConfig.server.activityGui ? MathHelper.clamp(t.getByte("g"), 0, 6) : 0;
        int i = ChatConfig.server.activityIdle && t.getBoolean("i") ? 1 : 0;
        int[] old = STATUS.get(p.getUniqueID());
        int og = old == null ? 0 : old[0], oi = old == null ? 0 : old[1];
        if (g == og && i == oi) return;
        if (g == 0 && i == 0) STATUS.remove(p.getUniqueID()); else STATUS.put(p.getUniqueID(), new int[]{g, i});
        NBTTagCompound o = statusTag(p, g, i, false);
        if (i != oi) {                                                     // asleep / awake: everyone (tab list Zz)
            for (EntityPlayerMP r : server().getPlayerList().getPlayers()) if (r != p && hasPrideChat(r)) Net.send(r, Net.S_STATUS, o, "");
            return;
        }
        for (net.minecraft.entity.player.EntityPlayer r : p.getServerWorld().getEntityTracker().getTrackingPlayers(p))   // menus: only who can see them
            if (r instanceof EntityPlayerMP && hasPrideChat((EntityPlayerMP) r)) Net.send((EntityPlayerMP) r, Net.S_STATUS, o, "");
    }

    /** viewer just started seeing target */
    static void tracking(EntityPlayerMP viewer, EntityPlayerMP target) {
        int[] st = STATUS.get(target.getUniqueID());
        if (st != null && st[0] != 0 && hasPrideChat(viewer)) Net.send(viewer, Net.S_STATUS, statusTag(target, st[0], st[1], true), "");
    }

    private static void react(EntityPlayerMP p, long id, String emoji) {
        List<UUID> to = RECIPIENTS.get(id);
        if (to == null || !to.contains(p.getUniqueID()) || emoji.length() > 40) return;
        NBTTagCompound o = new NBTTagCompound();
        o.setLong("id", id);
        o.setString("s", p.getName());
        for (UUID u : to) {
            EntityPlayerMP r = server().getPlayerList().getPlayerByUUID(u);
            if (r != null && hasPrideChat(r)) Net.send(r, Net.S_REACT, o, emoji);
        }
    }

    // ------------------------------------------------------------------ joining
    static void joined(EntityPlayerMP p) {
        Channels.joined(p);
        if (hasPrideChat(p)) { HELLO.add(p.getUniqueID()); hello(p); }
        for (String rule : ChatConfig.server.rules) tell(p, colors(rule));
        int mail = Mail.count(p.getName());
        if (mail > 0) tell(p, "§d✉ You have " + mail + " mail — §f/mail read");
        AI.catchUp(p);
    }

    /** a PrideChat client: what this server can do, its channels, and the recent public lines */
    static void hello(EntityPlayerMP p) {
        {
            NBTTagCompound t = new NBTTagCompound();
            t.setInteger("maxLength", ChatConfig.server.maxLength);
            t.setInteger("maxRange", ChatConfig.server.maxRange);
            t.setBoolean("global", ChatConfig.server.allowGlobal);
            t.setBoolean("colors", ChatConfig.server.allowColors);
            t.setBoolean("ai", AI.available());
            t.setBoolean("aGui", ChatConfig.server.activityGui);
            t.setBoolean("aIdle", ChatConfig.server.activityIdle);
            t.setBoolean("aRate", ChatConfig.server.activityTypingSpeed);
            t.setInteger("idleMin", ChatConfig.server.idleMinutes);
            Net.send(p, Net.S_HELLO, t, "");
            for (Map.Entry<UUID, int[]> e : STATUS.entrySet()) {          // who's asleep already (tab list); menus come with tracking
                EntityPlayerMP o = server().getPlayerList().getPlayerByUUID(e.getKey());
                if (o != null && o != p && e.getValue()[1] != 0) Net.send(p, Net.S_STATUS, statusTag(o, 0, 1, true), "");
            }
            Channels.sendList(p);
            int skip = Math.max(0, HISTORY.size() - ChatConfig.server.historyKeep), i = 0;
            for (Object[] h : HISTORY) {
                if (i++ < skip) continue;
                String ch = (String) h[2];
                if (!ch.isEmpty()) { Channels.Ch c = Channels.get(ch); if (c == null || !c.isMember(p)) continue; }
                NBTTagCompound meta = ((NBTTagCompound) h[0]).copy();
                meta.setBoolean("h", true);
                Net.send(p, Net.S_LINE, meta, (String) h[1]);
            }
        }
    }

    // ------------------------------------------------------------------ history on disk (roadmap #14)
    static boolean historyDirty;

    static long retentionMs() {
        switch (ChatConfig.server.historyRetention) {
            case "1h": return 3600_000L;
            case "1d": return 86400_000L;
            case "7d": return 7 * 86400_000L;
            case "30d": return 30 * 86400_000L;
            default: return 0;                                         // forever / off
        }
    }

    static void pruneHistory() {
        long keep = retentionMs(), now = System.currentTimeMillis();
        while (!HISTORY.isEmpty() && (HISTORY.size() > ChatConfig.server.historyStore
                || keep > 0 && now - ((NBTTagCompound) HISTORY.getFirst()[0]).getLong("t") > keep)) HISTORY.removeFirst();
    }

    private static java.io.File historyFile() {
        java.io.File dir = net.minecraftforge.common.DimensionManager.getCurrentSaveRootDirectory();
        return dir == null ? null : new java.io.File(dir, "pridechat-history.dat");
    }

    static void loadHistory() {
        HISTORY.clear();
        java.io.File f = historyFile();
        if (f == null || !f.exists() || "off".equals(ChatConfig.server.historyRetention)) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            net.minecraft.nbt.NBTTagList l = net.minecraft.nbt.CompressedStreamTools.readCompressed(in).getTagList("h", 10);
            for (int i = 0; i < l.tagCount(); i++) {
                NBTTagCompound h = l.getCompoundTagAt(i);
                HISTORY.add(new Object[]{h.getCompoundTag("m"), h.getString("j"), h.getString("c")});
            }
            pruneHistory();
            PrideChat.LOG.info("[PrideChat] " + HISTORY.size() + " chat lines from before");
        } catch (Exception e) { PrideChat.LOG.warn("couldn't read chat history: " + e); }
    }

    static void saveHistory() {
        if (!historyDirty) return;
        historyDirty = false;
        java.io.File f = historyFile();
        if (f == null) return;
        if ("off".equals(ChatConfig.server.historyRetention)) { f.delete(); return; }
        pruneHistory();
        net.minecraft.nbt.NBTTagList l = new net.minecraft.nbt.NBTTagList();
        for (Object[] h : HISTORY) {
            NBTTagCompound t = new NBTTagCompound();
            t.setTag("m", (NBTTagCompound) h[0]); t.setString("j", (String) h[1]); t.setString("c", (String) h[2]);
            l.appendTag(t);
        }
        NBTTagCompound root = new NBTTagCompound();
        root.setTag("h", l);
        java.io.File tmp = new java.io.File(f.getPath() + ".tmp");
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(tmp)) { net.minecraft.nbt.CompressedStreamTools.writeCompressed(root, out); }
        catch (Exception e) { PrideChat.LOG.warn("couldn't save chat history: " + e); return; }
        if (!f.delete() && f.exists() || !tmp.renameTo(f)) PrideChat.LOG.warn("couldn't replace " + f);
    }

    static void left(EntityPlayerMP p) {
        PARTIAL.remove(p.getUniqueID());
        RATE.remove(p.getUniqueID());
        HELLO.remove(p.getUniqueID());
        APPEAR.remove(p.getUniqueID());
        RPMODE.remove(p.getUniqueID());
        if (STATUS.remove(p.getUniqueID()) != null) {
            NBTTagCompound o = statusTag(p, 0, 0, true);
            for (EntityPlayerMP r : server().getPlayerList().getPlayers()) if (r != p && hasPrideChat(r)) Net.send(r, Net.S_STATUS, o, "");
        }
        AI.left(p);
    }

    static boolean perm(EntityPlayerMP p, String node) {
        try { return PermissionAPI.hasPermission(p, node); } catch (Throwable t) { return true; }
    }

    private Router() {}
}
