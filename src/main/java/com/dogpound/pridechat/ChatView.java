package com.dogpound.pridechat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.init.SoundEvents;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.event.ClickEvent;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PrideChat's chat box. It stands in for Minecraft's (so it sees EVERY message, even ones mods print directly),
 * keeps each line with its time, sender and tabs, and draws the active tab: emoji pictures inline, mentions
 * highlighted, timestamps, a tab strip with unread counts while chat is open. Clicking links / hovering names
 * still work — getChatComponent maps the mouse back onto the message pieces.
 */
public class ChatView extends GuiNewChat {
    // ------------------------------------------------------------------ the store
    public static final class Line {
        final ITextComponent comp;
        final String formatted, plain, sender;
        final long time;
        final int id;
        final boolean mention, mine;
        long tabBits;
        List<Row> rows;
        int rowsWidth = -1;
        Line(ITextComponent comp, int id, String sender, boolean mention, boolean mine) {
            this.comp = comp; this.id = id; this.sender = sender; this.mention = mention; this.mine = mine;
            formatted = comp.getFormattedText(); plain = comp.getUnformattedText(); time = System.currentTimeMillis();
        }
    }

    /** one drawn row of a line: pieces to draw, each remembering where its characters sit in the message */
    static final class Row {
        final List<Object> parts = new ArrayList<>();     // String (with § codes) or Emoji.E
        final List<int[]> spans = new ArrayList<>();      // per part: {plainStart, plainEnd} into Line.plain (-1 = timestamp)
        int width;
    }

    static final class Tab {
        final String name, rule;
        final int color;
        final Pattern re;
        int unread;
        Tab(String name, int color, String rule) {
            this.name = name; this.color = color; this.rule = rule;
            Pattern p = null;
            if (!rule.startsWith("@")) try { p = Pattern.compile(rule); } catch (Exception ignored) {}
            this.re = p;
        }
        boolean matches(Line l) {
            switch (rule) {
                case "@all": return true;
                case "@player": return l.sender != null;
                case "@system": return l.sender == null;
                case "@mention": return l.mention;
                default: return re != null && re.matcher(l.plain).find();
            }
        }
    }

    static final List<Line> LINES = new ArrayList<>();
    static final List<Tab> TABS = new ArrayList<>();
    static int active;
    static int scroll;                                   // rows scrolled up from the newest
    static String search = "";
    private static final int MAX = 2500;
    private static final Pattern ANGLE = Pattern.compile("^<([A-Za-z0-9_]{2,16})> ");
    private static final Pattern NAME_COLON = Pattern.compile("^(?:\\[[^\\]]{1,24}\\] ?)*(?:[^A-Za-z0-9_ ]+ ?)?([A-Za-z0-9_]{3,16}) ?[:»>] ");

    private final Minecraft mc;

    public ChatView(Minecraft mc) { super(mc); this.mc = mc; loadTabs(); }

