package com.dogpound.pridechat;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.event.ForgeEventFactory;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat screen: Minecraft's chat input (tab-complete, ↑ history, clickable links all still work) with no length
 * limit, plus: the range button (whisper / local / shout / everyone), format buttons (colours, bold, rainbow, emotes),
 * emoji picker + :shortcode suggestions, a live preview with emoji, a letter counter, Shift+Enter new lines,
 * Ctrl+Enter shout / Alt+Enter whisper, typing dots, tabs (right-click: close / mute / pop-ups), a right-click menu on
 * messages (reply, react, translate, copy, coords, report, emotes, friend, ignore), drag to move / resize the chat.
 */
public class GuiPrideChat extends GuiChat {
    private boolean picker, searching, palette, sent, filters;
    private int pickGroup = -2, pickScroll;                               // -2 = recent, -1 = all
    private GuiTextField pickSearch, searchBox;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> clicks = new ArrayList<>();
    private final List<int[]> rboxes = new ArrayList<>();
    private final List<Runnable> rclicks = new ArrayList<>();
    private String hover;
    private static final LinkedList<String> RECENT = new LinkedList<>();
    private List<String[]> menu;                                          // {label, action}
    private int menuX, menuY;
    private ChatView.Line menuLine;
    private List<Emoji.E> suggest = Collections.emptyList();
    private int drag;                                                     // 0 none, 1 move, 2 resize
    private int dragX, dragY, startA, startB, dragWin;
    private String nudge;                                                 // "this might sound harsh" bar
    private static String kinder;
    private static final String[] REACTIONS = {"red_heart", "joy", "thumbsup", "thumbsdown", "rage", "open_mouth", "question"};
    private static final Pattern COORDS = Pattern.compile("(-?\\d{1,7})[ ,/]+(-?\\d{1,3})[ ,/]+(-?\\d{1,7})");

    public GuiPrideChat(String start) { super(start); }

    private static final int BTNS = 6 * 22;

    @Override
    public void initGui() {
        super.initGui();
        inputField.setMaxStringLength(ChatConfig.maxLength);
        inputField.width = width - 4 - BTNS - 2;
        if (ChatConfig.keepDraft && inputField.getText().isEmpty() && !ClientSide.draft.isEmpty()) inputField.setText(ClientSide.draft);
        pickSearch = new GuiTextField(90, fontRenderer, 0, 0, 120, 12);
        pickSearch.setMaxStringLength(30);
        searchBox = new GuiTextField(91, fontRenderer, 0, 0, 160, 12);
        searchBox.setMaxStringLength(60);
        searchBox.setText(ChatView.search);
        searching = !ChatView.search.isEmpty();
        kinder = null;
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        if (!sent && ChatConfig.keepDraft) ClientSide.draft = inputField.getText();
        ClientSide.typing(false);
        ChatView.search = "";
        ChatView.scroll = 0;
        ChatView.thread = 0;
    }

    private void click(int x, int y, int w, int h, Runnable r) { boxes.add(new int[]{x, y, w, h}); clicks.add(r); }
    private void rclick(int x, int y, int w, int h, Runnable r) { rboxes.add(new int[]{x, y, w, h}); rclicks.add(r); }

    private boolean btn(int x, int y, int w, int h, String label, int color, Runnable r, int mx, int my) {
        boolean over = PrideFrame.button(x, y, w, h, label, color, mx, my);
        click(x, y, w, h, r);
        return over;
    }

    // ------------------------------------------------------------------ sending
    /** every send from this screen (Enter, menu actions) ends up here */
    @Override
    public void sendChatMessage(String msg, boolean addToChat) { send(msg, addToChat, null); }

    private void send(String msg, boolean addToChat, String forceMode) {
        msg = ForgeEventFactory.onClientSendMessage(msg);
        if (msg == null || msg.isEmpty()) return;
        if (addToChat) mc.ingameGUI.getChatGUI().addToSentMessages(msg);
        if (msg.startsWith("/") && ClientCommandHandler.instance.executeCommand(mc.player, msg) != 0) return;
        ClientSide.submit(msg, forceMode);
    }

    private static final Pattern HARSH = Pattern.compile("(?i)\\b(stupid|idiot|shut up|hate you|kys|loser|dumb|trash|stfu|ugly)\\b");

