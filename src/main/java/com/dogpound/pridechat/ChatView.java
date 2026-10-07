package com.dogpound.pridechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.event.ClickEvent;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PrideChat's chat box. It stands in for Minecraft's (so it sees EVERY message, even ones mods print directly),
 * keeps each line with its time, sender, tabs and — from a PrideChat server — its "awareness" metadata (mode,
 * channel, distance band, DM partner, reactions, read ticks), and draws the active tab: faces, emoji, mentions,
 * timestamps, faded far-away lines. Clicking links / hovering names still work (getChatComponent maps the mouse back).
 */
public class ChatView extends GuiNewChat {
    // ------------------------------------------------------------------ the store
    public static final class Line {
        final ITextComponent comp;
        String formatted, plain;
        final String sender;
        long time;
        final int id;
        final boolean mention, mine;
        long tabBits;
        List<Row> rows;
        int rowsWidth = -1;
        String rowsStamp = "";
        int count = 1;                       // ×N when the same line repeats
        // from the server (PrideChat) or worked out here
        long msgId;
        String mode = "", channel = "", dm;  // dm = the other person in a private message
        String freq = "";                    // radio / intercom / phone label
        String style = "";                   // the sender's bubble style
        NBTTagCompound ap = new NBTTagCompound();   // the sender's own look (#7)
        String fx = "";                      // text effects from the sender (#47) and where the message body starts
        int bodyStart;
        int dim = Integer.MIN_VALUE;         // where it was said (the sender, else where I was), for search (#13)
        double x, z;
        long re, thread;                     // a reply to message `re`; the conversation thread it belongs to (#32)
        String reName = "", reText = "";
        int replies;
        int ver;                             // bumps when the line must be laid out again
        List<Row> rowsAlt;                   // layout for another width (extra windows)
        int rowsAltW = -1, rowsAltVer = -1;
        String rowsAltStamp = "";
        void dirty() { rows = null; ver++; }
        boolean out, delivered, read, history, bot, npc;
        int band, volume = 1;
        final Map<String, Integer> reactions = new LinkedHashMap<>();
        final Set<String> reactedBy = new HashSet<>();
        Line(ITextComponent comp, int id, String sender, boolean mention, boolean mine) {
            this.comp = comp; this.id = id; this.sender = sender; this.mention = mention; this.mine = mine;
            formatted = comp.getFormattedText(); plain = comp.getUnformattedText(); time = System.currentTimeMillis();
        }
    }

    /** one drawn row of a line: pieces to draw, each remembering where its characters sit in the message */
    static final class Row {
        final List<Object> parts = new ArrayList<>();     // String (with § codes), Emoji.E, Head, or React
        final List<int[]> spans = new ArrayList<>();      // per part: {plainStart, plainEnd} into Line.plain (-1 = not text)
        int width;
    }

    static final class Head { final String name; Head(String n) { name = n; } }
    static final class React { final String code; final int n; React(String c, int n) { code = c; this.n = n; } }

    static final class Tab {
        final String name, rule, sound, flags;
        final int color;
        final Pattern re;
        final boolean session;               // a DM / group tab made on the fly (not in the config)
        int unread;
        Tab(String name, int color, String rule, String sound, String flags, boolean session) {
            this.name = name; this.color = color; this.rule = rule; this.sound = sound; this.flags = flags; this.session = session;
            Pattern p = null;
            if (!rule.startsWith("@") && !rule.startsWith("#")) try { p = Pattern.compile(rule); } catch (Exception ignored) {}
            this.re = p;
        }
        boolean muted() { return flags.contains("m"); }
        boolean matches(Line l) {
            if (rule.startsWith("@dm:")) return l.dm != null && l.dm.equalsIgnoreCase(rule.substring(4));
            if (rule.startsWith("#")) return l.channel.equalsIgnoreCase(rule.substring(1));
            if (rule.startsWith("@radio:")) return Devices.RADIO.equals(l.mode) && l.freq.equalsIgnoreCase(rule.substring(7));
            switch (rule) {
                case "@all": return true;
                case "@player": return l.sender != null && !l.bot;
                case "@system": return l.sender == null && l.dm == null;
                case "@mention": return l.mention;
                case "@dm": return l.dm != null;
                case "@radio": return Devices.RADIO.equals(l.mode);
                case "@local": return Router.LOCAL.equals(l.mode) || Router.WHISPER.equals(l.mode) || Router.SHOUT.equals(l.mode) || Router.EMOTE.equals(l.mode);
                case "@global": return (Router.GLOBAL.equals(l.mode) || l.mode.isEmpty()) && l.sender != null && l.dm == null || Router.ANNOUNCE.equals(l.mode) || l.bot;
                default:
                    if (rule.startsWith("@")) return rule.substring(1).equalsIgnoreCase(l.mode);   // @zone @dim @whisper @shout @emote…
                    return re != null && re.matcher(l.plain).find();
            }
        }
    }

    static final List<Line> LINES = new ArrayList<>();
    static final List<Tab> TABS = new ArrayList<>();
    static final List<String> DM_TABS = new ArrayList<>();
    static boolean radioSeen;                            // a radio line arrived → a Radio tab appears (if none matches @radio)
    static int active;
    static int scroll;                                   // rows scrolled up from the newest
    static String search = "";
    static long thread;                                  // ≠ 0: showing one conversation thread instead of the tab (#32)
    /** metadata for the line being printed right now (set by ClientSide around printChatMessage) */
    static NBTTagCompound meta;
    private static final Pattern ANGLE = Pattern.compile("^(?:\\[[^\\]]{1,24}\\] ?)*<([A-Za-z0-9_]{2,16})> ");
    private static final Pattern NAME_COLON = Pattern.compile("^(?:\\[[^\\]]{1,24}\\] ?)*(?:[^A-Za-z0-9_ ]+ ?)?([A-Za-z0-9_]{3,16}) ?[:»>] ");
    private static final Pattern DM_IN = Pattern.compile("^\\[?([A-Za-z0-9_]{2,16}) ?(?:->|→|»|>) ?(?:me|you)\\]? ?:? ?|^([A-Za-z0-9_]{2,16}) whispers(?: to you)?: ");
    private static final Pattern DM_OUT = Pattern.compile("^\\[?(?:me|you) ?(?:->|→|»|>) ?([A-Za-z0-9_]{2,16})\\]? ?:? ?|^You whisper to ([A-Za-z0-9_]{2,16}): ");
    private static final Map<String, LinkedList<Long>> FLOOD = new HashMap<>();

    private final Minecraft mc;

    public ChatView(Minecraft mc) { super(mc); this.mc = mc; loadTabs(); }