    static void loadTabs() {
        List<Tab> t = new ArrayList<>();
        for (String s : ChatConfig.tabList) {
            String[] p = s.split("\\s*\\|\\s*", 3);
            if (p.length < 3) continue;
            int c;
            try { c = Integer.parseInt(p[1].replace("#", ""), 16); } catch (NumberFormatException e) { c = 0xF5A9B8; }
            t.add(new Tab(p[0].trim(), c, p[2].trim()));
        }
        if (t.isEmpty()) t.add(new Tab("All", 0xF5A9B8, "@all"));
        TABS.clear();
        TABS.addAll(t);
        if (active >= TABS.size()) active = 0;
        for (Line l : LINES) l.tabBits = tabsOf(l);
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

    // ------------------------------------------------------------------ Minecraft's chat calls
    @Override
    public void printChatMessageWithOptionalDeletion(ITextComponent comp, int id) {
        super.printChatMessageWithOptionalDeletion(comp, id);            // keep vanilla's copy (turning PrideChat off shows it)
        if (id != 0) LINES.removeIf(l -> l.id == id);
        String sender = senderOf(comp);
        String me = mc.player == null ? "" : mc.player.getName();
        boolean mine = sender != null && sender.equalsIgnoreCase(me);
        boolean mention = ChatConfig.mentions && !mine && mentions(comp.getUnformattedText(), me);
        Line l = new Line(comp, id, sender, mention, mine);
        l.tabBits = tabsOf(l);
        LINES.add(l);
        if (LINES.size() > MAX) LINES.remove(0);
        for (int i = 0; i < TABS.size(); i++) if (i != active && (l.tabBits & (1L << i)) != 0 && !ignored(sender)) TABS.get(i).unread++;
        if (scroll > 0 && (l.tabBits & (1L << active)) != 0) scroll += rows(l).size();   // reading older lines: stay put
        if (mention && ChatConfig.mentionSound && mc.player != null)
            mc.player.playSound(SoundEvents.BLOCK_NOTE_PLING, 0.7f, 1.6f);
    }

    static boolean mentions(String text, String me) {
        String t = text.toLowerCase(Locale.ROOT);
        if (!me.isEmpty() && Pattern.compile("(?<![A-Za-z0-9_])@?" + Pattern.quote(me.toLowerCase(Locale.ROOT)) + "(?![A-Za-z0-9_])").matcher(t).find()) {
            // their own "<Name>" prefix is not a mention
            return !t.startsWith("<" + me.toLowerCase(Locale.ROOT) + ">");
        }
        for (String w : ChatConfig.mentionWords) if (!w.trim().isEmpty() && t.contains(w.trim().toLowerCase(Locale.ROOT))) return true;
        return false;
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
    private static final SimpleDateFormat[] FMT = {null};
    private static String fmtKey = "";

    static String stamp(long t) {
        if (!ChatConfig.timestamps) return "";
        if (!ChatConfig.timeFormat.equals(fmtKey)) {
            fmtKey = ChatConfig.timeFormat;
            try { FMT[0] = new SimpleDateFormat(fmtKey); } catch (IllegalArgumentException e) { FMT[0] = new SimpleDateFormat("HH:mm"); }
        }
        return "§8" + FMT[0].format(new Date(t)) + " §r";
    }

    static int width() { return ChatConfig.width; }

    static List<Row> rows(Line l) {
        int w = width() - 8;
        if (l.rows != null && l.rowsWidth == w) return l.rows;
        FontRenderer fr = Minecraft.getMinecraft().fontRenderer;
        int es = ChatConfig.emojiSize;
        List<Row> out = new ArrayList<>();
        Row cur = new Row();
        String ts = stamp(l.time);
        if (!ts.isEmpty()) { cur.parts.add(ts); cur.spans.add(new int[]{-1, -1}); cur.width = fr.getStringWidth(ts); }
        int plainAt = 0;
        StringBuilder sofar = new StringBuilder();                   // all formatted text so far (for carrying colours over)
        for (Emoji.Piece p : Emoji.split(l.formatted)) {
            if (p.emoji != null) {
                int len = p.raw == null ? p.emoji.unicode.length() : p.raw.length();
                if (cur.width + es + 1 > w && cur.width > 0) { out.add(cur); cur = new Row(); }
                cur.parts.add(p.emoji);
                cur.spans.add(new int[]{plainAt, plainAt + len});
                cur.width += es + 1;
                plainAt += len;
                continue;
            }
            // text: word by word (a word keeps the spaces after it)
            Matcher m = Pattern.compile("\\S*\\s*").matcher(p.text);
            while (m.find()) {
                String word = m.group();
                if (word.isEmpty()) continue;
                String fmt = FontRenderer.getFormatFromString(sofar.toString());
                sofar.append(word);
                int plainLen = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(word).length();
                int ww = fr.getStringWidth(word);
                if (cur.width + ww > w && cur.width > 0) {
                    out.add(cur);
                    cur = new Row();
                }
                while (fr.getStringWidth(fmt + word) > w) {              // one very long word: hard split
                    String piece = fr.trimStringToWidth(fmt + word, w - cur.width);
                    if (piece.length() <= fmt.length()) { out.add(cur); cur = new Row(); piece = fr.trimStringToWidth(fmt + word, w); }
                    String body = piece.substring(fmt.length());
                    int bl = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(body).length();
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
        l.rows = out;
        l.rowsWidth = w;
        return out;
    }

    /** the active tab's rows, oldest first: {line, row} */
    static List<Object[]> visibleRows() {
        List<Object[]> out = new ArrayList<>();
        long bit = 1L << active;
        String q = search.toLowerCase(Locale.ROOT);
        for (Line l : LINES) {
            if ((l.tabBits & bit) == 0 || ignored(l.sender)) continue;
            if (!q.isEmpty() && !l.plain.toLowerCase(Locale.ROOT).contains(q)) continue;
            for (Row r : rows(l)) out.add(new Object[]{l, r});
        }
        return out;
    }

    // ------------------------------------------------------------------ drawing
    /** where rows were drawn last frame, for the mouse: {x, y, line, row} */
    static final List<Object[]> HITS = new ArrayList<>();
    static int boxX, boxY, boxW, boxH;                                   // the last drawn box (GUI coords, unscaled)

    @Override
    public void drawChat(int updateCounter) {
        if (!ChatConfig.enabled) { super.drawChat(updateCounter); return; }
        if (mc.gameSettings.chatVisibility == net.minecraft.entity.player.EntityPlayer.EnumChatVisibility.HIDDEN) return;
        HITS.clear();
        boolean open = getChatOpen();
        ScaledResolution sr = new ScaledResolution(mc);
        float sc = ChatConfig.scale / 100f;
        int lines = open ? ChatConfig.linesOpen : ChatConfig.linesClosed;
        lines = Math.max(3, Math.min(lines, (int) ((sr.getScaledHeight() - 48 - 24) / (9 * (ChatConfig.scale / 100f)))));   // small windows: fit, leave room for the tabs
        List<Object[]> rows = visibleRows();
        long now = System.currentTimeMillis(), fade = ChatConfig.fadeSeconds * 1000L;
        int end = rows.size() - (open ? scroll : 0), start = Math.max(0, end - lines);
        int w = width(), rowH = 9;
        GlStateManager.pushMatrix();
        int baseY = sr.getScaledHeight() - 48;                              // Forge already moved us here (just above the hotbar) —
        GlStateManager.translate(2, 0, 0);                                  // baseY is only for the mouse hit boxes below
        GlStateManager.scale(sc, sc, 1);
        // background: while closed, only behind the lines that haven't faded
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
        int bg = (int) (ChatConfig.opacity * 2.55f * (open ? 1f : topAlpha / 255f));
        Gui.drawRect(0, -h - 2, w, 1, bg << 24 | 0x140E22);
        if (open) {                                                           // the Pride top edge
            int sw = w / PrideFrame.RAINBOW.length;
            for (int i = 0; i < PrideFrame.RAINBOW.length; i++) Gui.drawRect(i * sw, -h - 3, i == PrideFrame.RAINBOW.length - 1 ? w : (i + 1) * sw, -h - 2, PrideFrame.RAINBOW[i]);
        }
        FontRenderer fr = mc.fontRenderer;
        int y = -rowH;
        for (int i = end - 1; i >= start; i--) {
            Line l = (Line) rows.get(i)[0];
            Row r = (Row) rows.get(i)[1];
            int a = open ? 255 : alpha(now - l.time, fade);
            if (a <= 3) break;
            if (l.mention) {                                                  // a mention: pink edge + a warm tint
                Gui.drawRect(0, y, w, y + rowH, (a * 45 / 255) << 24 | 0xF5A9B8);
                Gui.drawRect(0, y, 2, y + rowH, a << 24 | 0xF5A9B8);
            }
            int x = 4;
            for (int k = 0; k < r.parts.size(); k++) {
                Object part = r.parts.get(k);
                if (part instanceof String) {
                    fr.drawStringWithShadow((String) part, x, y + 1, 0xFFFFFF | a << 24);
                    x += fr.getStringWidth((String) part);
                } else {
                    Emoji.E e = (Emoji.E) part;
                    int es = ChatConfig.emojiSize;
                    GlStateManager.color(1, 1, 1, a / 255f);
                    Emoji.draw(e, x, y + (rowH - es) / 2, es);
                    x += es + 1;
                }
            }
            HITS.add(new Object[]{2 + Math.round(4 * sc), baseY + Math.round(y * sc), l, r});
            y -= rowH;
        }
        if (open && rows.size() > lines) {                                     // scroll bar
            int total = rows.size(), bar = Math.max(6, h * lines / total), pos = (h - bar) * (total - end) / Math.max(1, total - lines);
            Gui.drawRect(w - 2, -h + (h - bar) - pos, w, -h + (h - bar) - pos + bar, 0xC0F5A9B8);
        }
        boxX = 2; boxY = baseY - Math.round((h + 2) * sc); boxW = Math.round(w * sc); boxH = Math.round((h + 3) * sc);
        GlStateManager.popMatrix();
        GlStateManager.enableBlend();
    }

    private static int alpha(long age, long fade) {
        if (age >= fade) return 0;
        long left = fade - age;
        return left > 1000 ? 255 : (int) (255 * left / 1000);
    }

    // ------------------------------------------------------------------ the mouse (links, name hovers)
    @Override
    public ITextComponent getChatComponent(int mouseX, int mouseY) {
        if (!ChatConfig.enabled) return super.getChatComponent(mouseX, mouseY);
        if (!getChatOpen()) return null;
        ScaledResolution sr = new ScaledResolution(mc);
        int f = sr.getScaleFactor();
        int gx = mouseX / f, gy = (mc.displayHeight - mouseY) / f;           // raw mouse → GUI coords (top-left origin)
        float sc = ChatConfig.scale / 100f;
        int rowH = Math.round(9 * sc);
        for (Object[] hit : HITS) {
            int hx = (int) hit[0], hy = (int) hit[1];
            if (gy < hy || gy >= hy + rowH) continue;
            Line l = (Line) hit[2];
            Row r = (Row) hit[3];
            int x = hx;
            FontRenderer fr = mc.fontRenderer;
            for (int k = 0; k < r.parts.size(); k++) {
                Object part = r.parts.get(k);
                int[] span = r.spans.get(k);
                if (part instanceof String) {
                    String s = (String) part;
                    int pw = Math.round(fr.getStringWidth(s) * sc);
                    if (gx >= x && gx < x + pw && span[0] >= 0) {
                        String plain = net.minecraft.util.text.TextFormatting.getTextWithoutFormattingCodes(s);
                        int cx = x, idx = span[0];
                        for (int c = 0; c < plain.length(); c++) {
                            int cw = Math.round(fr.getCharWidth(plain.charAt(c)) * sc);
                            if (gx < cx + cw) { idx = span[0] + c; break; }
                            cx += cw;
                        }
                        return componentAt(l.comp, idx);
                    }
                    x += pw;
                } else {
                    int pw = Math.round((ChatConfig.emojiSize + 1) * sc);
                    if (gx >= x && gx < x + pw && span[0] >= 0) return componentAt(l.comp, span[0]);
                    x += pw;
                }
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
        int rowH = Math.round(9 * sc);
        for (Object[] hit : HITS) {
            int hy = (int) hit[1];
            if (gy >= hy && gy < hy + rowH && gx >= boxX && gx < boxX + boxW) return (Line) hit[2];
        }
        return null;
    }
}