    private boolean harsh(String t) {
        if (HARSH.matcher(t).find()) return true;
        int letters = 0, caps = 0;
        for (char c : t.toCharArray()) if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) caps++; }
        return letters >= 10 && caps * 100 / letters >= 80;
    }

    static void toneAnswer(String json) {
        try {
            JsonObject o = new JsonParser().parse(json.substring(json.indexOf('{'), json.lastIndexOf('}') + 1)).getAsJsonObject();
            if (o.has("gentler") && (!o.has("harsh") || o.get("harsh").getAsBoolean())) kinder = o.get("gentler").getAsString();
        } catch (Throwable ignored) {}
    }

    private void enter(String forceMode) {
        String s = inputField.getText().trim();
        if (s.isEmpty()) { mc.displayGuiScreen(null); return; }
        if (ChatConfig.toneNudge && nudge == null && !s.startsWith("/") && harsh(s)) {
            nudge = s;
            if (ClientSide.serverAI) ClientSide.ai("tone", new NBTTagCompound(), s.replace('⏎', ' '));
            return;
        }
        sent = true;
        ClientSide.draft = "";
        if (ChatView.thread != 0 && ClientSide.replyId == 0 && !s.startsWith("/")) {          // in a thread: answer it
            ChatView.Line root = ChatView.byId(ChatView.thread);
            if (root != null) { ClientSide.replyId = root.msgId; ClientSide.replyName = root.sender == null ? "?" : root.sender; ClientSide.replyText = ChatView.body(root); }
        }
        send(s, true, forceMode);
        mc.displayGuiScreen(null);
    }

    // ------------------------------------------------------------------ drawing
    @Override
    public void drawScreen(int mx, int my, float pt) {
        boxes.clear(); clicks.clear(); rboxes.clear(); rclicks.clear(); hover = null;
        super.drawScreen(mx, my, pt);                                          // input line, link hovers
        Gui.drawRect(2, height - 14, width - 2, height - 13, 0x80F5A9B8);
        String text = inputField.getText();
        drawSpelling(text);
        // the letter counter, inside the input bar on the right
        if (ChatConfig.charCounter && !text.isEmpty()) {
            int max = ClientSide.server ? Math.min(ChatConfig.maxLength, ClientSide.serverMaxLength) : 256;
            String c = (text.length() > max ? "§c" : text.length() > max * 9 / 10 ? "§e" : "§8") + text.length() + (text.length() > 200 || !ClientSide.server ? "/" + max : "");
            fontRenderer.drawStringWithShadow(c, width - BTNS - 6 - fontRenderer.getStringWidth(c), height - 12, 0xFFFFFF);
        }
        int bx = width - BTNS;
        String[] m = modeLabel();
        if (btn(bx, height - 14, 20, 12, m[0], PrideFrame.BUTTON, ClientSide::cycleMode, mx, my)) hover = m[1];
        if (ChatConfig.formatButtons && btn(bx + 22, height - 14, 20, 12, "§lA", palette ? PrideFrame.TILE_ON : PrideFrame.BUTTON, () -> { palette = !palette; picker = false; }, mx, my)) hover = "Colours, bold, rainbow, emotes";
        if (btn(bx + 44, height - 14, 20, 12, "☺", picker ? PrideFrame.TILE_ON : PrideFrame.BUTTON, () -> { picker = !picker; palette = false; pickSearch.setFocused(picker); }, mx, my)) hover = "Emoji";
        if (btn(bx + 66, height - 14, 20, 12, "⌕", searching ? PrideFrame.TILE_ON : PrideFrame.BUTTON, this::toggleSearch, mx, my)) hover = "Search this tab (Ctrl+F)\n§7from:name  in:#channel  in:local\n§7since:30m  after:14:00  before:16:30  on:2026-10-05 / today / yesterday\n§7dim:nether  dim:-1  dim:here  near:100  at:120,-40,50";
        if (btn(bx + 110, height - 14, 20, 12, "☰", filters ? PrideFrame.TILE_ON : PrideFrame.BUTTON, () -> { filters = !filters; picker = false; palette = false; }, mx, my)) hover = "Show / hide kinds of lines in this tab";
        if (btn(bx + 88, height - 14, 20, 12, "⚙", PrideFrame.BUTTON, () -> mc.displayGuiScreen(new GuiChatSettings(this)), mx, my)) hover = "PrideChat settings";
        if (ChatConfig.enabled) { drawTabs(mx, my); drawGrips(mx, my); }
        int above = height - 16;
        above = drawReply(above, mx, my);
        above = drawNudge(above, mx, my);
        above = drawPreview(above, text);
        above = drawTyping(above);
        drawSuggest(above, text, mx, my);
        if (ChatConfig.jumpButton && ChatView.scroll > 0 && ChatView.boxW > 0)
            btn(ChatView.boxX + ChatView.boxW - 64, ChatView.boxY + ChatView.boxH - 14, 62, 12, "↓ newest", PrideFrame.TILE_ON, () -> ChatView.scroll = 0, mx, my);
        if (picker) drawPicker(mx, my);
        if (palette) drawPalette(mx, my);
        if (filters) drawFilters(mx, my);
        drawWindowBars(mx, my);
        if (menu != null) drawMenu(mx, my);
        if (hover != null) drawHoveringText(Arrays.asList(hover.split("\n")), mx, my);
    }

    /** a red wavy line under words the dictionary doesn't know */
    private void drawSpelling(String text) {
        List<int[]> bad = Spell.check(text);
        if (bad.isEmpty()) return;
        int off = 0;
        try { off = net.minecraftforge.fml.common.ObfuscationReflectionHelper.getPrivateValue(GuiTextField.class, inputField, "field_146225_q"); } catch (Throwable ignored) {}
        off = Math.max(0, Math.min(off, text.length()));
        String vis = fontRenderer.trimStringToWidth(text.substring(off), inputField.getWidth());
        for (int[] b : bad) {
            if (b[1] <= off || b[0] >= off + vis.length()) continue;
            int s = Math.max(b[0], off), e = Math.min(b[1], off + vis.length());
            int x1 = inputField.x + fontRenderer.getStringWidth(text.substring(off, s)), x2 = inputField.x + fontRenderer.getStringWidth(text.substring(off, e));
            for (int x = x1; x < x2; x++) { int wy = height - 3 + ((x - x1) / 2 % 2); Gui.drawRect(x, wy, x + 1, wy + 1, 0xFFFF5555); }
        }
    }

    private String[] modeLabel() {
        String rule = ChatView.activeRule();
        if (rule.startsWith("@dm:")) return new String[]{"§d✉", "Private message to " + rule.substring(4) + " (this tab)"};
        if (rule.startsWith("#")) return new String[]{"§b#", "Talking in " + rule + " (this tab)"};
        if (!ClientSide.server) return new String[]{"§7G", "Everyone hears you\n§7(this server has no PrideChat: no local/whisper/shout)"};
        String mode = ClientSide.mode;
        if ("@global".equals(rule)) mode = Router.GLOBAL;
        switch (mode) {
            case Router.WHISPER: return new String[]{"§7W", "Whisper — " + ChatConfig.whisperRange + " blocks\n§7click: change · prefix " + ChatConfig.whisperPrefix + " · Alt+Enter"};
            case Router.SHOUT: return new String[]{"§6S", "Shout — " + ChatConfig.shoutRange + " blocks\n§7click: change · prefix " + ChatConfig.shoutPrefix + " · Ctrl+Enter"};
            case Router.GLOBAL: return new String[]{"§bG", "Everyone on the server\n§7click: change · prefix " + ChatConfig.globalPrefix};
            case Router.CHANNEL: return new String[]{"§b#", "Channel #" + ClientSide.modeTarget};
            case Router.ZONE: return new String[]{"§2⌂", "Everyone in your zone (town / area)\n§7/local to go back"};
            case Router.DIM: return new String[]{"§3D", "Everyone in this dimension\n§7/local to go back"};
            default: return new String[]{"§aL", "Local — " + ChatConfig.localRange + " blocks\n§7click: change · " + ChatConfig.globalPrefix + "message = everyone"};
        }
    }

    private void toggleSearch() {
        searching = !searching;
        searchBox.setFocused(searching);
        if (!searching) { searchBox.setText(""); ChatView.search = ""; }
        ChatView.scroll = 0;
    }

    /** "↪ Replying to Name: …  ✖" above the input (threads, #32) */
    private int drawReply(int y, int mx, int my) {
        if (ClientSide.replyId == 0) return y;
        y -= 12;
        Gui.drawRect(2, y, width - 2, y + 11, 0xE0142A3A);
        Gui.drawRect(2, y, 4, y + 11, 0xFF5BCEFA);
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth("§b↪ Replying to " + ClientSide.replyName + ": §7" + ClientSide.replyText, width - 30), 7, y + 2, 0xFFFFFF);
        boolean over = mx >= width - 14 && mx < width - 2 && my >= y && my < y + 11;
        fontRenderer.drawStringWithShadow(over ? "§c✖" : "§7✖", width - 12, y + 2, 0xFFFFFF);
        click(width - 14, y, 12, 11, () -> ClientSide.replyId = 0);
        return y - 1;
    }

    /** the ☰ panel: tick which kinds of lines this tab shows (#42) */
    private void drawFilters(int mx, int my) {
        String rule = ChatView.thread != 0 ? "" : ChatView.activeRule();
        java.util.Set<String> hidden = ChatView.hiddenKinds(rule);
        int pw = 160, ph = ChatView.KINDS.length * 12 + 18, px = width - pw - 4, py = height - 18 - ph;
        Gui.drawRect(px, py, px + pw, py + ph, 0xF0140E22);
        Gui.drawRect(px, py, px + pw, py + 2, 0xFFF5A9B8);
        String tab = ChatView.active < ChatView.TABS.size() ? ChatView.TABS.get(ChatView.active).name : "";
        fontRenderer.drawStringWithShadow("§7Show in §f" + tab, px + 4, py + 5, 0xFFFFFF);
        for (int i = 0; i < ChatView.KINDS.length; i++) {
            String[] k = ChatView.KINDS[i];
            int ry = py + 16 + i * 12;
            boolean on = !hidden.contains(k[0]), over = mx >= px && mx < px + pw && my >= ry && my < ry + 12;
            if (over) Gui.drawRect(px + 1, ry, px + pw - 1, ry + 12, 0x606A3FA0);
            Gui.drawRect(px + 4, ry + 2, px + 12, ry + 10, 0xFF8A8499);
            Gui.drawRect(px + 5, ry + 3, px + 11, ry + 9, on ? 0xFF2FA055 : 0xFF140E22);
            if (on) fontRenderer.drawString("✔", px + 5, ry + 2, 0xFFFFFF);
            fontRenderer.drawStringWithShadow((on ? "§f" : "§8") + k[1], px + 16, ry + 2, 0xFFFFFF);
            click(px, ry, pw, 12, () -> ChatView.toggleKind(rule, k[0]));
        }
    }

    /** headers of the extra windows while chat is open: drag to move, ✖ to close (#41) */
    private void drawWindowBars(int mx, int my) {
        for (int[] w : ChatView.WINDOWS) {
            if (mx >= w[0] && mx < w[0] + w[2] && my >= w[1] && my < w[1] + 11)
                hover = mx >= w[0] + w[2] - 11 ? "Close this window" : "Drag to move\n§7right-click a tab → Pop out for more";
        }
    }

    private int drawNudge(int y, int mx, int my) {
        if (nudge == null) return y;
        y -= 12;
        Gui.drawRect(2, y, width - 2, y + 11, 0xE0402010);
        String s = kinder != null ? "§bKinder: §f" + kinder + " §7(click to use)" : "§eThis might sound harsh. §7Enter = send anyway · edit it to change";
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(s, width - 10), 5, y + 2, 0xFFFFFF);
        if (kinder != null) click(2, y, width - 4, 11, () -> { inputField.setText(kinder); kinder = null; nudge = null; });
        return y - 1;
    }

    /** what the message will look like (emoji, colours, new lines) */
    private int drawPreview(int y, String text) {
        if (!ChatConfig.inputPreview || text.isEmpty()) return y;
        if (!(text.contains(":") || text.contains("⏎") || text.contains("&") || text.contains("[item]"))) return y;
        String shown = ClientSide.serverColors ? text.replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1") : text;
        List<List<Object>> rows = new ArrayList<>();
        List<Object> cur = new ArrayList<>();
        int w = 0, max = width - 20;
        for (Emoji.Piece p : Emoji.split(shown)) {
            if (p.emoji != null) { if (w + 10 > max) { rows.add(cur); cur = new ArrayList<>(); w = 0; } cur.add(p.emoji); w += 10; continue; }
            String[] parts = p.text.split("⏎", -1);
            for (int i = 0; i < parts.length; i++) {
                if (i > 0) { rows.add(cur); cur = new ArrayList<>(); w = 0; }
                String t = parts[i];
                while (!t.isEmpty()) {
                    String fit = fontRenderer.trimStringToWidth(t, Math.max(10, max - w));
                    if (fit.isEmpty()) { rows.add(cur); cur = new ArrayList<>(); w = 0; continue; }
                    cur.add(fit); w += fontRenderer.getStringWidth(fit);
                    t = t.substring(fit.length());
                    if (!t.isEmpty()) { rows.add(cur); cur = new ArrayList<>(); w = 0; }
                }
            }
        }
        if (!cur.isEmpty()) rows.add(cur);
        int n = Math.min(4, rows.size());
        List<List<Object>> last = rows.subList(rows.size() - n, rows.size());
        int h = n * 10 + 4;
        y -= h;
        Gui.drawRect(2, y, width - 2, y + h, 0xD0140E22);
        Gui.drawRect(2, y, 4, y + h, 0xFF5BCEFA);
        int ty = y + 2;
        for (List<Object> r : last) {
            int x = 8;
            String fmt = "";
            for (Object o : r) {
                if (o instanceof String) { fontRenderer.drawStringWithShadow(fmt + o, x, ty + 1, 0xFFFFFF); fmt = FontRenderer0.fmt(fmt + o); x += fontRenderer.getStringWidth((String) o); }
                else { Emoji.draw((Emoji.E) o, x, ty, 9); x += 10; }
            }
            ty += 10;
        }
        if (rows.size() > n) fontRenderer.drawStringWithShadow("§8+" + (rows.size() - n) + " more lines", width - 80, y + 2, 0xFFFFFF);
        return y - 1;
    }

    private int drawTyping(int y) {
        if (ClientSide.TYPING.isEmpty() || !ChatConfig.showTyping) return y;
        List<String> who = new ArrayList<>(ClientSide.TYPING.keySet());
        String s = who.size() == 1 ? who.get(0) + " is typing" : who.size() <= 3 ? String.join(", ", who) + " are typing" : who.size() + " people are typing";
        int rate = 0;                                                     // the fastest typist sets the pace (0.4.1)
        for (String n : who) rate = Math.max(rate, Activity.rate(n));
        long now = System.currentTimeMillis();
        int period = Activity.period(rate) * 4 / 3;
        s += new String[]{".", "..", "..."}[(int) (now / period % 3)];
        boolean wacky = ChatConfig.typingWacky && rate >= 6;
        int col = !wacky ? 0xFF55FF : rate >= 18 ? Activity.PRIDE[(int) (now / period % Activity.PRIDE.length)] : 0xF5A9B8;
        int jx = wacky && rate >= 12 ? (int) Math.round(Math.sin(now / 40.0) * 1.5) : 0;
        y -= 10;
        fontRenderer.drawStringWithShadow(s, 4 + jx, y + 1, col);
        return y - 1;
    }

    /** :shortcode suggestions for the word being typed */
    private void drawSuggest(int y, String text, int mx, int my) {
        suggest = Collections.emptyList();
        if (!ChatConfig.emojiAutocomplete) return;
        int cur = inputField.getCursorPosition();
        int colon = text.lastIndexOf(':', Math.max(0, cur - 1));
        if (colon < 0 || cur - colon < 3) return;
        String q = text.substring(colon + 1, cur);
        if (!q.matches("[a-z0-9_+-]{2,30}")) return;
        if (colon > 0 && !Character.isWhitespace(text.charAt(colon - 1)) && text.charAt(colon - 1) != '⏎') return;
        List<Emoji.E> all = Emoji.search(q, -1);
        all.sort(Comparator.comparingInt(e -> e.code().startsWith(q) ? 0 : 1));
        suggest = all.subList(0, Math.min(8, all.size()));
        if (suggest.isEmpty()) return;
        int w = 0;
        for (Emoji.E e : suggest) w = Math.max(w, fontRenderer.getStringWidth(":" + e.code() + ":") + 18);
        int h = suggest.size() * 11 + 2, x = 4;
        y -= h;
        Gui.drawRect(x, y, x + w, y + h, 0xF0140E22);
        Gui.drawRect(x, y, x + w, y + 1, 0xFFF5A9B8);
        for (int i = 0; i < suggest.size(); i++) {
            Emoji.E e = suggest.get(i);
            int ry = y + 1 + i * 11;
            boolean over = mx >= x && mx < x + w && my >= ry && my < ry + 11;
            if (over || i == 0) Gui.drawRect(x, ry, x + w, ry + 11, over ? 0xFF6A3FA0 : 0x603A2A58);
            Emoji.draw(e, x + 2, ry + 1, 9);
            fontRenderer.drawStringWithShadow((i == 0 ? "§f" : "§7") + ":" + e.code() + ":", x + 14, ry + 2, 0xFFFFFF);
            final int k = i;
            click(x, ry, w, 11, () -> complete(k));
        }
        if (my >= y && my < y + h && mx < x + w) hover = "Tab = first one";
    }

    private void complete(int k) {
        if (k >= suggest.size()) return;
        String text = inputField.getText();
        int cur = inputField.getCursorPosition(), colon = text.lastIndexOf(':', Math.max(0, cur - 1));
        if (colon < 0) return;
        String code = ":" + Emoji.toned(suggest.get(k)).code() + ": ";
        inputField.setText(text.substring(0, colon) + code + text.substring(cur));
        inputField.setCursorPosition(colon + code.length());
        RECENT.remove(suggest.get(k).code());
        RECENT.addFirst(suggest.get(k).code());
    }

    // ------------------------------------------------------------------ tabs (+ the search box beside them)
    private void drawTabs(int mx, int my) {
        if (!ChatConfig.tabs && !searching && ChatView.thread == 0) return;
        int x = ChatView.boxX, y = ChatView.boxY - 14;
        if (ChatView.boxH == 0) y = height - 48 - ChatConfig.linesOpen * 9 - 16;
        if (ChatView.thread != 0) {                                            // looking at one conversation
            ChatView.Line root = ChatView.byId(ChatView.thread);
            String label = "§b🧵 Thread" + (root != null && root.sender != null ? " §7with " + root.sender : "") + "  §f✖ back";
            int w = fontRenderer.getStringWidth(label) + 10;
            boolean over = mx >= x && mx < x + w && my >= y && my < y + 12;
            Gui.drawRect(x, y, x + w, y + 12, over ? 0xF06A3FA0 : 0xF0243A5A);
            Gui.drawRect(x, y, x + w, y + 1, 0xFF5BCEFA);
            fontRenderer.drawStringWithShadow(label, x + 5, y + 2, 0xFFFFFF);
            click(x, y, w, 12, () -> { ChatView.thread = 0; ChatView.scroll = 0; });
            if (over) hover = "Back to the tab\n§7what you send here answers the thread";
            return;
        }
        if (ChatConfig.tabs)
            for (int i = 0; i < ChatView.TABS.size(); i++) {
                ChatView.Tab t = ChatView.TABS.get(i);
                boolean on = i == ChatView.active;
                String typing = t.rule.startsWith("@dm:") && ClientSide.TYPING.containsKey(t.rule.substring(4)) ? " §d…" : "";
                String dot = t.rule.startsWith("@dm:") ? (ClientSide.isOnline(t.rule.substring(4)) ? "§a● §r" : "§8● §r") : "";
                String label = dot + t.name + typing + (ChatConfig.unreadBadges && t.unread > 0 && !on ? " §c" + Math.min(99, t.unread) : "") + (t.muted() ? " §8🔇" : "");
                int w = fontRenderer.getStringWidth(label) + 10;
                boolean over = mx >= x && mx < x + w && my >= y && my < y + 12;
                Gui.drawRect(x, y, x + w, y + 12, on ? 0xF06A3FA0 : over ? 0xE02A2140 : 0xC01C1530);
                Gui.drawRect(x, y, x + w, y + 1, 0xFF000000 | t.color);
                fontRenderer.drawStringWithShadow(label, x + 5, y + 2, on ? 0xFFFFFF : 0xC8C0D8);
                final int k = i;
                click(x, y, w, 12, () -> ChatView.select(k));
                rclick(x, y, w, 12, () -> tabMenu(k, mx, my));
                if (over) hover = t.name + "\n§7shows: " + describe(t.rule) + "\n§8right-click: close / mute / pop-ups";
                x += w + 2;
            }
        if (searching) {
            searchBox.x = Math.max(x + 6, ChatView.boxX + ChatView.boxW - 160);
            searchBox.y = y;
            searchBox.drawTextBox();
            if (searchBox.getText().isEmpty() && !searchBox.isFocused()) fontRenderer.drawString("§8search… from: in: since:1h dim: near:", searchBox.x + 4, y + 2, 0xFFFFFF);
        }
    }

    private void tabMenu(int k, int x, int y) {
        ChatView.Tab t = ChatView.TABS.get(k);
        menu = new ArrayList<>();
        menuLine = null;
        menu.add(new String[]{"✔ Mark as read", "tab:read:" + k});
        menu.add(new String[]{"🗗 Pop out into its own window", "tab:popout:" + k});
        if (!t.session) {
            menu.add(new String[]{t.muted() ? "🔊 Unmute tab" : "🔇 Mute tab (no counts, no sound)", "tab:mute:" + k});
            menu.add(new String[]{t.flags.contains("n") ? "✖ No pop-ups" : "🔔 Pop-up for new lines", "tab:notify:" + k});
            menu.add(new String[]{"⚙ Edit tabs…", "tab:edit:" + k});
        } else menu.add(new String[]{"✖ Close tab", "tab:close:" + k});
        menu.add(new String[]{"✖ Cancel", "close:"});
        menuX = x; menuY = y + 10;
    }

    /** the move / resize handles at the top-right of the open chat */
    private void drawGrips(int mx, int my) {
        if (ChatView.boxW == 0) return;
        int gx = ChatView.boxX + ChatView.boxW + 2, gy = ChatView.boxY - 2;
        boolean o1 = mx >= gx && mx < gx + 10 && my >= gy && my < gy + 10, o2 = mx >= gx && mx < gx + 10 && my >= gy + 12 && my < gy + 22;
        Gui.drawRect(gx, gy, gx + 10, gy + 10, o1 || drag == 1 ? 0xFF6A3FA0 : 0xA02A2238);
        fontRenderer.drawString("✥", gx + 2, gy + 1, 0xFFFFFF);
        Gui.drawRect(gx, gy + 12, gx + 10, gy + 22, o2 || drag == 2 ? 0xFF6A3FA0 : 0xA02A2238);
        fontRenderer.drawString("⤡", gx + 2, gy + 13, 0xFFFFFF);
        if (o1) hover = "Drag to move the chat\n§7(settings → Look → position to reset)";
        if (o2) hover = "Drag to make the chat wider / taller";
    }

    static String describe(String rule) {
        if (rule.startsWith("@dm:")) return "private messages with " + rule.substring(4);
        if (rule.startsWith("#")) return "the " + rule + " channel";
        if (rule.startsWith("@radio:")) return "radio frequency " + rule.substring(7);
        switch (rule) {
            case "@all": return "everything";
            case "@player": return "players talking";
            case "@system": return "server and mod messages";
            case "@mention": return "lines that mention you";
            case "@dm": return "all private messages";
            case "@radio": return "radios, walkie-talkies, intercoms and phones";
            case "@zone": return "zone chat (/zone)";
            case "@dim": return "dimension chat (/dimchat)";
            case "@local": return "people near you (whisper, local, shout)";
            case "@global": return "everyone-chat";
            default: return "lines matching " + rule;
        }
    }

    // ------------------------------------------------------------------ format palette (colours, styles, rainbow, emotes)
    private static final String CODES = "0123456789abcdef";
    private static final int[] COLS = {0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF};

    private void drawPalette(int mx, int my) {
        int pw = 8 * 14 + 8, ph = 2 * 14 + 16 + 14 + 16 + 15 + 15 + 14 + 8, px = width - pw - 4, py = height - 18 - ph;
        Gui.drawRect(px, py, px + pw, py + ph, 0xF0140E22);
        Gui.drawRect(px, py, px + pw, py + 2, 0xFFF5A9B8);
        for (int i = 0; i < 16; i++) {
            int cx = px + 4 + (i % 8) * 14, cy = py + 5 + (i / 8) * 14;
            Gui.drawRect(cx, cy, cx + 12, cy + 12, 0xFF000000 | COLS[i]);
            final char c = CODES.charAt(i);
            click(cx, cy, 12, 12, () -> inputField.writeText("&" + c));
            if (mx >= cx && mx < cx + 12 && my >= cy && my < cy + 12) hover = "&" + c;
        }
        String[][] styles = {{"§lB", "&l", "bold"}, {"§oI", "&o", "italic"}, {"§nU", "&n", "underline"}, {"§mS", "&m", "strike"}, {"§kO", "&k", "magic"}, {"R", "&r", "plain again"}};
        int sy = py + 5 + 28 + 2;
        for (int i = 0; i < styles.length; i++) {
            String[] s = styles[i];
            if (btn(px + 4 + i * 19, sy, 17, 12, s[0], PrideFrame.BUTTON, () -> inputField.writeText(s[1]), mx, my)) hover = s[2] + " (" + s[1] + ")";
        }
        sy += 15;
        if (btn(px + 4, sy, pw - 8, 12, rainbow("Rainbow text"), PrideFrame.BUTTON, () -> inputField.setText(rainbowCodes(inputField.getText())), mx, my))
            hover = "Turns your whole message into rainbow colours";
        sy += 15;
        String[][] grads = {{"bdfdb", "Trans"}, {"e6c5", "Sunset"}, {"b391", "Ocean"}, {"ee6c4", "Fire"}, {"d5913", "Galaxy"}};
        for (int i = 0; i < grads.length; i++) {
            String[] g = grads[i];
            StringBuilder lab = new StringBuilder();
            for (char c : g[0].toCharArray()) lab.append('§').append(c).append('▌');
            if (btn(px + 4 + i * 23, sy, 21, 12, lab.toString(), PrideFrame.BUTTON, () -> inputField.setText(gradientCodes(inputField.getText(), g[0])), mx, my))
                hover = g[1] + " gradient";
        }
        sy += 15;
        String[][] effects = {{"§d✧glow", "[glow]", "Soft glow around your message"}, {"§7▓shad", "[shadow]", "A long drop shadow"},
                {rainbow("anim"), "[color]", "Colours that move"}, {"§f⌨type", "[type]", "Letters appear one by one"}};
        for (int i = 0; i < effects.length; i++) {                        // text effects (roadmap #47), need permission pridechat.effects
            String[] fx = effects[i];
            if (btn(px + 4 + i * 29, sy, 27, 12, fx[0], PrideFrame.BUTTON, () -> {
                String t = inputField.getText();
                inputField.setText(t.startsWith(fx[1]) ? t.substring(fx[1].length()) : fx[1] + t);
            }, mx, my)) hover = fx[2] + " (" + fx[1] + " in front; combine them)\n§7the server decides who may use effects";
        }
        sy += 15;
        String[][] emotes = {{"👋", "waves"}, {"🤗", "hugs everyone"}, {"💃", "dances"}, {"😂", "laughs"}, {"🙇", "bows"}, {"✋", "high-fives"}};
        for (int i = 0; i < emotes.length; i++) {
            String[] e = emotes[i];
            Emoji.Piece pc = Emoji.split(e[0]).get(0);
            int ex = px + 4 + i * 19;
            boolean over = btn(ex, sy, 17, 12, "", PrideFrame.BUTTON, () -> { send("/" + (ClientSide.server ? "emote" : "me") + " " + e[1], false, null); mc.displayGuiScreen(null); }, mx, my);
            if (pc.emoji != null) Emoji.draw(pc.emoji, ex + 4, sy + 2, 9);
            if (over) hover = "/me " + e[1] + (ClientSide.server ? " §7(people near you)" : "");
        }
        fontRenderer.drawString(ClientSide.serverColors ? "§8colours on ✔" : "§8server must allow &codes", px + 4, py + ph - 9, 0xFFFFFF);
    }

    static String rainbow(String s) {
        String r = "c6eab9d";
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (char c : s.toCharArray()) { if (c != ' ') b.append('§').append(r.charAt(i++ % r.length())); b.append(c); }
        return b.toString();
    }

    /** colours spread evenly over the letters, start to end (the font only has 16 colours, so it steps through them) */
    static String gradientCodes(String s, String codes) {
        String plain = s.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        int letters = 0;
        for (char c : plain.toCharArray()) if (c != ' ' && c != '⏎') letters++;
        StringBuilder b = new StringBuilder();
        int i = 0;
        char last = 0;
        for (int k = 0; k < plain.length(); k++) {
            char c = plain.charAt(k);
            if (c == ':') {                                            // keep :shortcodes: whole
                int end = plain.indexOf(':', k + 1);
                if (end > k + 1 && Emoji.byCode(plain.substring(k + 1, end)) != null) { b.append(plain, k, end + 1); k = end; i++; continue; }
            }
            if (c != ' ' && c != '⏎') {
                char code = codes.charAt(Math.min(codes.length() - 1, i++ * codes.length() / Math.max(1, letters)));
                if (code != last) { b.append('&').append(code); last = code; }
            }
            b.append(c);
        }
        return b.toString();
    }

    static String rainbowCodes(String s) {
        String plain = s.replaceAll("&[0-9a-fk-orA-FK-OR]", "");
        String r = "c6eab9d";
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (int k = 0; k < plain.length(); k++) {
            char c = plain.charAt(k);
            if (c == ':' ) {                                            // keep :shortcodes: whole
                int end = plain.indexOf(':', k + 1);
                if (end > k + 1 && Emoji.byCode(plain.substring(k + 1, end)) != null) { b.append(plain, k, end + 1); k = end; continue; }
            }
            if (c != ' ' && c != '⏎') b.append('&').append(r.charAt(i++ % r.length()));
            b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ emoji picker
    private static final int CELL = 16;
    private static final int[] TONE_COLORS = {0xFFFFCC4D, 0xFFF7DECE, 0xFFF3D2A2, 0xFFD5AB88, 0xFFAF7E57, 0xFF7C533E};

    private void drawPicker(int mx, int my) {
        int cols = 12, rows = 8, pw = cols * CELL + 8, ph = rows * CELL + 44;
        int px = width - pw - 4, py = height - 18 - ph;
        Gui.drawRect(px, py, px + pw, py + ph, 0xF0140E22);
        int sw = pw / PrideFrame.RAINBOW.length;
        for (int i = 0; i < PrideFrame.RAINBOW.length; i++) Gui.drawRect(px + i * sw, py, i == PrideFrame.RAINBOW.length - 1 ? px + pw : px + (i + 1) * sw, py + 2, PrideFrame.RAINBOW[i]);
        int[] groups = {-2, 0, 1, 3, 4, 6, 5, 7, 8, 9};
        String[] names = {"Recent", "Smileys", "People", "Animals & nature", "Food & drink", "Activities", "Travel & places", "Objects", "Symbols", "Flags"};
        int gx = px + 4;
        for (int i = 0; i < groups.length; i++) {
            final int g = groups[i];
            boolean on = pickGroup == g;
            Gui.drawRect(gx, py + 4, gx + 17, py + 21, on ? 0xFF6A3FA0 : 0xFF2A2238);
            Emoji.E icon = g == -2 ? Emoji.byCode("clock3") : firstOf(g);
            if (g == -2 && icon == null) icon = Emoji.byCode("star");
            if (icon != null) Emoji.draw(icon, gx + 1, py + 5, 15);
            click(gx, py + 4, 17, 17, () -> { pickGroup = g; pickScroll = 0; });
            if (mx >= gx && mx < gx + 17 && my >= py + 4 && my < py + 21) hover = names[i];
            gx += 19;
        }
        pickSearch.x = px + 4; pickSearch.y = py + 24; pickSearch.width = pw - 8 - 6 * 11;
        for (int t = 0; t <= 5; t++) {                                         // skin tone: default + 5
            int sx = px + pw - 4 - (6 - t) * 11;
            boolean on = ChatConfig.skinTone == t, over = mx >= sx && mx < sx + 10 && my >= py + 24 && my < py + 36;
            Gui.drawRect(sx, py + 24, sx + 10, py + 36, on ? 0xFFFFFFFF : over ? 0xFF8A8499 : 0xFF2A2238);
            Gui.drawRect(sx + 1, py + 25, sx + 9, py + 35, TONE_COLORS[t]);
            final int tone = t;
            click(sx, py + 24, 10, 12, () -> { ChatConfig.skinTone = tone; ClientSide.saved(); });
            if (over) hover = t == 0 ? "Skin tone: default" : "Skin tone " + t + " (hands + people)";
        }
        pickSearch.drawTextBox();
        if (pickSearch.getText().isEmpty() && !pickSearch.isFocused()) fontRenderer.drawString("§8search emoji… (heart, cat, pride)", px + 8, py + 26, 0xFFFFFF);
        List<Emoji.E> list;
        if (!pickSearch.getText().trim().isEmpty()) list = Emoji.search(pickSearch.getText(), -1);
        else if (pickGroup == -2) { list = new ArrayList<>(); for (String c : RECENT) { Emoji.E e = Emoji.byCode(c); if (e != null) list.add(e); } if (list.isEmpty()) list = Emoji.search("", 0); }
        else list = Emoji.search("", pickGroup);
        int maxScroll = Math.max(0, (list.size() + cols - 1) / cols - rows);
        pickScroll = Math.max(0, Math.min(pickScroll, maxScroll));
        int gy = py + 40;
        for (int i = pickScroll * cols; i < list.size() && i < (pickScroll + rows) * cols; i++) {
            Emoji.E e = Emoji.toned(list.get(i));
            int cx = px + 4 + (i % cols) * CELL, cy = gy + (i / cols - pickScroll) * CELL;
            boolean over = mx >= cx && mx < cx + CELL && my >= cy && my < cy + CELL;
            if (over) { Gui.drawRect(cx, cy, cx + CELL, cy + CELL, 0x60F5A9B8); hover = e.label + "\n§7:" + e.code() + ":"; }
            Emoji.draw(e, cx + 1, cy + 1, CELL - 2);
            click(cx, cy, CELL, CELL, () -> insert(e));
        }
        PrideFrame.scrollbar(px + pw - 4, gy, rows * CELL, pickScroll, rows, Math.max(rows, (list.size() + cols - 1) / cols));
    }

    private static Emoji.E firstOf(int g) { List<Emoji.E> l = Emoji.search("", g); return l.isEmpty() ? null : l.get(0); }

    private void insert(Emoji.E e) {
        String code = ":" + e.code() + ":";
        String t = inputField.getText();
        inputField.setFocused(true);
        inputField.writeText((t.isEmpty() || t.endsWith(" ") ? "" : " ") + code + " ");
        RECENT.remove(e.code());
        RECENT.addFirst(e.code());
        while (RECENT.size() > 36) RECENT.removeLast();
    }

    // ------------------------------------------------------------------ right-click menu on a message
    private void openMenu(ChatView.Line l, int x, int y) {
        menu = new ArrayList<>();
        menuLine = l;
        if (l.msgId != 0 && ClientSide.server) {
            menu.add(new String[]{"", "reactrow:"});
            menu.add(new String[]{"↪ Reply in a thread", "threadreply:"});
            if (l.replies > 0 || l.re != 0) menu.add(new String[]{"🧵 Open the thread" + (l.replies > 0 ? " (" + l.replies + ")" : ""), "threadopen:"});
        }
        String me = mc.player == null ? "" : mc.player.getName();
        String who = l.dm != null ? l.dm : l.sender;
        if (who != null && !who.equalsIgnoreCase(me) && !l.bot && !l.npc) playerItems(who);
        Matcher c = COORDS.matcher(l.plain);
        if (c.find()) menu.add(new String[]{"⌖ Copy coordinates " + c.group(1) + " " + c.group(2) + " " + c.group(3), "copy:" + c.group(1) + " " + c.group(2) + " " + c.group(3)});
        if (ClientSide.serverAI) menu.add(new String[]{"🌐 Translate to " + ChatConfig.translateTo, "translate:"});
        menu.add(new String[]{"⧉ Copy message", "copy:" + l.plain});
        menu.add(new String[]{"✖ Close", "close:"});
        menuX = x; menuY = y;
    }

    /** what you can do with a player (the line menu, and right-click on their bubble / head, #43) */
    private void playerItems(String n) {
        menu.add(new String[]{"↩ Reply privately to " + n, "dm:" + n});
        menu.add(new String[]{"@ Mention " + n, "insert:@" + n + " "});
        menu.add(new String[]{"👋 Wave at " + n, "emote:waves at " + n});
        menu.add(new String[]{"🤗 Hug " + n, "emote:hugs " + n});
        menu.add(new String[]{"$ Pay " + n + "…", "suggest:/pay " + n + " "});
        menu.add(new String[]{"➤ Ask to teleport to " + n, "run:/tpa " + n});
        menu.add(new String[]{ChatView.friend(n) ? "💔 Remove friend" : "♥ Add friend", "friend:" + n});
        menu.add(new String[]{ChatView.muted(n) ? "🔔 Unmute " + n : "🔕 Mute " + n + " (quiet + grey, no bubbles)", "mute:" + n});
        menu.add(new String[]{ChatView.ignored(n) ? "☺ Stop ignoring " + n : "✖ Ignore / block " + n + " (hide everything)", "ignore:" + n});
        // per-player visibility, each on its own (#44)
        menu.add(new String[]{Bubbles.hidden(n) ? "💬 Show their bubbles" : "💬 Hide their bubbles", "bubbles:" + n});
        menu.add(new String[]{ChatView.hiddenChat(n) ? "☰ Show their chat lines" : "☰ Hide their chat lines (bubbles stay)", "hide:chat:" + n});
        menu.add(new String[]{ChatView.hiddenEmotes(n) ? "🎭 Show their emotes" : "🎭 Hide their emotes", "hide:emotes:" + n});
        menu.add(new String[]{ChatView.hiddenTyping(n) ? "… Show their typing dots" : "… Hide their typing dots", "hide:typing:" + n});
        menu.add(new String[]{"⚑ Report", "report:" + n});
    }

    /** right-click on someone's speech bubble or floating head: their newest line's menu, or just the player actions */
    private void openPlayerMenu(String name, int x, int y) {
        for (int i = ChatView.LINES.size() - 1; i >= 0; i--) {
            ChatView.Line l = ChatView.LINES.get(i);
            if (name.equalsIgnoreCase(l.sender) && l.dm == null) { openMenu(l, x, y); menu.add(0, new String[]{"§d" + name + " §8— their last message", "close:"}); return; }
        }
        menu = new ArrayList<>();
        menuLine = null;
        menu.add(new String[]{"§d" + name, "close:"});
        if (mc.player == null || !name.equalsIgnoreCase(mc.player.getName())) playerItems(name);
        menu.add(new String[]{"✖ Close", "close:"});
        menuX = x; menuY = y;
    }

    private void drawMenu(int mx, int my) {
        int w = 7 * 14 + 16;
        for (String[] m : menu) w = Math.max(w, fontRenderer.getStringWidth(m[0]) + 16);
        int h = menu.size() * 14 + 4, x = Math.min(menuX, width - w - 2), y = Math.max(2, Math.min(menuY, height - h - 16));
        Gui.drawRect(x, y, x + w, y + h, 0xF0140E22);
        Gui.drawRect(x, y, x + w, y + 1, 0xFFF5A9B8);
        for (int i = 0; i < menu.size(); i++) {
            String[] m = menu.get(i);
            int ry = y + 2 + i * 14;
            if (m[1].equals("reactrow:")) {                                   // ❤ 😂 👍 👎 😡 😮 ❓
                for (int k = 0; k < REACTIONS.length; k++) {
                    int ex = x + 6 + k * 14;
                    boolean over = mx >= ex && mx < ex + 13 && my >= ry && my < ry + 13;
                    if (over) Gui.drawRect(ex - 1, ry, ex + 13, ry + 13, 0xFF6A3FA0);
                    Emoji.E e = Emoji.byCode(REACTIONS[k]);
                    if (e != null) Emoji.draw(e, ex + 1, ry + 2, 10);
                    final String code = REACTIONS[k];
                    click(ex - 1, ry, 14, 13, () -> act("react:" + code));
                }
                continue;
            }
            boolean over = mx >= x && mx < x + w && my >= ry && my < ry + 14;
            if (over) Gui.drawRect(x + 1, ry, x + w - 1, ry + 14, 0xFF6A3FA0);
            fontRenderer.drawStringWithShadow(m[0], x + 8, ry + 3, 0xFFFFFF);
            click(x, ry, w, 14, () -> act(m[1]));
        }
    }

    private void act(String a) {
        menu = null;
        int c = a.indexOf(':');
        String kind = a.substring(0, c), v = a.substring(c + 1);
        switch (kind) {
            case "suggest": inputField.setText(v); inputField.setFocused(true); break;
            case "insert": inputField.writeText(v); inputField.setFocused(true); break;
            case "run": send(v, false, null); break;
            case "emote": send("/" + (ClientSide.server ? "emote" : "me") + " " + v, false, null); break;
            case "copy": GuiScreen.setClipboardString(v); break;
            case "threadreply":
                if (menuLine == null) break;
                ClientSide.replyId = menuLine.msgId;
                ClientSide.replyName = menuLine.sender == null ? "?" : menuLine.sender;
                ClientSide.replyText = ChatView.body(menuLine);
                inputField.setFocused(true);
                break;
            case "threadopen": if (menuLine != null) { ChatView.thread = menuLine.thread; ChatView.scroll = 0; } break;
            case "dm":
                if (ChatConfig.dmTabs) ChatView.openDm(v);
                else inputField.setText("/" + ChatConfig.msgCommand + " " + v + " ");
                inputField.setFocused(true);
                break;
            case "react": {
                if (menuLine == null) break;
                NBTTagCompound t = new NBTTagCompound();
                t.setLong("id", menuLine.msgId);
                Net.toServer(Net.C_REACT, t, v);
                break;
            }
            case "translate": {
                if (menuLine == null) break;
                NBTTagCompound t = new NBTTagCompound();
                t.setString("lang", ChatConfig.translateTo);
                ClientSide.ai("translate", t, ChatView.body(menuLine));
                break;
            }
            case "report": {
                String body = menuLine == null ? "" : ChatView.body(menuLine);
                if (ChatConfig.reportCommand.isEmpty()) { GuiScreen.setClipboardString(v + ": " + body); ClientSide.info("§7Copied the message — paste it to staff. (Set a report command in settings → Filters.)"); }
                else send(ChatConfig.reportCommand.replace("{name}", v).replace("{msg}", body), false, null);
                break;
            }
            case "friend": toggleList(true, v); break;
            case "bubbles": {
                List<String> list = new ArrayList<>(Arrays.asList(ChatConfig.hiddenBubbles));
                if (!list.removeIf(s -> s.equalsIgnoreCase(v))) list.add(v);
                ChatConfig.hiddenBubbles = list.toArray(new String[0]);
                ClientSide.saved();
                break;
            }
            case "ignore": toggleList(false, v); break;
            case "hide": {                                                // hide:chat|emotes|typing:Name (#44)
                String what = v.substring(0, v.indexOf(':')), n = v.substring(v.indexOf(':') + 1);
                String[] cur = "chat".equals(what) ? ChatConfig.hiddenChat : "emotes".equals(what) ? ChatConfig.hiddenEmotes : ChatConfig.hiddenTyping;
                List<String> list = new ArrayList<>(Arrays.asList(cur));
                if (!list.removeIf(s -> s.equalsIgnoreCase(n))) list.add(n);
                String[] out = list.toArray(new String[0]);
                if ("chat".equals(what)) ChatConfig.hiddenChat = out; else if ("emotes".equals(what)) ChatConfig.hiddenEmotes = out; else ChatConfig.hiddenTyping = out;
                if ("typing".equals(what)) ClientSide.TYPING.remove(n);
                ClientSide.saved();
                break;
            }
            case "mute": {
                List<String> list = new ArrayList<>(Arrays.asList(ChatConfig.muted));
                if (!list.removeIf(s -> s.equalsIgnoreCase(v))) list.add(v);
                ChatConfig.muted = list.toArray(new String[0]);
                ClientSide.saved();
                break;
            }
            case "tab": {
                String[] p = v.split(":");
                int k = Integer.parseInt(p[1]);
                if (k >= ChatView.TABS.size()) break;
                switch (p[0]) {
                    case "read": ChatView.TABS.get(k).unread = 0; break;
                    case "close": ChatView.closeTab(k); break;
                    case "popout": ChatView.popOut(ChatView.TABS.get(k).rule); break;
                    case "mute": flag(k, 'm'); break;
                    case "notify": flag(k, 'n'); break;
                    case "edit": mc.displayGuiScreen(new GuiChatSettings(this, "Tabs")); break;
                    default: break;
                }
                break;
            }
            default: break;
        }
    }

    private static void toggleList(boolean friends, String v) {
        List<String> list = new ArrayList<>(Arrays.asList(friends ? ChatConfig.friends : ChatConfig.ignored));
        if (!list.removeIf(s -> s.equalsIgnoreCase(v))) list.add(v);
        if (friends) ChatConfig.friends = list.toArray(new String[0]); else ChatConfig.ignored = list.toArray(new String[0]);
        ClientSide.saved();
    }

    /** flip a letter in a config tab's flags (m = mute, n = pop-ups) */
    private static void flag(int k, char f) {
        String rule = ChatView.TABS.get(k).rule;
        for (int i = 0; i < ChatConfig.tabList.length; i++) {
            String[] p = ChatConfig.tabList[i].split("\\s*\\|\\s*", -1);
            if (p.length < 3 || !p[2].trim().equals(rule)) continue;
            String sound = p.length > 3 ? p[3].trim() : "none", flags = p.length > 4 ? p[4].trim() : "";
            flags = flags.indexOf(f) >= 0 ? flags.replace(String.valueOf(f), "") : flags + f;
            ChatConfig.tabList[i] = p[0].trim() + " | " + p[1].trim() + " | " + p[2].trim() + " | " + sound + " | " + flags;
        }
        ClientSide.saved();
    }

    // ------------------------------------------------------------------ input
    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        if (button == 0) {
            for (int i = boxes.size() - 1; i >= 0; i--) {
                int[] b = boxes.get(i);
                if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) { clicks.get(i).run(); return; }
            }
            if (menu != null) { menu = null; return; }
            for (int[] w : ChatView.WINDOWS) {
                if (mx < w[0] || mx >= w[0] + w[2] || my < w[1] || my >= w[1] + 11) continue;
                if (mx >= w[0] + w[2] - 11) { ChatView.closeWindow(w[4]); return; }
                drag = 3; dragWin = w[4]; dragX = mx; dragY = my; startA = w[0]; startB = w[1];
                return;
            }
            int gx = ChatView.boxX + ChatView.boxW + 2, gy = ChatView.boxY - 2;
            if (ChatView.boxW > 0 && mx >= gx && mx < gx + 10 && my >= gy && my < gy + 22) {          // start dragging
                drag = my < gy + 11 ? 1 : 2;
                dragX = mx; dragY = my;
                startA = drag == 1 ? ChatConfig.offsetX : ChatConfig.width;
                startB = drag == 1 ? ChatConfig.offsetY : ChatConfig.linesOpen;
                return;
            }
            if (picker) { pickSearch.mouseClicked(mx, my, button); }
            if (searching) { searchBox.mouseClicked(mx, my, button); if (searchBox.isFocused()) return; }
        }
        if (button == 1) {
            for (int i = rboxes.size() - 1; i >= 0; i--) {
                int[] b = rboxes.get(i);
                if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3]) { rclicks.get(i).run(); return; }
            }
            if (ChatConfig.enabled) {                                        // right-click a message
                ChatView.Line l = ChatView.lineAt(mx, my);
                if (l != null) { openMenu(l, mx, my); return; }
                String who = Bubbles.at(mx, my);                           // a bubble / floating head out in the world (#43)
                if (who != null) { openPlayerMenu(who, mx, my); return; }
                menu = null;
            }
        }
        if (button == 2 && ChatConfig.middleClickCopy) {
            ChatView.Line l = ChatView.lineAt(mx, my);
            if (l != null) { GuiScreen.setClipboardString(l.plain); ClientSide.info("§7Copied."); return; }
        }
        super.mouseClicked(mx, my, button);
    }

    @Override
    protected void mouseClickMove(int mx, int my, int button, long time) {
        if (drag == 0) { super.mouseClickMove(mx, my, button, time); return; }
        float sc = ChatConfig.scale / 100f;
        if (drag == 3) { ChatView.moveWindow(dragWin, startA + mx - dragX, startB + my - dragY); return; }
        if (drag == 1) {
            ChatConfig.offsetX = startA + (mx - dragX);
            ChatConfig.offsetY = startB - (my - dragY);
        } else {
            ChatConfig.width = Math.max(160, Math.min(900, startA + (int) ((mx - dragX) / sc)));
            ChatConfig.linesOpen = Math.max(5, Math.min(80, startB + (int) ((dragY - my) / (ChatView.rowH() * sc))));
            ChatView.relayout();
        }
    }

    @Override
    protected void mouseReleased(int mx, int my, int state) {
        if (drag != 0) { drag = 0; ClientSide.saved(); return; }
        super.mouseReleased(mx, my, state);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE && (menu != null || picker || palette || filters || nudge != null)) { menu = null; picker = false; palette = false; filters = false; nudge = null; kinder = null; return; }
        if (key == Keyboard.KEY_ESCAPE && ChatView.thread != 0) { ChatView.thread = 0; ChatView.scroll = 0; return; }
        if (key == Keyboard.KEY_F && GuiScreen.isCtrlKeyDown()) { toggleSearch(); return; }
        if (picker && pickSearch.isFocused() && key != Keyboard.KEY_RETURN && key != Keyboard.KEY_NUMPADENTER) {
            pickSearch.textboxKeyTyped(c, key);
            pickScroll = 0;
            return;
        }
        if (searching && searchBox.isFocused() && key != Keyboard.KEY_ESCAPE) {
            searchBox.textboxKeyTyped(c, key);
            ChatView.search = searchBox.getText();
            ChatView.scroll = 0;
            if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) searchBox.setFocused(false);
            return;
        }
        if (key == Keyboard.KEY_TAB && !suggest.isEmpty()) { complete(0); return; }
        if (key == Keyboard.KEY_RETURN || key == Keyboard.KEY_NUMPADENTER) {
            if (GuiScreen.isShiftKeyDown() && ChatConfig.multiLine) { inputField.writeText("⏎"); return; }
            if (nudge != null && inputField.getText().trim().equals(nudge)) { nudge = null; sent = true; send(inputField.getText().trim(), true, null); mc.displayGuiScreen(null); return; }
            nudge = null;
            enter(GuiScreen.isCtrlKeyDown() ? Router.SHOUT : GuiScreen.isAltKeyDown() ? Router.WHISPER : null);
            return;
        }
        if (GuiScreen.isKeyComboCtrlV(key)) {                             // paste keeps new lines as ⏎
            String clip = GuiScreen.getClipboardString().replace("\r\n", "\n").replace('\r', '\n').replace("\n", ChatConfig.multiLine ? "⏎" : " ");
            inputField.writeText(clip);
        } else {
            super.keyTyped(c, key);
            if (net.minecraft.util.ChatAllowedCharacters.isAllowedCharacter(c)) Activity.keystroke();
        }
        ClientSide.typing(!inputField.getText().isEmpty() && !inputField.getText().startsWith("/"));
    }

    @Override
    public void handleMouseInput() throws IOException {
        int d = Mouse.getEventDWheel();
        if (d != 0 && picker) {
            int mx = Mouse.getEventX() * width / mc.displayWidth;
            if (mx > width - 12 * CELL - 16) { pickScroll = Math.max(0, pickScroll - Integer.signum(d)); return; }
        }
        super.handleMouseInput();
    }

    @Override
    public void updateScreen() {
        super.updateScreen();
        pickSearch.updateCursorCounter();
        searchBox.updateCursorCounter();
    }

    /** strip colour codes (for copying) */
    static String plain(String s) { return TextFormatting.getTextWithoutFormattingCodes(s); }

    /** carry § formatting from one drawn piece to the next */
    static final class FontRenderer0 { static String fmt(String s) { return net.minecraft.client.gui.FontRenderer.getFormatFromString(s); } }
}