    static void loadTabs() {
        List<Tab> t = new ArrayList<>();
        Set<String> rules = new HashSet<>();
        for (String s : ChatConfig.tabList) {
            String[] p = s.split("\\s*\\|\\s*", -1);
            if (p.length < 3) continue;
            int c;
            try { c = Integer.parseInt(p[1].replace("#", "").trim(), 16); } catch (NumberFormatException e) { c = 0xF5A9B8; }
            t.add(new Tab(p[0].trim(), c, p[2].trim(), p.length > 3 ? p[3].trim() : "none", p.length > 4 ? p[4].trim() : "", false));
            rules.add(p[2].trim().toLowerCase(Locale.ROOT));
        }
        if (t.isEmpty()) t.add(new Tab("All", 0xF5A9B8, "@all", "none", "", false));
        for (String[] ch : ClientSide.CHANNELS)                           // a tab for each group (and, if wanted, channel) we're in without one
            if (!rules.contains("#" + ch[0]) && ("true".equals(ch[2]) || ChatConfig.channelTabs)) t.add(new Tab("#" + ch[0], "true".equals(ch[2]) ? 0xFF8CD2 : 0x9AA5FF, "#" + ch[0], "pop", "", true));
        if (radioSeen && !rules.contains("@radio")) t.add(new Tab("📻 Radio", 0x5BCEFA, "@radio", "none", "", true));
        if (ChatConfig.dmTabs) for (String n : DM_TABS) t.add(new Tab("✉ " + n, 0xB070FF, "@dm:" + n, ChatConfig.dmSound, "", true));
        String was = active < TABS.size() ? TABS.get(active).rule : "";
        TABS.clear();
        TABS.addAll(t);
        active = 0;
        for (int i = 0; i < TABS.size(); i++) if (TABS.get(i).rule.equals(was)) active = i;
        for (Line l : LINES) l.tabBits = tabsOf(l);
    }

    static void relayout() { for (Line l : LINES) l.dirty(); }

    static String activeRule() { return active < TABS.size() ? TABS.get(active).rule : "@all"; }

    static void openDm(String name) {
        if (!ChatConfig.dmTabs) return;
        boolean known = false;
        for (String n : DM_TABS) known |= n.equalsIgnoreCase(name);
        if (!known) { DM_TABS.add(name); loadTabs(); }
        for (int i = 0; i < TABS.size(); i++) if (TABS.get(i).rule.equalsIgnoreCase("@dm:" + name)) select(i);
    }

    static void closeTab(int i) {
        Tab t = TABS.get(i);
        if (t.rule.startsWith("@dm:")) DM_TABS.removeIf(n -> n.equalsIgnoreCase(t.rule.substring(4)));
        loadTabs();
        active = Math.min(active, TABS.size() - 1);
    }

    static void select(int i) {
        active = i;
        Tab t = TABS.get(i);
        t.unread = 0;
        scroll = 0;
        if (t.rule.startsWith("@dm:") && ClientSide.server && ChatConfig.sendReadReceipts) {        // tell them we've read it (✓✓)
            NBTTagCompound o = new NBTTagCompound();
            o.setString("from", t.rule.substring(4));
            Net.toServer(Net.C_READ, o, "");
        }
    }

    private static long tabsOf(Line l) {
        long bits = 0;
        for (int i = 0; i < TABS.size() && i < 63; i++) if (TABS.get(i).matches(l)) bits |= 1L << i;
        return bits;
    }

    /** who sent it: the name component (vanilla gives it an insertion / a /msg suggestion), else "<Name>" or "Name: " */
    static String senderOf(ITextComponent comp) {
        for (ITextComponent c : comp) {
            String ins = c.getStyle().getInsertion();
            if (ins != null && ins.matches("[A-Za-z0-9_]{2,16}")) return ins;
            ClickEvent ce = c.getStyle().getClickEvent();
            if (ce != null && ce.getAction() == ClickEvent.Action.SUGGEST_COMMAND) {
                Matcher m = Pattern.compile("^/(?:msg|tell|w|whisper|m) ([A-Za-z0-9_]{2,16})").matcher(ce.getValue());
                if (m.find()) return m.group(1);
            }
        }
        String plain = comp.getUnformattedText();
        Matcher m = ANGLE.matcher(plain);
        if (m.find()) return m.group(1);
        m = NAME_COLON.matcher(plain);
        if (m.find() && isOnline(m.group(1))) return m.group(1);
        return null;
    }

    private static boolean isOnline(String name) {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.getConnection() != null && mc.getConnection().getPlayerInfo(name) != null;
    }

    static boolean ignored(String name) {
        if (name == null) return false;
        for (String s : ChatConfig.ignored) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** muted: their lines stay but go grey and quiet — no sounds, pop-ups, pings, bubbles or typing dots (ignore hides them) */
    static boolean muted(String name) {
        if (name == null) return false;
        for (String s : ChatConfig.muted) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    private static boolean in(String[] list, String name) {
        if (name == null) return false;
        for (String s : list) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** per-player visibility (roadmap #44): emotes / chat lines / typing dots, each on its own */
    static boolean hiddenEmotes(String name) { return in(ChatConfig.hiddenEmotes, name); }
    static boolean hiddenChat(String name) { return in(ChatConfig.hiddenChat, name); }
    static boolean hiddenTyping(String name) { return in(ChatConfig.hiddenTyping, name); }

    static boolean friend(String name) {
        if (name == null) return false;
        for (String s : ChatConfig.friends) if (s.equalsIgnoreCase(name)) return true;
        return false;
    }

    /** what the message says, without the "<Name> " / "[L] Name: " in front */
    static String body(Line l) {
        if (l.sender == null) return l.plain;
        int i = l.plain.indexOf(l.sender);
        if (i < 0) return l.plain;
        String rest = l.plain.substring(i + l.sender.length());
        Matcher m = Pattern.compile("^[^A-Za-z0-9_]{0,3}?[>:»→]+ ?").matcher(rest);
        return m.find() ? rest.substring(m.end()) : rest.trim();
    }

    /** the noisy vanilla lines some people don't want */
    private static boolean filtered(ITextComponent comp) {
        if (!(comp instanceof TextComponentTranslation)) return false;
        String k = ((TextComponentTranslation) comp).getKey();
        if (ChatConfig.hideJoinLeave && k.startsWith("multiplayer.player.")) return true;
        if (ChatConfig.hideDeaths && k.startsWith("death.")) return true;
        return ChatConfig.hideAdvancements && k.startsWith("chat.type.advancement.");
    }

    // ------------------------------------------------------------------ Minecraft's chat calls
    @Override
    public void printChatMessageWithOptionalDeletion(ITextComponent comp, int id) {
        super.printChatMessageWithOptionalDeletion(comp, id);            // keep vanilla's copy (turning PrideChat off shows it)
        NBTTagCompound t = meta;
        if (id != 0) LINES.removeIf(l -> l.id == id);
        if (filtered(comp)) return;
        String sender = t != null && t.hasKey("s") ? t.getString("s") : senderOf(comp);
        String me = mc.player == null ? "" : mc.player.getName();
        boolean mine = sender != null && sender.equalsIgnoreCase(me);
        String plain = comp.getUnformattedText();
        // private messages: from the server's metadata, vanilla /msg, or the usual "[Name -> me]" shapes
        String dm = null;
        boolean out = false;
        if (t != null && t.hasKey("dm")) { dm = t.getString("dm"); out = t.getBoolean("out"); }
        else if (comp instanceof TextComponentTranslation) {
            TextComponentTranslation tr = (TextComponentTranslation) comp;
            Object[] a = tr.getFormatArgs();
            if (a.length > 0 && ("commands.message.display.incoming".equals(tr.getKey()) || "commands.message.display.outgoing".equals(tr.getKey()))) {
                dm = a[0] instanceof ITextComponent ? ((ITextComponent) a[0]).getUnformattedText() : String.valueOf(a[0]);
                out = tr.getKey().endsWith("outgoing");
            }
        }
        if (dm == null) {
            Matcher m = DM_IN.matcher(plain);
            if (m.find()) dm = m.group(1) != null ? m.group(1) : m.group(2);
            else if ((m = DM_OUT.matcher(plain)).find()) { dm = m.group(1) != null ? m.group(1) : m.group(2); out = true; }
        }
        if (dm != null && !out) sender = dm;
        if (ignored(sender) || (dm != null && ignored(dm))) return;
        boolean history = t != null && t.getBoolean("h");

        // spam guards (other people only)
        if (!mine && sender != null && !history) {
            long now = System.currentTimeMillis();
            if (ChatConfig.floodLimit > 0) {
                LinkedList<Long> f = FLOOD.computeIfAbsent(sender, k -> new LinkedList<>());
                while (!f.isEmpty() && now - f.getFirst() > 5000) f.removeFirst();
                f.add(now);
                if (f.size() > ChatConfig.floodLimit) return;
            }
            Line last = LINES.isEmpty() ? null : LINES.get(LINES.size() - 1);
            if (ChatConfig.spamRepeat) for (int i = LINES.size() - 1; i >= 0 && i >= LINES.size() - 40; i--) {
                Line o = LINES.get(i);
                if (now - o.time > 30000) break;
                if (sender.equals(o.sender) && o.plain.equals(plain) && !(ChatConfig.collapseDuplicates && o == last)) return;
            }
        }
        if (ChatConfig.collapseDuplicates && !LINES.isEmpty()) {                 // LOL ×5
            Line last = LINES.get(LINES.size() - 1);
            if (last.plain.equals(plain) && last.id == id && (t == null || last.msgId != t.getLong("id"))) {
                last.count++; last.time = System.currentTimeMillis(); last.dirty();
                return;
            }
        }
        boolean hush = muted(sender) || dm != null && muted(dm);
        boolean mention = ChatConfig.mentions && !mine && !history && !hush && !(t != null && t.getBoolean("nm")) && (mentions(plain, me) || t != null && t.getBoolean("ping"));
        Line l = new Line(comp, id, sender, mention, mine);
        l.dm = dm; l.out = out; l.history = history;
        if (t != null) {
            l.msgId = t.getLong("id");
            l.mode = t.getString("m");
            l.channel = t.getString("c");
            l.band = t.getByte("b");
            l.volume = t.getByte("v");
            l.delivered = t.getBoolean("delivered");
            l.bot = t.getBoolean("bot");
            l.npc = t.getBoolean("npc");
            l.freq = t.getString("f");
            l.style = t.getString("bs");
            l.fx = t.getString("fx");
            if (ChatConfig.othersLook || mine) l.ap = t.getCompoundTag("ap");
            l.re = t.getLong("re");
            l.reName = t.getString("rs");
            l.reText = t.getString("rt");
        }
        if (!l.fx.isEmpty()) { String b = body(l); l.bodyStart = l.plain.endsWith(b) ? l.plain.length() - b.length() : 0; }
        if (t != null && t.hasKey("x")) { l.dim = t.getInteger("dim"); l.x = t.getFloat("x"); l.z = t.getFloat("z"); }
        else if (mc.player != null) { l.dim = mc.player.dimension; l.x = mc.player.posX; l.z = mc.player.posZ; }
        if (Devices.RADIO.equals(l.mode) && !radioSeen) { radioSeen = true; loadTabs(); }
        // word filter + calm caps change only what's drawn (same length, so clicks still line up)
        if (ChatConfig.wordFilter && !mine) {
            String c = censor(l.formatted);
            if (!c.equals(l.formatted)) {
                if ("hide".equals(ChatConfig.wordFilterMode)) return;
                l.formatted = c; l.plain = censor(l.plain);
            }
        }
        if (ChatConfig.calmCaps && !mine && sender != null) {
            String b = body(l);
            int letters = 0, caps = 0;
            for (char ch : b.toCharArray()) if (Character.isLetter(ch)) { letters++; if (Character.isUpperCase(ch)) caps++; }
            if (letters >= 8 && caps * 100 / letters >= 75) { l.formatted = l.formatted.toLowerCase(Locale.ROOT); l.plain = l.plain.toLowerCase(Locale.ROOT); }
        }
        if (hush) l.formatted = "§8" + TextFormatting.getTextWithoutFormattingCodes(l.formatted);
        if (dm != null && ChatConfig.dmTabs && !DM_TABS.stream().anyMatch(n -> n.equalsIgnoreCase(l.dm))) { DM_TABS.add(dm); loadTabs(); }
        l.tabBits = tabsOf(l);
        l.thread = l.msgId;
        if (l.re != 0) {                                                       // a reply: join the parent's thread
            Line parent = byId(l.re);
            l.thread = parent != null && parent.thread != 0 ? parent.thread : l.re;
            if (parent != null) { parent.replies++; parent.dirty(); }
        }
        if (Router.EMOTE.equals(l.mode) && hiddenEmotes(sender)) return;      // per-player visibility (#44)
        if (!mine && dm == null && hiddenChat(sender)) { if (!history && !hush) bubble(l, sender, mine); return; }
        LINES.add(l);
        while (LINES.size() > ChatConfig.historyLines) LINES.remove(0);
        if (history) return;
        ClientSide.log(plain);
        boolean open = getChatOpen();
        String sound = null;
        for (int i = 0; i < TABS.size(); i++) {
            Tab tab = TABS.get(i);
            if ((l.tabBits & (1L << i)) == 0 || tab.muted()) continue;
            if (i != active || !open) { if (i != active) tab.unread++; if (sound == null && !"none".equals(tab.sound)) sound = tab.sound; }
            if (tab.flags.contains("n") && !open && sender != null && !mine && !hush) ClientSide.toast(tab.name, body(l), tab.color);
        }
        if (scroll > 0 && (l.tabBits & (1L << active)) != 0) scroll += rows(l).size();   // reading older lines: stay put
        if (Router.ANNOUNCE.equals(l.mode)) ClientSide.toast("§d✦ Announcement", body(l), 0xF5A9B8);
        if (hush) { if (dm != null && !out) ClientSide.lastDm = dm; }
        else if (dm != null && !out) {
            ClientSide.incomingDm(dm, body(l), false);
            ClientSide.flash();
            if (open && activeRule().equalsIgnoreCase("@dm:" + dm) && ClientSide.server && ChatConfig.sendReadReceipts) {
                NBTTagCompound o = new NBTTagCompound();
                o.setString("from", dm);
                Net.toServer(Net.C_READ, o, "");
            }
        }
        else if (mention) {
            ClientSide.sound(ChatConfig.mentionSound);
            ClientSide.flash();
            if (ChatConfig.toastMentions && !open) ClientSide.toast("§d@ " + (sender == null ? "mention" : sender), body(l), 0xF5A9B8);
        } else if (!mine && sender != null) ClientSide.sound(sound != null ? sound : ChatConfig.messageSound);
        if (!hush) bubble(l, sender, mine);
        if (sender != null) ClientSide.TYPING.remove(sender);
        if (t != null && t.hasKey("x") && sender != null && !mine && dm == null && mc.player != null) {   // where they talked from (#15)
            double x = t.getFloat("x"), y = t.getFloat("y"), z = t.getFloat("z");
            int dim = t.getInteger("dim");
            String kind = l.mode.isEmpty() ? "global" : l.mode;
            double dist = dim == mc.player.dimension ? Math.sqrt(mc.player.getDistanceSq(x, y, z)) : -1;
            net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new com.dogpound.pridechat.api.TalkEvent(sender, kind, body(l), dim, x, y, z, dist));
            ClientSide.talked(sender, kind, dim, x, z, dist);
        }
    }

    /** a speech bubble for this line, when it's one (spoken nearby, not a DM / bot / radio / channel) */
    private static void bubble(Line l, String sender, boolean mine) {
        if (sender != null && l.dm == null && !l.bot && !Devices.RADIO.equals(l.mode) && (l.channel.isEmpty() || l.mode.equals(Router.LOCAL)) && (!mine || ChatConfig.bubbleOwn))
            Bubbles.add(sender, Router.EMOTE.equals(l.mode) ? "*" + body(l) + "*" : body(l), l.volume, l.band, l.mention, l.msgId, l.style, l.ap, l.fx);
    }

    static Line byId(long id) {
        for (int i = LINES.size() - 1; i >= 0; i--) if (LINES.get(i).msgId == id) return LINES.get(i);
        return null;
    }

    /** what kind of line it is, for the filter checkboxes (#42) */
    static String kindOf(Line l) {
        if (l.dm != null) return "dm";
        if (l.bot) return "bot";
        if (l.mode.isEmpty()) return l.sender == null ? "system" : "global";
        return l.mode;
    }

    static final String[][] KINDS = {{"local", "Local"}, {"whisper", "Whispers"}, {"shout", "Shouts"}, {"emote", "Emotes"}, {"global", "Everyone-chat"},
            {"channel", "Channels / groups"}, {"dm", "Private messages"}, {"radio", "Radio, intercom, phone"}, {"zone", "Zone chat"}, {"dim", "Dimension chat"},
            {"announce", "Announcements"}, {"bot", "Helper bot"}, {"system", "Server + mod messages"}};

    /** the kinds hidden in the tab with this rule */
    static Set<String> hiddenKinds(String rule) {
        for (String f : ChatConfig.tabFilters) {
            String[] p = f.split("\\s*\\|\\s*", 2);
            if (p.length == 2 && p[0].trim().equals(rule)) return new HashSet<>(Arrays.asList(p[1].trim().split("\\s*,\\s*")));
        }
        return Collections.emptySet();
    }

    static void toggleKind(String rule, String kind) {
        Set<String> h = new TreeSet<>(hiddenKinds(rule));
        if (!h.remove(kind)) h.add(kind);
        h.remove("");
        List<String> out = new ArrayList<>();
        for (String f : ChatConfig.tabFilters) if (!f.split("\\s*\\|\\s*", 2)[0].trim().equals(rule)) out.add(f);
        if (!h.isEmpty()) out.add(rule + " | " + String.join(",", h));
        ChatConfig.tabFilters = out.toArray(new String[0]);
        ClientSide.saved();
    }

    static String censor(String s) {
        String out = s;
        for (String w : ChatConfig.filterWords) {
            if (w.trim().isEmpty()) continue;
            out = Pattern.compile(Pattern.quote(w.trim()), Pattern.CASE_INSENSITIVE).matcher(out).replaceAll(new String(new char[w.trim().length()]).replace('\0', '*'));
        }
        return out;
    }

    static boolean mentions(String text, String me) {
        String t = text.toLowerCase(Locale.ROOT);
        if (!me.isEmpty()) {
            String lm = me.toLowerCase(Locale.ROOT);
            if (t.startsWith("<" + lm + ">")) return false;                           // their own "<Name>" prefix is not a mention
            if (Pattern.compile("(?<![A-Za-z0-9_])@?" + Pattern.quote(lm) + "(?![A-Za-z0-9_])").matcher(t).find()) return true;
            if (ChatConfig.smartMentions && lm.length() > 5 && Pattern.compile("(?<![A-Za-z0-9_])@?" + Pattern.quote(lm.substring(0, Math.max(4, lm.length() / 2))) + "[a-z]{0,3}(?![A-Za-z0-9_])").matcher(t).find()) return true;
        }
        if (t.contains("@here") || t.contains("@everyone")) return true;
        for (String w : ChatConfig.mentionWords) if (!w.trim().isEmpty() && t.contains(w.trim().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    static void markRead(String by) {
        for (Line l : LINES) if (l.out && by.equalsIgnoreCase(l.dm) && !l.read) { l.read = true; l.dirty(); }
    }

    static void react(long id, String code, String who) {
        for (int i = LINES.size() - 1; i >= 0; i--) {
            Line l = LINES.get(i);
            if (l.msgId != id) continue;
            if (!l.reactedBy.add(who + ":" + code)) return;
            l.reactions.merge(code, 1, Integer::sum);
            l.dirty();
            Bubbles.react(id, code);
            return;
        }
    }

    @Override
    public void deleteChatLine(int id) { super.deleteChatLine(id); LINES.removeIf(l -> l.id == id); }

    @Override
    public void clearChatMessages(boolean sent) { super.clearChatMessages(sent); LINES.clear(); scroll = 0; }

    @Override
    public void resetScroll() { super.resetScroll(); scroll = 0; }

    @Override
    public void scroll(int amount) {
        if (!ChatConfig.enabled) { super.scroll(amount); return; }
        int total = visibleRows().size();
        scroll = Math.max(0, Math.min(Math.max(0, total - 3), scroll + amount));
    }

    // ------------------------------------------------------------------ layout
    private static SimpleDateFormat fmt;
    private static String fmtKey = "";

    static String stamp(long t) {
        if (!ChatConfig.timestamps) return "";
        String col = "§" + nearestCode(ChatConfig.timeColor);
        if ("relative".equals(ChatConfig.timeFormat)) {
            long s = (System.currentTimeMillis() - t) / 1000;
            String r = s < 60 ? "now" : s < 3600 ? s / 60 + "m" : s < 86400 ? s / 3600 + "h" : s / 86400 + "d";
            return col + r + " §r";
        }
        String f = ChatConfig.compact ? "HH:mm" : ChatConfig.timeFormat;
        if (!f.equals(fmtKey)) {
            fmtKey = f;
            try { fmt = new SimpleDateFormat(f); } catch (IllegalArgumentException e) { fmt = new SimpleDateFormat("HH:mm"); }
        }
        return col + fmt.format(new Date(t)) + " §r";
    }

    /** the § colour closest to a hex colour (the font only knows 16) */
    static char nearestCode(String hex) {
        int c;
        try { c = Integer.parseInt(hex.replace("#", "").trim(), 16); } catch (NumberFormatException e) { return '8'; }
        int[] pal = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};
        int best = 8;
        long bd = Long.MAX_VALUE;
        for (int i = 0; i < 16; i++) {
            long dr = ((c >> 16) & 255) - ((pal[i] >> 16) & 255), dg = ((c >> 8) & 255) - ((pal[i] >> 8) & 255), db = (c & 255) - (pal[i] & 255);
            long d = dr * dr + dg * dg + db * db;
            if (d < bd) { bd = d; best = i; }
        }
        return "0123456789abcdef".charAt(best);
    }

    static int width() { return ChatConfig.width; }
    static int rowH() { return 9 + (ChatConfig.compact ? 0 : ChatConfig.lineSpacing); }
    static boolean heads() { return ChatConfig.chatHeads && !ChatConfig.compact; }

    static List<Row> rows(Line l) {
        int w = width() - 8;
        String ts = stamp(l.time);
        if (l.rows != null && l.rowsWidth == w && l.rowsStamp.equals(ts)) return l.rows;
        l.rows = layout(l, w, ts);
        l.rowsWidth = w;
        l.rowsStamp = ts;
        return l.rows;
    }

    /** rows for another width (extra windows), cached separately */
    static List<Row> rows(Line l, int w) {
        if (w == width() - 8) return rows(l);
        String ts = stamp(l.time);
        if (l.rowsAlt != null && l.rowsAltW == w && l.rowsAltVer == l.ver && l.rowsAltStamp.equals(ts)) return l.rowsAlt;
        l.rowsAlt = layout(l, w, ts);
        l.rowsAltW = w; l.rowsAltVer = l.ver; l.rowsAltStamp = ts;
        return l.rowsAlt;
    }

    private static List<Row> layout(Line l, int w, String ts) {
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int es = ChatConfig.emojiSize;
        List<Row> out = new ArrayList<>();
        if (l.re != 0) {                                                        // ↪ Name: what they answered
            Row r = new Row();
            String s = "§8  ↪ " + l.reName + ": §7" + l.reText.replace('\n', ' ');
            r.parts.add(fr.trimStringToWidth(s, w)); r.spans.add(new int[]{-1, -1}); r.width = Math.min(w, fr.getStringWidth(s));
            out.add(r);
        }
        Row cur = new Row();
        if (!ts.isEmpty()) { cur.parts.add(ts); cur.spans.add(new int[]{-1, -1}); cur.width = fr.getStringWidth(ts); }
        if (heads() && l.sender != null && !l.bot) { cur.parts.add(new Head(l.sender)); cur.spans.add(new int[]{-1, -1}); cur.width += 10; }
        int plainAt = 0;
        StringBuilder sofar = new StringBuilder();                   // all formatted text so far (for carrying colours over)
        String text = l.formatted;
        if (l.count > 1) text += " §d×" + l.count;
        if (l.out && l.delivered) text += l.read ? " §b✓✓" : " §7✓";
        if (l.replies > 0) text += " §8💬" + l.replies;
        for (Emoji.Piece p : Emoji.split(text)) {
            if (p.emoji != null) {
                int len = p.raw == null ? p.emoji.unicode.length() : p.raw.length();
                if (cur.width + es + 1 > w && cur.width > 0) { out.add(cur); cur = new Row(); }
                cur.parts.add(p.emoji);
                cur.spans.add(new int[]{plainAt, plainAt + len});
                cur.width += es + 1;
                plainAt += len;
                continue;
            }
            // text: word by word (a word keeps the spaces after it); a new line in the message starts a new row
            Matcher m = Pattern.compile("\\n|[^\\S\\n]*\\S*[^\\S\\n]*").matcher(p.text);
            while (m.find()) {
                String word = m.group();
                if (word.isEmpty()) continue;
                if (word.equals("\n")) { out.add(cur); cur = new Row(); plainAt++; sofar.append(' '); continue; }
                String fmt = FontRenderer.getFormatFromString(sofar.toString());
                sofar.append(word);
                int plainLen = TextFormatting.getTextWithoutFormattingCodes(word).length();
                int ww = fr.getStringWidth(word);
                if (cur.width + ww > w && cur.width > 0) {
                    out.add(cur);
                    cur = new Row();
                }
                while (fr.getStringWidth(fmt + word) > w) {              // one very long word: hard split
                    String piece = fr.trimStringToWidth(fmt + word, w - cur.width);
                    if (piece.length() <= fmt.length()) { out.add(cur); cur = new Row(); piece = fr.trimStringToWidth(fmt + word, w); }
                    String body = piece.substring(fmt.length());
                    if (body.isEmpty()) break;
                    int bl = TextFormatting.getTextWithoutFormattingCodes(body).length();
                    cur.parts.add(fmt + body);
                    cur.spans.add(new int[]{plainAt, plainAt + bl});
                    plainAt += bl;
                    out.add(cur);
                    cur = new Row();
                    word = word.substring(body.length());
                    plainLen -= bl;
                }
                cur.parts.add(fmt + word);
                cur.spans.add(new int[]{plainAt, plainAt + plainLen});
                cur.width += fr.getStringWidth(fmt + word);
                plainAt += plainLen;
            }
        }
        if (!cur.parts.isEmpty()) out.add(cur);
        if (!l.reactions.isEmpty()) {                                // ❤ 2  😂 1
            Row r = new Row();
            r.width = 10;
            for (Map.Entry<String, Integer> e : l.reactions.entrySet()) { r.parts.add(new React(e.getKey(), e.getValue())); r.spans.add(new int[]{-1, -1}); r.width += 24; }
            out.add(r);
        }
        return out;
    }

    /** the active tab's rows, oldest first: {line, row} */
    static List<Object[]> visibleRows() {
        List<Object[]> out = new ArrayList<>();
        long bit = 1L << active;
        String q = search.toLowerCase(Locale.ROOT).trim();
        String from = null, in = null;
        Matcher fm = Pattern.compile("(?:^|\\s)from:(\\S+)").matcher(q);
        if (fm.find()) { from = fm.group(1); q = q.replace(fm.group(), " ").trim(); }
        Matcher im = Pattern.compile("(?:^|\\s)in:#?(\\S+)").matcher(q);
        if (im.find()) { in = im.group(1); q = q.replace(im.group(), " ").trim(); }
        // date + place filters (roadmap #13)
        long after = 0, before = Long.MAX_VALUE;
        String dimQ = null;
        double[] near = null;
        Matcher tm = Pattern.compile("(?:^|\\s)(since|after|before|on|dim|near|at):(\\S+)").matcher(q);
        StringBuffer rest = new StringBuffer();
        while (tm.find()) {
            String k = tm.group(1), v = tm.group(2);
            long[] range = when(v);
            switch (k) {
                case "since": { long ago = ago(v); if (ago > 0) after = Math.max(after, System.currentTimeMillis() - ago); break; }
                case "after": if (range != null) after = Math.max(after, range[0]); break;
                case "before": if (range != null) before = Math.min(before, range[0]); break;
                case "on": if (range != null) { after = Math.max(after, range[0]); before = Math.min(before, range[1]); } break;
                case "dim": dimQ = v; break;
                case "near": try { near = new double[]{Double.NaN, Double.NaN, Double.parseDouble(v)}; } catch (NumberFormatException ignored) {} break;
                case "at": {
                    String[] c = v.split(",");
                    try { near = new double[]{Double.parseDouble(c[0]), Double.parseDouble(c[1]), c.length > 2 ? Double.parseDouble(c[2]) : 64}; } catch (Exception ignored) {}
                    break;
                }
                default: break;
            }
            tm.appendReplacement(rest, " ");
        }
        tm.appendTail(rest);
        q = rest.toString().trim();
        Minecraft mc = Minecraft.getMinecraft();
        if (near != null && Double.isNaN(near[0]) && mc.player != null) { near[0] = mc.player.posX; near[1] = mc.player.posZ; }
        int hereDim = mc.player == null ? 0 : mc.player.dimension;
        Set<String> hide = thread != 0 ? Collections.emptySet() : hiddenKinds(activeRule());
        for (Line l : LINES) {
            if (l.time < after || l.time > before) continue;
            if (dimQ != null && !dimMatches(l.dim, dimQ, hereDim)) continue;
            if (near != null && (l.dim != hereDim && l.dim != Integer.MIN_VALUE || l.dim == Integer.MIN_VALUE
                    || (l.x - near[0]) * (l.x - near[0]) + (l.z - near[1]) * (l.z - near[1]) > near[2] * near[2])) continue;
            if (thread != 0 ? l.thread != thread : (l.tabBits & bit) == 0) continue;
            if (ignored(l.sender) || !hide.isEmpty() && hide.contains(kindOf(l))) continue;
            if (from != null && (l.sender == null || !l.sender.toLowerCase(Locale.ROOT).startsWith(from))) continue;
            if (in != null && !l.channel.equals(in) && !l.mode.equals(in)) continue;
            if (!q.isEmpty() && !l.plain.toLowerCase(Locale.ROOT).contains(q)) continue;
            for (Row r : rows(l)) out.add(new Object[]{l, r});
        }
        return out;
    }

    /** "30m" "2h" "1d" "45s" → ms (0 = not a duration) */
    static long ago(String v) {
        Matcher m = Pattern.compile("^(\\d+)([smhdw])$").matcher(v);
        if (!m.find()) return 0;
        long n = Long.parseLong(m.group(1));
        switch (m.group(2)) { case "s": return n * 1000; case "m": return n * 60000; case "h": return n * 3600000; case "d": return n * 86400000; default: return n * 7 * 86400000; }
    }

    /** a time or day → {start, end} ms: HH:mm (today), yyyy-MM-dd, today, yesterday, or a duration ago */
    static long[] when(String v) {
        Calendar c = Calendar.getInstance();
        try {
            if (v.matches("\\d{1,2}:\\d{2}")) {
                String[] p = v.split(":");
                c.set(Calendar.HOUR_OF_DAY, Integer.parseInt(p[0])); c.set(Calendar.MINUTE, Integer.parseInt(p[1])); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                return new long[]{c.getTimeInMillis(), c.getTimeInMillis() + 60000};
            }
            if ("today".equals(v) || "yesterday".equals(v) || v.matches("\\d{4}-\\d{2}-\\d{2}")) {
                if (v.length() == 10 && v.charAt(4) == '-') c.setTime(new SimpleDateFormat("yyyy-MM-dd").parse(v));
                else if ("yesterday".equals(v)) c.add(Calendar.DAY_OF_MONTH, -1);
                c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0);
                return new long[]{c.getTimeInMillis(), c.getTimeInMillis() + 86400000L};
            }
        } catch (Exception ignored) {}
        long a = ago(v);
        return a > 0 ? new long[]{System.currentTimeMillis() - a, System.currentTimeMillis()} : null;
    }

    /** dim:-1 / dim:nether / dim:end / dim:overworld / dim:here (any part of the dimension's name) */
    static boolean dimMatches(int dim, String q, int here) {
        if (dim == Integer.MIN_VALUE) return false;
        if ("here".equals(q)) return dim == here;
        try { return dim == Integer.parseInt(q); } catch (NumberFormatException ignored) {}
        try { return net.minecraft.world.DimensionType.getById(dim).getName().toLowerCase(Locale.ROOT).contains(q); } catch (Throwable t) { return false; }
    }

    // ------------------------------------------------------------------ drawing
    /** where rows were drawn last frame, for the mouse: {x, y, line, row} */
    static final List<Object[]> HITS = new ArrayList<>();
    static int boxX, boxY, boxW, boxH;                                   // the last drawn box (GUI coords)

    static int bgColor() {
        try { return Integer.parseInt(ChatConfig.backgroundColor.replace("#", "").trim(), 16) & 0xFFFFFF; } catch (NumberFormatException e) { return 0x140E22; }
    }

    @Override
    public void drawChat(int updateCounter) {
        if (!ChatConfig.enabled) { super.drawChat(updateCounter); return; }
        if (mc.gameSettings.chatVisibility == net.minecraft.entity.player.EntityPlayer.EnumChatVisibility.HIDDEN) return;
        HITS.clear();
        boolean open = getChatOpen();
        ScaledResolution sr = new ScaledResolution(mc);
        float sc = ChatConfig.scale / 100f;
        int rowH = rowH();
        int ox = ChatConfig.offsetX, oy = ChatConfig.offsetY;
        int lines = open ? ChatConfig.linesOpen : ChatConfig.linesClosed;
        lines = Math.max(1, Math.min(lines, (int) ((sr.getScaledHeight() - 48 - 24 + oy) / (rowH * sc))));   // small windows: fit, leave room for the tabs
        List<Object[]> rows = visibleRows();
        long now = System.currentTimeMillis(), fade = ChatConfig.fadeSeconds * 1000L;
        int end = rows.size() - (open ? scroll : 0), start = Math.max(0, end - lines);
        int w = width();
        GlStateManager.pushMatrix();
        int baseY = sr.getScaledHeight() - 48 - oy;                          // Forge already moved us above the hotbar
        GlStateManager.translate(2 + ox, -oy, 0);
        GlStateManager.scale(sc, sc, 1);
        int shown = 0, topAlpha = 0;
        for (int i = end - 1; i >= start; i--) {
            Line l = (Line) rows.get(i)[0];
            int a = open ? 255 : alpha(now - l.time, fade);
            if (a <= 0) break;
            shown++;
            topAlpha = a;
        }
        if (shown == 0 && !open) { GlStateManager.popMatrix(); return; }
        int h = open ? lines * rowH : shown * rowH;
        // the box
        int bga = (int) (ChatConfig.opacity * 2.55f * (open ? 1f : topAlpha / 255f));
        int col = bgColor();
        switch (ChatConfig.background) {
            case "none": break;
            case "solid": box(0, -h - 2, w, 1, Math.min(255, bga + 60) << 24 | col); break;
            case "pride": PrideFrame.gradient(0, -h - 2, w, 1, bga << 24 | 0x2A1A48, bga << 24 | 0x48203C); break;
            default: box(0, -h - 2, w, 1, bga << 24 | col);
        }
        if ("always".equals(ChatConfig.rainbowEdge) || open && "open".equals(ChatConfig.rainbowEdge)) {
            int sw = w / PrideFrame.RAINBOW.length;
            for (int i = 0; i < PrideFrame.RAINBOW.length; i++)
                Gui.drawRect(i * sw + (i == 0 && ChatConfig.rounded ? 1 : 0), -h - 3, i == PrideFrame.RAINBOW.length - 1 ? w - (ChatConfig.rounded ? 1 : 0) : (i + 1) * sw, -h - 2,
                        (open ? 255 : topAlpha) << 24 | (PrideFrame.RAINBOW[i] & 0xFFFFFF));
        }
        FontRenderer fr = mc.fontRenderer;
        int y = -rowH;
        Line newest = rows.isEmpty() ? null : (Line) rows.get(rows.size() - 1)[0];
        int slide = ChatConfig.slideIn && newest != null && end == rows.size() && now - newest.time < 160 ? (int) ((160 - (now - newest.time)) * rowH / 160) : 0;
        for (int i = end - 1; i >= start; i--) {
            Line l = (Line) rows.get(i)[0];
            Row r = (Row) rows.get(i)[1];
            int a = open ? 255 : alpha(now - l.time, fade);
            if (a <= 3) break;
            int ry = y + (l == newest ? slide : 0);
            drawRow(fr, l, r, 0, ry, w, a);
            HITS.add(new Object[]{2 + ox + Math.round(4 * sc), baseY + Math.round(ry * sc), l, r});
            y -= rowH;
        }
        if (open && rows.size() > lines) {                                     // scroll bar
            int total = rows.size(), bar = Math.max(6, h * lines / total), pos = (h - bar) * (total - end) / Math.max(1, total - lines);
            Gui.drawRect(w - 2, -h + (h - bar) - pos, w, -h + (h - bar) - pos + bar, 0xC0F5A9B8);
        }
        boxX = 2 + ox; boxY = baseY - Math.round((h + 2) * sc); boxW = Math.round(w * sc); boxH = Math.round((h + 3) * sc);
        GlStateManager.popMatrix();
        GlStateManager.color(1, 1, 1, 1);
        GlStateManager.enableBlend();
    }

    /** one row of a line at (left, ry): mention / own / friend tint, then its text, emoji, face and reactions */
    static void drawRow(FontRenderer fr, Line l, Row r, int left, int ry, int w, int a) {
        int rowH = rowH();
        int mentionCol = 0xF5A9B8;
        try { mentionCol = Integer.parseInt(ChatConfig.mentionColor.replace("#", ""), 16); } catch (NumberFormatException ignored) {}
        int ownCol = 0x5BCEFA;
        try { ownCol = Integer.parseInt(ChatConfig.ownColor.replace("#", ""), 16); } catch (NumberFormatException ignored) {}
        if (l.band == 1) a = a * 65 / 100;                                     // far away: fainter (roadmap #2)
        if (l.band >= 2) a = a * 45 / 100;
        String kind = ChatConfig.lineAnimation;                              // the entrance (roadmap #8)
        int len = l.plain.length();
        float p = l.history ? 1 : Anim.progress(kind, System.currentTimeMillis() - l.time, len);
        long seed = l.msgId ^ l.id ^ len;
        if (p < 1) {
            float[] mo = Anim.motion(kind, p, w, rowH);
            a = (int) (a * mo[4]);
            float pivot = left + r.width / 2f;
            GlStateManager.pushMatrix();
            GlStateManager.translate(pivot + mo[0], ry + mo[1] + rowH / 2f, 0);
            GlStateManager.scale(mo[2], mo[3], 1);
            GlStateManager.translate(-pivot, -ry - rowH / 2f, 0);
            if ("hologram".equals(kind)) {
                Gui.drawRect(left, ry, left + w, ry + rowH, (a * 50 / 255) << 24 | 0x00E5FF);
                Gui.drawRect(left, ry + (int) (p * rowH * 3) % rowH, left + w, ry + (int) (p * rowH * 3) % rowH + 1, (a * 120 / 255) << 24 | 0x7FF7FF);
            }
        }
        if (l.mention) {                                                      // a mention: coloured edge + a warm tint
            Gui.drawRect(left, ry, left + w, ry + rowH, (a * 45 / 255) << 24 | mentionCol);
            Gui.drawRect(left, ry, left + 2, ry + rowH, a << 24 | mentionCol);
        } else if (l.mine && ChatConfig.tintOwn) Gui.drawRect(left, ry, left + w, ry + rowH, (a * 30 / 255) << 24 | ownCol);
        else if (friend(l.sender)) Gui.drawRect(left, ry, left + 1, ry + rowH, a << 24 | 0x7CFC9A);
        if (thread != 0 && l.msgId == thread) Gui.drawRect(left, ry, left + 2, ry + rowH, a << 24 | 0x5BCEFA);
        int x = left + 4, ty = ry + (rowH - 8) / 2;
        {
            for (int k = 0; k < r.parts.size(); k++) {
                Object part = r.parts.get(k);
                int[] span = r.spans.get(k);
                if (p < 1 && span[0] >= 0 && !(part instanceof String) && Anim.hidden(kind, p, span[0], len, seed)) { x += partWidth(part, fr); continue; }
                if (part instanceof String) {
                    String s = (String) part;
                    if (p < 1 && span[0] >= 0) s = Anim.reveal(s, kind, p, span[0], len, seed);
                    String fx = span[0] >= l.bodyStart && !l.history ? l.fx : "";
                    if (fx.contains("type") && span[0] >= 0) {                  // the sender's typewriter effect (#47)
                        int bl = Math.max(1, len - l.bodyStart);
                        s = Anim.reveal(s, "typewriter", Math.min(1f, (System.currentTimeMillis() - l.time) / (45f * bl)), span[0] - l.bodyStart, bl, seed);
                    }
                    Anim.text(fr, s, x, ty, 0xFFFFFF | Math.max(4, a) << 24, ChatConfig.textShadow, fx, span[0]);
                    x += fr.getStringWidth(s);
                } else if (part instanceof Emoji.E) {
                    int es = ChatConfig.emojiSize;
                    GlStateManager.color(1, 1, 1, a / 255f);
                    Emoji.draw((Emoji.E) part, x, ry + (rowH - es) / 2, es);
                    x += es + 1;
                } else if (part instanceof Head) {
                    drawHead(((Head) part).name, x, ry + (rowH - 8) / 2, 8, a);
                    x += 10;
                } else if (part instanceof React) {
                    React re = (React) part;
                    Gui.drawRect(x - 1, ry, x + 22, ry + rowH - 1, (a * 80 / 255) << 24 | 0x3A2A58);
                    Emoji.E e = Emoji.byCode(re.code);
                    GlStateManager.color(1, 1, 1, a / 255f);
                    if (e != null) Emoji.draw(e, x, ry, 8);
                    fr.drawStringWithShadow(String.valueOf(re.n), x + 10, ty, 0xFFFFFF | Math.max(4, a) << 24);
                    x += 24;
                }
            }
        }
        if (p < 1) GlStateManager.popMatrix();
    }

    // ------------------------------------------------------------------ extra windows (#41)
    /** where each extra window was drawn last frame: {x, y, w, h, index} (GUI coords) */
    static final List<int[]> WINDOWS = new ArrayList<>();

    static String[] window(int i) {
        String[] p = ChatConfig.windows[i].split("\\s*\\|\\s*", -1);
        return p.length >= 5 ? p : null;
    }

    private static int num(String s, int def) { try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return def; } }

    /** every extra chat window: "rule | x | y | width | lines" in the config; a header to drag / close while chat is open */
    static void drawWindows(int sw, int sh) {
        WINDOWS.clear();
        if (!ChatConfig.enabled) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.gameSettings.chatVisibility == net.minecraft.entity.player.EntityPlayer.EnumChatVisibility.HIDDEN) return;
        FontRenderer fr = mc.fontRenderer;
        boolean open = mc.currentScreen instanceof net.minecraft.client.gui.GuiChat;
        long now = System.currentTimeMillis(), fade = ChatConfig.fadeSeconds * 1000L;
        int rowH = rowH();
        for (int i = 0; i < ChatConfig.windows.length; i++) {
            String[] p = window(i);
            if (p == null) continue;
            String rule = p[0].trim();
            int w = Math.max(80, Math.min(600, num(p[3], 200))), n = Math.max(1, Math.min(40, num(p[4], 8)));
            int x = Math.max(0, Math.min(sw - w, num(p[1], 4))), y = Math.max(0, Math.min(sh - 24, num(p[2], 4)));
            Tab tab = null;
            for (Tab t : TABS) if (t.rule.equals(rule)) tab = t;
            if (tab == null) tab = new Tab(GuiPrideChat.describe(rule), 0x5BCEFA, rule, "none", "", true);
            Set<String> hide = hiddenKinds(rule);
            List<Object[]> rows = new ArrayList<>();                            // newest first
            for (int k = LINES.size() - 1; k >= 0 && rows.size() < n; k--) {
                Line l = LINES.get(k);
                if (!tab.matches(l) || ignored(l.sender) || hide.contains(kindOf(l))) continue;
                List<Row> rs = rows(l, w - 8);
                for (int j = rs.size() - 1; j >= 0 && rows.size() < n; j--) rows.add(new Object[]{l, rs.get(j)});
            }
            int shown = 0, topA = 255;
            for (Object[] r : rows) { int a = alpha(now - ((Line) r[0]).time, fade); if (!open && a <= 0) break; shown++; topA = a; }
            WINDOWS.add(new int[]{x, y, w, n * rowH + 12, i});
            if (shown == 0 && !open) continue;
            int bottom = y + 12 + n * rowH, h = (open ? n : shown) * rowH;
            int bga = (int) (ChatConfig.opacity * 2.55f * (open ? 1f : topA / 255f));
            box(x, bottom - h - 1, x + w, bottom + 1, bga << 24 | bgColor());
            if (open) {
                Gui.drawRect(x, y, x + w, y + 11, 0xE02A2140);
                Gui.drawRect(x, y, x + w, y + 1, 0xFF000000 | tab.color);
                fr.drawStringWithShadow("§f" + tab.name + " §8✥ drag", x + 4, y + 2, 0xFFFFFF);
                fr.drawStringWithShadow("§c✖", x + w - 9, y + 2, 0xFFFFFF);
            }
            int ry = bottom - rowH;
            for (Object[] o : rows) {
                Line l = (Line) o[0];
                int a = open ? 255 : alpha(now - l.time, fade);
                if (a <= 3) break;
                drawRow(fr, l, (Row) o[1], x, ry, w, a);
                ry -= rowH;
            }
        }
        GlStateManager.color(1, 1, 1, 1);
    }

    /** a tab gets its own window (top-right, under the others) */
    static void popOut(String rule) {
        List<String> l = new ArrayList<>(Arrays.asList(ChatConfig.windows));
        int sw = new ScaledResolution(Minecraft.getMinecraft()).getScaledWidth();
        l.add(rule + " | " + (sw - 204) + " | " + (4 + l.size() * 100) + " | 200 | 8");
        ChatConfig.windows = l.toArray(new String[0]);
        ClientSide.saved();
    }

    static void moveWindow(int i, int x, int y) {
        String[] p = window(i);
        if (p == null) return;
        p[1] = String.valueOf(x); p[2] = String.valueOf(y);
        ChatConfig.windows[i] = String.join(" | ", p);
    }

    static void closeWindow(int i) {
        List<String> l = new ArrayList<>(Arrays.asList(ChatConfig.windows));
        if (i < l.size()) l.remove(i);
        ChatConfig.windows = l.toArray(new String[0]);
        ClientSide.saved();
    }

    /** a box with (optionally) rounded corners */
    static void box(int x1, int y1, int x2, int y2, int c) {
        if (!ChatConfig.rounded) { Gui.drawRect(x1, y1, x2, y2, c); return; }
        Gui.drawRect(x1 + 1, y1, x2 - 1, y1 + 1, c);
        Gui.drawRect(x1, y1 + 1, x2, y2 - 1, c);
        Gui.drawRect(x1 + 1, y2 - 1, x2 - 1, y2, c);
    }

    /** a player's face (+ hat layer) from their skin */
    static void drawHead(String name, int x, int y, int size, int alpha) {
        Minecraft mc = Minecraft.getMinecraft();
        ResourceLocation skin = null;
        if (mc.getConnection() != null) {
            NetworkPlayerInfo info = mc.getConnection().getPlayerInfo(name);
            if (info != null) skin = info.getLocationSkin();
        }
        if (skin == null) skin = net.minecraft.client.resources.DefaultPlayerSkin.getDefaultSkinLegacy();
        mc.getTextureManager().bindTexture(skin);
        GlStateManager.enableBlend();
        GlStateManager.color(1, 1, 1, alpha / 255f);
        Gui.drawScaledCustomSizeModalRect(x, y, 8, 8, 8, 8, size, size, 64, 64);
        Gui.drawScaledCustomSizeModalRect(x, y, 40, 8, 8, 8, size, size, 64, 64);
        GlStateManager.color(1, 1, 1, 1);
    }

    static int alpha(long age, long fade) {
        switch (ChatConfig.fadeStyle) {
            case "never": return 255;
            case "instant": return age >= fade ? 0 : 255;
            default:
                if (age >= fade) return 0;
                long left = fade - age;
                return left > 1000 ? 255 : (int) (255 * left / 1000);
        }
    }

    // ------------------------------------------------------------------ the mouse (links, name hovers)
    static int partWidth(Object part, FontRenderer fr) {
        if (part instanceof String) return fr.getStringWidth((String) part);
        if (part instanceof Emoji.E) return ChatConfig.emojiSize + 1;
        if (part instanceof Head) return 10;
        return 24;
    }

    @Override
    public ITextComponent getChatComponent(int mouseX, int mouseY) {
        if (!ChatConfig.enabled) return super.getChatComponent(mouseX, mouseY);
        if (!getChatOpen()) return null;
        ScaledResolution sr = new ScaledResolution(mc);
        int f = sr.getScaleFactor();
        int gx = mouseX / f, gy = (mc.displayHeight - mouseY) / f;           // raw mouse → GUI coords (top-left origin)
        float sc = ChatConfig.scale / 100f;
        int rowH = Math.round(rowH() * sc);
        FontRenderer fr = mc.fontRenderer;
        for (Object[] hit : HITS) {
            int hx = (int) hit[0], hy = (int) hit[1];
            if (gy < hy || gy >= hy + rowH) continue;
            Line l = (Line) hit[2];
            Row r = (Row) hit[3];
            int x = hx;
            for (int k = 0; k < r.parts.size(); k++) {
                Object part = r.parts.get(k);
                int[] span = r.spans.get(k);
                int pw = Math.round(partWidth(part, fr) * sc);
                if (gx >= x && gx < x + pw && span[0] >= 0) {
                    if (part instanceof String) {
                        String plain = TextFormatting.getTextWithoutFormattingCodes((String) part);
                        int cx = x, idx = span[0];
                        for (int c = 0; c < plain.length(); c++) {
                            int cw = Math.round(fr.getCharWidth(plain.charAt(c)) * sc);
                            if (gx < cx + cw) { idx = span[0] + c; break; }
                            cx += cw;
                        }
                        return componentAt(l.comp, idx);
                    }
                    return componentAt(l.comp, span[0]);
                }
                x += pw;
            }
        }
        return null;
    }

    /** the piece of the message that owns character `idx` of its plain text */
    static ITextComponent componentAt(ITextComponent comp, int idx) {
        int at = 0;
        for (ITextComponent c : comp) {
            int len = c.getUnformattedComponentText().length();
            if (idx < at + len) return c;
            at += len;
        }
        return null;
    }

    /** the Line under the mouse (right-click menus), or null */
    static Line lineAt(int gx, int gy) {
        float sc = ChatConfig.scale / 100f;
        int rowH = Math.round(rowH() * sc);
        for (Object[] hit : HITS) {
            int hy = (int) hit[1];
            if (gy >= hy && gy < hy + rowH && gx >= boxX && gx < boxX + boxW) return (Line) hit[2];
        }
        return null;
    }
}
