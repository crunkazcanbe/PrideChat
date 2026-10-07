package com.dogpound.pridechat;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.common.config.Config;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.function.IntConsumer;

/**
 * PrideChat settings — one Pride panel with a page list on the left and a search box that finds any option.
 * Options are read straight from ChatConfig fields (ranges from @RangeInt), so a new option is ONE line on a page:
 *   bool(y, null, "fieldName", "Label", "what it does")    num(…)   cycle(…, "a", "b")   text(…)   list(…)   color(…)
 * Pages are just the PAGES array + a case in page(); saves as you click.
 */
public class GuiChatSettings extends GuiScreen {
    private final GuiScreen back;
    private PrideFrame f;
    private int scroll, contentH, top, bottom, px, pw, side;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> clicks = new ArrayList<>();
    private String hover;
    private int mx, my;
    private GuiTextField find;
    private String[] endpoint = {"", ""};
    private static String page = "General";
    static final String[] PAGES = {"General", "Range", "Look", "Input", "Emoji", "Alerts", "Sounds", "Tabs", "People", "Bubbles", "My look", "Filters", "Tools", "Server", "AI"};
    private static final String[] ICONS = {"⚙", "📍", "🎨", "⌨", "😀", "🔔", "🎵", "🗂", "👥", "💬", "✨", "🛡", "🧰", "🖧", "🤖"};
    private static final int[] PALETTE = {0xF5A9B8, 0x5BCEFA, 0xFFD700, 0xB070FF, 0xFF8C00, 0xE40303, 0x7CFC9A, 0x008026, 0x24408E, 0x8A8499, 0xFFFFFF, 0x140E22};

    GuiChatSettings(GuiScreen back) { this.back = back; }
    GuiChatSettings(GuiScreen back, String startPage) { this.back = back; page = startPage; }

    @Override
    public void initGui() {
        Keyboard.enableRepeatEvents(true);
        f = PrideFrame.fit(width, height);
        side = 92;
        px = f.cx + side + 8; pw = f.cw - side - 8;
        top = f.cy + 18; bottom = f.cy + f.ch;
        find = new GuiTextField(1, fontRenderer, f.cx, f.cy, side, 13);
        endpoint = AI.endpoint();
        find.setMaxStringLength(40);
    }

    @Override public void onGuiClosed() { Keyboard.enableRepeatEvents(false); }
    @Override public boolean doesGuiPauseGame() { return false; }

    private void click(int x, int y, int w, int h, Runnable r) { boxes.add(new int[]{x, y, w, h}); clicks.add(r); }
    private boolean vis(int y, int h) { return y + h > top && y < bottom; }
    private String q() { return find.getText().trim().toLowerCase(Locale.ROOT); }
    /** while searching, only options whose label matches are shown (from every page) */
    private boolean shown(String label) { return q().isEmpty() || label.toLowerCase(Locale.ROOT).contains(q()); }

    // ------------------------------------------------------------------ reflection on ChatConfig
    private static Field fld(Object owner, String name) {
        try { return (owner == null ? ChatConfig.class : owner.getClass()).getField(name); } catch (Exception e) { throw new RuntimeException("no option " + name); }
    }
    private static Object get(Object owner, String name) { try { return fld(owner, name).get(owner); } catch (Exception e) { return null; } }
    private static void set(Object owner, String name, Object v) { try { fld(owner, name).set(owner, v); } catch (Exception ignored) {} ClientSide.saved(); }

    // ------------------------------------------------------------------ widgets
    private void btn(int x, int y, int w, int h, String label, Runnable r) {
        if (!vis(y, h)) return;
        PrideFrame.button(x, y, w, h, label, PrideFrame.BUTTON, mx, my);
        click(x, y, w, h, () -> { r.run(); ClientSide.saved(); });
    }

    private int heading(int y, String t) {
        if (!q().isEmpty()) return y;
        if (vis(y, 14)) { fontRenderer.drawStringWithShadow(t, px, y + 2, 0xFFFFFF); Gui.drawRect(px, y + 12, px + pw - 8, y + 13, 0x30FFFFFF); }
        return y + 17;
    }

    private int note(int y, String t) {
        if (!q().isEmpty()) return y;
        for (String line : fontRenderer.listFormattedStringToWidth(t, pw - 10)) {
            if (vis(y, 10)) fontRenderer.drawStringWithShadow("§8" + line, px, y + 1, 0xFFFFFF);
            y += 10;
        }
        return y + 2;
    }

    private int label(int y, String label, String desc, int room) {
        fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, pw - room), px, y + 3, 0xFFFFFF);
        if (desc != null && !desc.isEmpty() && mx >= px && mx < px + pw - room && my >= y && my < y + 14) hover = desc;
        return y;
    }

    int bool(int y, Object o, String field, String label, String desc) {
        if (!shown(label)) return y;
        boolean on = Boolean.TRUE.equals(get(o, field));
        if (vis(y, 16)) {
            label(y, label, desc, 50);
            int sx = px + pw - 42;
            boolean over = mx >= sx && mx < sx + 30 && my >= y && my < y + 14;
            Gui.drawRect(sx, y + 1, sx + 30, y + 13, on ? (over ? 0xFF2FA055 : 0xFF1F7A3A) : (over ? 0xFF4A4060 : 0xFF2A2238));
            Gui.drawRect(on ? sx + 17 : sx + 2, y + 3, on ? sx + 28 : sx + 13, y + 11, 0xFFFFFFFF);
            click(sx, y, 30, 14, () -> set(o, field, !on));
        }
        return y + 16;
    }

    int num(int y, Object o, String field, String label, int step, String unit) {
        if (!shown(label)) return y;
        Field fl = fld(o, field);
        Config.RangeInt r = fl.getAnnotation(Config.RangeInt.class);
        int min = r == null ? 0 : r.min(), max = r == null ? Integer.MAX_VALUE : r.max();
        int v = (int) get(o, field);
        if (vis(y, 16)) {
            label(y, label, "", 120);
            int x = px + pw - 110;
            btn(x, y, 20, 14, "−", () -> set(o, field, Math.max(min, v - step * (isShiftKeyDown() ? 10 : 1))));
            String s = "§f" + v + unit;
            fontRenderer.drawStringWithShadow(s, x + 24 + (56 - fontRenderer.getStringWidth(s)) / 2, y + 3, 0xFFFFFF);
            if (mx >= x + 22 && mx < x + 82 && my >= y && my < y + 14) hover = "click to type a number (" + min + "–" + max + ")\n§7Shift+click −/+ = ×10";
            click(x + 22, y, 60, 14, () -> ask(label + " (" + min + "–" + max + ")", String.valueOf(v), s2 -> {
                try { set(o, field, Math.max(min, Math.min(max, Integer.parseInt(s2.trim())))); } catch (NumberFormatException ignored) {}
            }));
            btn(x + 84, y, 20, 14, "+", () -> set(o, field, (int) Math.min((long) max, (long) v + step * (isShiftKeyDown() ? 10 : 1))));
        }
        return y + 16;
    }

    int cycle(int y, Object o, String field, String label, String... values) {
        if (!shown(label)) return y;
        String v = String.valueOf(get(o, field));
        if (vis(y, 16)) {
            label(y, label, "", 130);
            btn(px + pw - 120, y, 110, 14, "§f" + v + " §8▸", () -> {
                int i = Arrays.asList(values).indexOf(v);
                set(o, field, values[(i + (isShiftKeyDown() ? values.length - 1 : 1)) % values.length]);
            });
        }
        return y + 16;
    }

    int text(int y, Object o, String field, String label) {
        if (!shown(label)) return y;
        String v = String.valueOf(get(o, field));
        if (vis(y, 16)) {
            label(y, label + ": §f" + (v.isEmpty() ? "§8(empty)" : v), "", 90);
            btn(px + pw - 80, y, 70, 14, "Change", () -> ask(label, v, s -> set(o, field, s)));
        }
        return y + 16;
    }

    int color(int y, Object o, String field, String label) {
        if (!shown(label)) return y;
        String v = String.valueOf(get(o, field));
        if (vis(y, 16)) {
            label(y, label, "", 130);
            int c;
            try { c = Integer.parseInt(v.replace("#", ""), 16); } catch (NumberFormatException e) { c = 0xFFFFFF; }
            int sx = px + pw - 120;
            Gui.drawRect(sx, y + 1, sx + 12, y + 13, 0xFF000000 | c);
            final int cur = c;
            click(sx, y, 12, 14, () -> {
                int i = 0;
                while (i < PALETTE.length && PALETTE[i] != cur) i++;
                set(o, field, String.format("%06X", PALETTE[(i + 1) % PALETTE.length]));
            });
            btn(sx + 16, y, 94, 14, "§f#" + v + " §8✎", () -> ask(label + " (hex like F5A9B8)", v, s -> set(o, field, s.replace("#", "").trim().toUpperCase(Locale.ROOT))));
            if (mx >= sx && mx < sx + 12 && my >= y && my < y + 14) hover = "click: next colour";
        }
        return y + 16;
    }

    /** a String[] option: one row per entry (edit / remove) + Add */
    int list(int y, Object o, String field, String label, String hint) {
        if (!shown(label)) return y;
        String[] arr = (String[]) get(o, field);
        if (vis(y, 14)) fontRenderer.drawStringWithShadow("§7" + label + (arr.length == 0 ? " §8— none" : ""), px, y + 3, 0xFFFFFF);
        y += 15;
        for (int i = 0; i < arr.length; i++) {
            final int k = i;
            if (vis(y, 14)) {
                fontRenderer.drawStringWithShadow("§f" + fontRenderer.trimStringToWidth(arr[i], pw - 90), px + 8, y + 3, 0xFFFFFF);
                btn(px + pw - 74, y, 30, 14, "✎", () -> ask(label, arr[k], s -> { String[] a = ((String[]) get(o, field)).clone(); a[k] = s; set(o, field, a); }));
                btn(px + pw - 40, y, 30, 14, "§c✖", () -> { List<String> a = new ArrayList<>(Arrays.asList((String[]) get(o, field))); a.remove(k); set(o, field, a.toArray(new String[0])); });
            }
            y += 15;
        }
        if (vis(y, 14)) btn(px + 8, y, 70, 14, "§a+ Add", () -> ask(hint, "", s -> {
            if (s.trim().isEmpty()) return;
            List<String> a = new ArrayList<>(Arrays.asList((String[]) get(o, field)));
            a.add(s.trim());
            set(o, field, a.toArray(new String[0]));
        }));
        return y + 18;
    }

    private int row(int y, String label, String button, Runnable r) {
        if (!shown(label)) return y;
        if (vis(y, 16)) {
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, pw - 100), px, y + 3, 0xFFFFFF);
            btn(px + pw - 90, y, 80, 14, button, r);
        }
        return y + 16;
    }

    private void ask(String q, String start, java.util.function.Consumer<String> a) {
        mc.displayGuiScreen(new GuiAskText(this, q, start, s -> { a.accept(s); ClientSide.saved(); }));
    }

    // ------------------------------------------------------------------ the screen
    @Override
    public void drawScreen(int mouseX, int mouseY, float pt) {
        mx = mouseX; my = mouseY;
        boxes.clear(); clicks.clear(); hover = null;
        String srv = ClientSide.server ? "§aserver has PrideChat" : mc.isIntegratedServerRunning() ? "§asingleplayer" : mc.player == null ? "§7main menu" : "§eserver without PrideChat";
        f.draw(this, "PrideChat settings", "§7" + ChatView.LINES.size() + " lines · " + ChatView.TABS.size() + " tabs · " + srv);
        // page list
        find.x = f.cx; find.y = f.cy;
        find.drawTextBox();
        if (find.getText().isEmpty() && !find.isFocused()) fontRenderer.drawString("§8⌕ find a setting…", f.cx + 4, f.cy + 3, 0xFFFFFF);
        int ly = f.cy + 18;
        for (int i = 0; i < PAGES.length; i++) {
            String p = PAGES[i];
            boolean on = p.equals(page) && q().isEmpty(), over = mx >= f.cx && mx < f.cx + side && my >= ly && my < ly + 15;
            PrideFrame.tile(f.cx, ly, side, 15, PrideFrame.RAINBOW[i % PrideFrame.RAINBOW.length], over, on);
            Emoji.Piece pc = Emoji.split(ICONS[Math.min(i, ICONS.length - 1)]).get(0);   // a page without its own icon never crashes the screen
            if (pc.emoji != null) Emoji.draw(pc.emoji, f.cx + 3, ly + 4, 9);
            fontRenderer.drawStringWithShadow(p, f.cx + 16, ly + 5, on ? 0xFFFFFF : 0xC8C0D8);
            click(f.cx, ly, side, 15, () -> { page = p; scroll = 0; find.setText(""); });
            ly += 16;
        }
        // the page
        if (vis(f.cy, 14)) fontRenderer.drawStringWithShadow("§l" + (q().isEmpty() ? page : "Search: " + find.getText()), px, f.cy + 3, 0xFFFFFF);
        PrideFrame.clip(px, top, pw, bottom - top);
        int y = top - scroll;
        if (q().isEmpty()) y = page(page, y);
        else for (String p : PAGES) y = page(p, y);
        PrideFrame.unclip();
        contentH = y + scroll - top;
        PrideFrame.scrollbar(px + pw - 3, top, bottom - top, scroll, bottom - top, Math.max(1, contentH));
        if (hover != null) drawHoveringText(Arrays.asList(hover.split("\n")), mx, my);
    }

    private static final String[] TIME_FORMATS = {"HH:mm", "HH:mm:ss", "h:mm a", "h:mm:ss a", "relative", "EEE HH:mm", "MMM d HH:mm"};

    private int page(String p, int y) {
        Object S = ChatConfig.server, A = ChatConfig.ai;
        boolean local = mc.isIntegratedServerRunning() || mc.player == null;
        switch (p) {
            case "General":
                y = heading(y, "Chat");
                y = bool(y, null, "enabled", "PrideChat on", "off = Minecraft's normal chat (your lines are kept)");
                y = bool(y, null, "tabs", "Tab strip", "the tabs above the chat while it's open");
                y = heading(y, "Long messages");
                y = num(y, null, "maxLength", "Longest message you can type", 1000, " letters");
                y = note(y, "On a server with PrideChat long messages go through in one piece (up to what the server allows). On other servers they're split into 256-letter pieces:");
                y = bool(y, null, "splitOnVanilla", "Split long messages on other servers", "off = refuse and tell you");
                y = num(y, null, "splitDelayTicks", "Wait between pieces", 5, " ticks");
                y = num(y, null, "maxSplitPieces", "Most pieces per message", 1, "");
                y = heading(y, "Memory");
                y = num(y, null, "historyLines", "Lines kept", 250, "");
                y = bool(y, null, "keepDraft", "Keep what I typed when I close the chat", "");
                y = bool(y, null, "saveInputHistory", "Remember sent messages (↑) between sessions", "");
                return y + 4;
            case "Range":
                y = heading(y, "How far your messages travel");
                y = note(y, ClientSide.server ? "This server has PrideChat: ranges work. Max range " + ClientSide.serverMaxRange + " blocks." : "Ranges need PrideChat on the server (singleplayer counts). Elsewhere everyone hears you.");
                y = cycle(y, null, "sendMode", "Talk by default", "local", "whisper", "shout", "global");
                y = num(y, null, "whisperRange", "Whisper reaches", 1, " blocks");
                y = num(y, null, "localRange", "Local reaches", 4, " blocks");
                y = num(y, null, "shoutRange", "Shout reaches", 8, " blocks");
                y = heading(y, "Quick ways");
                y = text(y, null, "globalPrefix", "Start with this to talk to everyone");
                y = text(y, null, "shoutPrefix", "Start with this to shout");
                y = text(y, null, "whisperPrefix", "Start with this to whisper");
                y = note(y, "Also: Ctrl+Enter shouts, Alt+Enter whispers, the button left of ☺ switches, and a key in Controls → PrideChat cycles it. /local /global /whisper /shout work too.");
                return y + 4;
            case "Look":
                y = heading(y, "Size and place");
                y = num(y, null, "width", "Width", 10, " px");
                y = num(y, null, "linesClosed", "Lines while closed", 1, "");
                y = num(y, null, "linesOpen", "Lines while open", 1, "");
                y = num(y, null, "scale", "Text size", 10, "%");
                y = num(y, null, "lineSpacing", "Space between lines", 1, " px");
                if (shown("Position")) {
                    if (vis(y, 16)) {
                        fontRenderer.drawStringWithShadow("Position §8(or drag ✥ in the open chat)", px, y + 3, 0xFFFFFF);
                        int bx = px + pw - 210;
                        btn(bx, y, 48, 14, "↙ Normal", () -> { ChatConfig.offsetX = 0; ChatConfig.offsetY = 0; });
                        btn(bx + 50, y, 48, 14, "↖ Top", () -> { ChatConfig.offsetX = 0; ChatConfig.offsetY = height - 48 - 60 - ChatConfig.linesClosed * ChatView.rowH(); });
                        btn(bx + 100, y, 48, 14, "↘ Right", () -> { ChatConfig.offsetX = width - ChatConfig.width - 8; ChatConfig.offsetY = 0; });
                        btn(bx + 150, y, 50, 14, "↗ Top-R", () -> { ChatConfig.offsetX = width - ChatConfig.width - 8; ChatConfig.offsetY = height - 48 - 60 - ChatConfig.linesClosed * ChatView.rowH(); });
                    }
                    y += 16;
                }
                y = heading(y, "Box");
                y = cycle(y, null, "background", "Background", "glass", "solid", "pride", "none");
                y = color(y, null, "backgroundColor", "Background colour");
                y = num(y, null, "opacity", "Background darkness", 5, "%");
                y = bool(y, null, "rounded", "Rounded corners", "");
                y = cycle(y, null, "rainbowEdge", "Rainbow edge", "open", "always", "never");
                y = heading(y, "Lines");
                y = bool(y, null, "textShadow", "Text shadow", "");
                y = bool(y, null, "chatHeads", "Faces in front of players' lines", "");
                y = bool(y, null, "compact", "Compact (no faces, no gaps)", "");
                y = bool(y, null, "slideIn", "New lines slide in", "");
                y = cycle(y, null, "lineAnimation", "New lines arrive with", Anim.KINDS);
                y = num(y, null, "animSpeed", "Animation speed", 10, "%");
                y = num(y, null, "fadeSeconds", "New lines stay visible for", 1, " s");
                y = cycle(y, null, "fadeStyle", "Then they", "fade", "instant", "never");
                y = bool(y, null, "unreadBadges", "Unread counts on tabs", "");
                y = bool(y, null, "jumpButton", "'↓ newest' button when scrolled up", "");
                y = bool(y, null, "tintOwn", "Tint my own lines", "");
                y = color(y, null, "ownColor", "My lines' tint");
                y = heading(y, "Time");
                y = bool(y, null, "timestamps", "Timestamps", "");
                y = cycle(y, null, "timeFormat", "Time format", TIME_FORMATS);
                y = color(y, null, "timeColor", "Time colour");
                return y + 4;
            case "Input":
                y = heading(y, "Typing");
                y = bool(y, null, "inputPreview", "Preview with emoji + colours above the box", "");
                y = bool(y, null, "charCounter", "Letter counter", "");
                y = bool(y, null, "emojiAutocomplete", ":shortcode suggestions (Tab picks)", "");
                y = bool(y, null, "multiLine", "Shift+Enter = new line", "pasted text keeps its lines too");
                y = bool(y, null, "formatButtons", "Format button (colours, bold, rainbow, emotes)", "the server turns &codes into colours when it allows them");
                y = bool(y, null, "spellCheck", "Spell check (red wavy line)", "names, :emoji:, links and short CAPS are skipped");
                y = list(y, null, "myWords", "My words (spell check accepts them)", "A word");
                y = cycle(y, null, "rpMode", "Roleplay style", "chat", "actions", "novel", "script");
                y = bool(y, null, "toneNudge", "Ask me before sending something harsh", "with AI on the server it suggests a kinder way");
                return y + 4;
            case "Emoji":
                y = heading(y, "Emoji");
                y = bool(y, null, "emoji", "Colour emoji", ":heart: and ❤ show as pictures (1,162 Twemoji, CC-BY 4.0)");
                y = num(y, null, "emojiSize", "Emoji size", 1, " px");
                y = num(y, null, "skinTone", "Skin tone for hands + people (0 = default)", 1, "");
                if (vis(y, 14) && q().isEmpty()) {
                    fontRenderer.drawStringWithShadow("§7Preview:", px, y + 3, 0xFFFFFF);
                    String[] sample = {"red_heart", "rainbow_flag", "transgender_flag", "sparkles", "smiling_face_with_hearts", "cat", "star_struck", "pizza"};
                    int x = px + 60;
                    for (String s : sample) { Emoji.E e = Emoji.byCode(s); if (e != null) { Emoji.draw(e, x, y + 2, ChatConfig.emojiSize); x += ChatConfig.emojiSize + 3; } }
                }
                y += q().isEmpty() ? 18 : 0;
                y = note(y, "Type :name: (like :heart: :sparkles: :pride:) or use ☺ in the chat. Emoji show in chat, in the preview and in speech bubbles.");
                return y + 4;
            case "Alerts":
                y = heading(y, "Mentions");
                y = bool(y, null, "mentions", "Highlight lines that mention me", "");
                y = bool(y, null, "smartMentions", "Also short forms of my name", "e.g. 'Alex' for Alexandra");
                y = color(y, null, "mentionColor", "Mention colour");
                y = list(y, null, "mentionWords", "Words that also count as a mention", "A word or nickname");
                y = heading(y, "Who's talking");
                y = bool(y, null, "talkIndicator", "'Name talking 43m ↗' for far / unseen talkers", "an arrow points to them; map mods get the same through TalkEvent");
                y = num(y, null, "talkIndicatorDistance", "…when at least", 4, " blocks away (or behind me)");
                y = num(y, null, "talkIndicatorSeconds", "…for", 1, " s");
                y = heading(y, "Pop-ups");
                y = bool(y, null, "dnd", "Do not disturb (no sounds, no pop-ups)", "");
                y = bool(y, null, "toastMentions", "Pop-up when mentioned (chat closed)", "");
                y = bool(y, null, "toastDMs", "Pop-up for private messages", "");
                y = bool(y, null, "toastFriends", "Pop-up when friends join / leave", "");
                y = num(y, null, "toastSeconds", "Pop-ups stay", 1, " s");
                return y + 4;
            case "Sounds":
                y = heading(y, "Sounds");
                y = cycle(y, null, "mentionSound", "Mention", ClientSide.SOUNDS);
                y = cycle(y, null, "dmSound", "Private message", ClientSide.SOUNDS);
                y = cycle(y, null, "messageSound", "Any new message", ClientSide.SOUNDS);
                y = cycle(y, null, "sendSound", "When I send", ClientSide.SOUNDS);
                y = cycle(y, null, "friendSound", "Friend joins", ClientSide.SOUNDS);
                y = num(y, null, "volume", "Volume", 5, "%");
                y = row(y, "§7Hear them", "▶ Play all", () -> { ClientSide.sound(ChatConfig.mentionSound); ClientSide.sound(ChatConfig.dmSound); ClientSide.sound(ChatConfig.friendSound); });
                y = note(y, "Each tab can have its own sound too (Tabs page). Note-block sounds keep it chiptune-y.");
                return y + 4;
            case "Tabs": return tabsPage(y);
            case "People":
                y = list(y, null, "friends", "Friends", "A player's name");
                y = list(y, null, "muted", "Muted (lines stay, grey + quiet, no bubbles)", "A player's name");
                y = list(y, null, "ignored", "Ignored / blocked (hides their lines, bubbles and DMs)", "A player's name");
                y = heading(y, "Hide just one thing §8(or right-click their line / bubble)");
                y = list(y, null, "hiddenBubbles", "Their bubbles", "A player's name");
                y = list(y, null, "hiddenChat", "Their chat lines (bubbles stay)", "A player's name");
                y = list(y, null, "hiddenEmotes", "Their emotes (lines, bubbles, screen shakes)", "A player's name");
                y = list(y, null, "hiddenTyping", "Their typing dots", "A player's name");
                y = heading(y, "Private messages");
                y = bool(y, null, "dmTabs", "A tab opens for each person who messages me", "");
                y = text(y, null, "msgCommand", "Server's private message command");
                y = bool(y, null, "sendReadReceipts", "Tell people I read their messages (✓✓)", "");
                y = bool(y, null, "sendTyping", "Show others when I'm typing", "");
                y = bool(y, null, "showTyping", "Show when others are typing", "");
                y = heading(y, "What I share §8(off = it's never sent)");
                y = bool(y, null, "sendTypingSpeed", "How fast I type (my dots speed up)", "only a 0-30 keys/s number, a few times a second at most");
                y = bool(y, null, "sendGui", "When I'm in a menu / chest / crafting", "others see a little holo screen in front of you");
                y = bool(y, null, "sendIdle", "When I'm AFK (I fall asleep, Zz)", "after 'Count me AFK after' below (the server may set its own time)");
                y = heading(y, "Away");
                y = num(y, null, "afkMinutes", "Count me AFK / asleep after", 1, " min");
                y = bool(y, null, "idleNotices", "Chat line when someone falls asleep / wakes up", "");
                y = bool(y, null, "autoReply", "Auto-reply to private messages", "");
                y = bool(y, null, "autoReplyOnlyAfk", "…only while I'm AFK", "");
                y = text(y, null, "autoReplyText", "Auto-reply");
                y = bool(y, null, "aiAutoReply", "Let the AI write it in my style", "needs AI on the server");
                return y + 4;
            case "Bubbles":
                y = heading(y, "Speech bubbles + heads");
                y = bool(y, null, "bubbles", "Speech bubbles above players", "");
                y = bool(y, null, "bubbleHead", "Their skin head floats above the bubble", "");
                y = bool(y, null, "headAlways", "Float everyone's head all the time", "");
                y = bool(y, null, "head3d", "3D head that turns", "off = a flat face");
                y = cycle(y, null, "headLook", "Heads look", "smart", "you", "forward");
                y = cycle(y, null, "bubbleStyle", "My bubble style (others see it)", Bubbles.STYLES);
                y = cycle(y, null, "bubbleStyleOthers", "Other people's bubbles", "theirs", "rounded", "square", "mc", "comic", "speech", "thought", "dark", "transparent", "glass", "pixel", "holo", "minimal");
                y = bool(y, null, "bubbleOwn", "My own bubble (third person)", "");
                y = bool(y, null, "typingBubble", "Typing dots", "");
                y = bool(y, null, "screenShake", "Shake my screen when someone near me screams", "/emote screams, roars…");
                y = bool(y, null, "bubbleSeeThrough", "See bubbles through walls", "");
                y = num(y, null, "bubbleSeconds", "Bubble stays", 1, " s");
                y = num(y, null, "bubbleDistance", "Show for players within", 2, " blocks");
                y = num(y, null, "bubbleWidth", "Bubble width", 10, " px");
                y = num(y, null, "bubbleMaxLines", "Lines per bubble", 1, "");
                y = num(y, null, "bubbleStack", "Bubbles stacked", 1, "");
                y = num(y, null, "bubbleOpacity", "Bubble opacity", 5, "%");
                y = num(y, null, "bubbleScale", "Bubble size", 10, "%");
                y = cycle(y, null, "bubbleAnimation", "New bubbles", "pop", "theirs", "fade", "slide", "bounce", "typewriter", "scale", "dissolve", "enchant", "hologram", "float", "none");
                y = list(y, null, "hiddenBubbles", "Players whose bubbles I hide", "A player's name");
                y = heading(y, "What are they up to");
                y = bool(y, null, "typingWacky", "Dots get faster + wackier the faster they type", "bouncy, then wobbly, then rainbow");
                y = bool(y, null, "showGuiHolo", "Holo screen for players in a menu", "icon: inventory, chest, crafting, furnace, other");
                y = bool(y, null, "guiArmSwing", "…their arm taps at it", "");
                y = bool(y, null, "showSleep", "AFK players fall asleep (head droops, Zz)", "");
                y = bool(y, null, "sleepTabList", "Zz next to AFK players in the tab list", "");
                y = note(y, "Whispers get a small bubble, shouts a big one, bubbles that mention you a pink edge; far-away lines look faded. Reactions (right-click a message) show on the bubble too. Smart heads turn to whoever is talking to them.");
                return y + 4;
            case "My look":
                y = heading(y, "How others see me §8(PrideChat servers; the server may limit it)");
                y = color(y, null, "myBubbleColor", "Bubble colour (empty = the style's)");
                y = color(y, null, "myBubbleBorder", "Bubble border (empty = none)");
                y = num(y, null, "myBubbleBorderSize", "Border thickness", 1, " px");
                y = num(y, null, "myBubbleAlpha", "Bubble opacity", 5, "%");
                y = num(y, null, "myBubbleSeconds", "Bubbles stay (0 = their setting)", 1, " s");
                y = cycle(y, null, "myBubbleAnimation", "Bubbles appear with", "", "fade", "slide", "pop", "bounce", "typewriter", "scale", "dissolve", "enchant", "hologram", "float");
                y = num(y, null, "myHeadSize", "Floating head size", 10, "%");
                y = color(y, null, "myHeadBorder", "Frame around my head (empty = none)");
                y = heading(y, "Chat colours §8(permission pridechat.appearance.colors)");
                y = color(y, null, "myNameColor", "My name");
                y = color(y, null, "myMessageColor", "My messages");
                y = color(y, null, "myChannelColor", "The [L] / [#channel] tag");
                y = note(y, "Chat only has 16 colours, so these snap to the nearest one. Clear a colour (✎, empty) to go back to normal. Your bubble style is on the Bubbles page.");
                y = heading(y, "Other people");
                y = bool(y, null, "othersLook", "Show other players' own looks", "off = every bubble and head uses my settings");
                return y + 4;
            case "Filters":
                y = heading(y, "Hide");
                y = bool(y, null, "hideJoinLeave", "Joined / left messages", "");
                y = bool(y, null, "hideDeaths", "Death messages", "");
                y = bool(y, null, "hideAdvancements", "Advancement messages", "");
                y = heading(y, "Spam");
                y = bool(y, null, "collapseDuplicates", "Same line twice → one line ×2", "");
                y = bool(y, null, "spamRepeat", "Hide repeats within 30 s", "");
                y = num(y, null, "floodLimit", "Most lines per player per 5 s (0 = off)", 1, "");
                y = bool(y, null, "calmCaps", "ALL CAPS → lower case", "");
                y = heading(y, "Words");
                y = bool(y, null, "wordFilter", "Word filter", "");
                y = cycle(y, null, "wordFilterMode", "Filter does", "censor", "hide");
                y = list(y, null, "filterWords", "Filtered words", "A word");
                y = text(y, null, "reportCommand", "Report command ({name} {msg})");
                return y + 4;
            case "Tools":
                y = heading(y, "Tools");
                y = bool(y, null, "logToFile", "Save chat to logs/pridechat/<date>.log", "");
                y = bool(y, null, "middleClickCopy", "Middle-click a line to copy it", "");
                y = text(y, null, "translateTo", "Translate to");
                y = heading(y, "Shortcuts");
                y = bool(y, null, "macroVars", "Fill in %x %y %z %dim %biome %health %name %time", "");
                y = list(y, null, "macros", "Macros (send exactly the trigger)", "trigger | text, e.g. !home | heading home :house:");
                y = heading(y, "Do things");
                y = row(y, "§7Show a pretend mention", "Try it", () -> mc.ingameGUI.getChatGUI().printChatMessage(
                        new TextComponentString("<Friend> hey " + (mc.player == null ? "you" : mc.player.getName()) + " look :sparkles: :rainbow_flag: ❤")));
                y = row(y, "§7Save this tab to a file", "Export", this::export);
                y = row(y, "§7Open the chat logs folder", "Open", () -> openFolder(new File(mc.mcDataDir, "logs/pridechat")));
                y = row(y, "§7Clear this session's chat", "§cClear", () -> mc.ingameGUI.getChatGUI().clearChatMessages(false));
                y = row(y, "§7Every setting back to how it came", "§cReset all", () -> ask("Type RESET to put every PrideChat setting back", "", s -> { if ("RESET".equals(s.trim())) resetAll(); }));
                return y + 4;
            case "Server":
                y = heading(y, "Server");
                y = note(y, local ? "Singleplayer: you are the server, so these work right here. On a dedicated server they live in its config/pridechat.cfg."
                        : "These are set by the server (its config/pridechat.cfg). You see your own copy here.");
                if (!local) return y + 4;
                y = num(y, S, "maxLength", "Longest message accepted", 1000, "");
                y = num(y, S, "maxRange", "Furthest range", 16, " blocks");
                y = bool(y, S, "allowGlobal", "Players may talk to everyone", "");
                y = bool(y, S, "allowColors", "&colour codes become colours", "");
                y = bool(y, S, "itemLinks", "[item] shows the held item", "");
                y = bool(y, S, "textEffects", "Text effects [glow] [shadow] [color] [type]", "permission pridechat.effects");
                y = list(y, S, "allowedEffects", "Allowed effects", "glow, shadow, color or type");
                y = bool(y, S, "falloff", "Text fades near the edge of the range", "");
                y = bool(y, S, "occlusion", "Walls muffle ranged chat", "");
                y = num(y, S, "wallDamping", "Each wall takes off", 5, "% range");
                y = num(y, S, "floorDamping", "Each floor / ceiling takes off", 5, "% range");
                y = num(y, S, "undergroundBonus", "Tunnels + under a roof carry", 5, "% further");
                y = bool(y, S, "portalChat", "Talk through linked Nether portals", "stand near a portal; people at its other end hear you (muffled)");
                y = num(y, S, "portalHearRange", "…within", 1, " blocks of the portal");
                y = num(y, S, "portalLinkRange", "…ends linked within", 1, " Nether blocks");
                y = list(y, S, "dimensionRules", "Per-dimension range", "dimension id | multiplier, e.g. -1 | 0.75");
                y = bool(y, S, "tellNobodyHeard", "Tell me when nobody heard", "");
                y = bool(y, S, "activityGui", "Players see who's in a menu (holo screen)", "what are they up to");
                y = bool(y, S, "activityIdle", "AFK players fall asleep (Zz, tab list)", "what are they up to");
                y = bool(y, S, "activityTypingSpeed", "Typing dots speed up with typing speed", "what are they up to");
                y = num(y, S, "idleMinutes", "Fall asleep after (0 = each player's AFK time)", 1, " min");
                y = num(y, S, "slowMode", "Slow mode", 1, " s");
                y = num(y, S, "floodLimit", "Most messages per 5 s", 1, "");
                y = bool(y, S, "appearance", "Players pick their own bubble / head / name looks", "permissions pridechat.appearance, pridechat.appearance.colors");
                y = num(y, S, "maxBubbleSeconds", "…bubbles stay at most", 1, " s");
                y = num(y, S, "minBubbleAlpha", "…bubbles at least", 5, "% opaque");
                y = num(y, S, "maxHeadSize", "…heads at most", 10, "%");
                y = num(y, S, "historyKeep", "Lines new players can scroll back to", 10, "");
                y = cycle(y, S, "historyRetention", "Keep chat on disk", "7d", "30d", "forever", "1d", "1h", "off");
                y = num(y, S, "historyStore", "Most lines on disk", 500, "");
                y = bool(y, S, "mail", "Mail for offline players", "");
                y = bool(y, S, "takeOverMsg", "PrideChat's /msg with @nearest @here @team @party @radius N", "needs a restart of the world");
                y = text(y, S, "whisperTag", "Whisper tag");
                y = text(y, S, "localTag", "Local tag");
                y = text(y, S, "shoutTag", "Shout tag");
                y = text(y, S, "announceTag", "Announcement style");
                y = text(y, S, "botName", "Helper bot's name");
                y = bool(y, S, "rangeStyle", "Whispers italic, shouts bold", "");
                y = bool(y, S, "whisperFacing", "Whisper while looking at someone = just to them", "");
                y = num(y, S, "whisperFacingRange", "…when they're within", 1, " blocks");
                y = bool(y, S, "sneakWhisper", "Sneaking turns local into whisper", "");
                y = bool(y, S, "rpFormat", "*stars* make italics (roleplay)", "");
                y = list(y, S, "rpModeChannels", "Channels with a forced roleplay style", "channel | chat/actions/novel/script");
                y = list(y, S, "contextEffects", "How you sound changes when…", "underwater, fire, sprint, riding or hurt");
                y = bool(y, S, "emoteParticles", "Particles for /emote hug, dance, laugh…", "");
                y = list(y, S, "emoteEffects", "What /emote actions do", "regex | wave, clap, point, dance, particles:heart, shake:8, tiny, giant, range:shout");
                y = list(y, S, "channels", "Channels", "name | tag | permission | range | open/invite | key item | dimension");
                y = list(y, S, "rules", "Rules shown on join", "A line (& colours ok)");
                y = list(y, S, "bots", "Auto-responders", "regex | reply ({name})");
                y = heading(y, "Cross-server chat §8(off by default)");
                y = bool(y, S, "crossServer", "Link chat with other servers", "through tools/pridechat-relay.py — docs/CROSS-SERVER.md; needs a world restart");
                y = text(y, S, "relayUrl", "Relay address");
                y = text(y, S, "serverName", "This server's name");
                y = text(y, S, "relaySecret", "Shared secret");
                y = list(y, S, "crossChannels", "What crosses", "global or a channel name");
                y = text(y, S, "crossTag", "Tag for other servers' lines");
                y = heading(y, "Radios & devices");
                y = bool(y, S, "devices", "Radios, phones, intercoms, speakers, megaphones, PA", "items + blocks in the PrideChat creative tab");
                y = num(y, S, "walkieRange", "Walkie-talkies reach", 16, " blocks");
                y = num(y, S, "towerRadius", "Radio towers cover", 16, " blocks");
                y = num(y, S, "megaphoneRange", "Megaphone shout", 16, " blocks");
                y = num(y, S, "speakerRange", "Speakers heard within", 1, " blocks");
                y = num(y, S, "intercomRange", "Intercoms connect within", 8, " blocks");
                y = num(y, S, "phoneRange", "Phone calls heard within", 1, " blocks");
                y = heading(y, "Rooms & zones");
                y = bool(y, S, "rooms", "Rooms keep talk inside (soundproof: nothing in or out)", "made with the Room Marker; /room list");
                y = num(y, S, "maxRoomVolume", "Biggest room for non-ops", 10000, " blocks");
                y = text(y, S, "zoneTag", "Zone chat tag");
                y = text(y, S, "dimTag", "Dimension chat tag");
                y = heading(y, "Moderation");
                y = bool(y, S, "moderation", "Rule-based moderation", "");
                y = bool(y, S, "opsExempt", "Ops skip it", "");
                y = bool(y, S, "notifyStaff", "Tell staff about hits", "");
                y = num(y, S, "muteMinutes", "Automatic mute", 1, " min");
                y = list(y, S, "modActions", "What each problem does", "category | allow/soften/hide/shadow/warn/mute/kick/staff");
                y = list(y, S, "badWords", "Server word list", "A word");
                y = note(y, "Staff commands: /chatmute /chatunmute /chatshadow /chatslow /announce. Log: logs/pridechat/moderation.jsonl");
                return y + 4;
            case "AI":
                y = heading(y, "AI helpers");
                y = note(y, "Uses PrideMind if it's installed and thinking, else Buddy Builder's local model (CPU, 'buddy-cpu' — it never takes the GPU from Minecraft). Nothing runs until you switch it on. Messages only go to YOUR machine's model; nothing leaves your computer.");
                y = note(y, "Status: " + (ClientSide.serverAI ? "§aon" : "§7off") + (local ? " §8· " + AI.status + " · endpoint " + endpoint[0] + " · model " + endpoint[1] : ""));
                if (local) {
                    y = bool(y, A, "enabled", "AI on (master switch)", "off = rules only, no model is ever called");
                    y = bool(y, A, "usePrideMind", "Use PrideMind when it's ready", "");
                    y = bool(y, A, "direct", "Else call the endpoint directly", "");
                    y = text(y, A, "endpoint", "Endpoint (empty = Buddy Builder's)");
                    y = text(y, A, "model", "Model (empty = buddy-cpu)");
                    y = num(y, A, "maxPerMinute", "Most AI calls per minute", 5, "");
                    y = heading(y, "Moderation");
                    y = bool(y, A, "moderation", "AI second opinion on messages", "after the rules; can warn, mute, tell staff, log");
                    y = bool(y, A, "holdForReview", "Hold messages until the AI looked", "never longer than the deadline below");
                    y = num(y, A, "reviewDeadlineMs", "Longest hold", 100, " ms");
                    y = num(y, A, "minLength", "Only messages at least", 1, " letters");
                    y = num(y, A, "actConfidence", "Act when at least", 5, "% sure");
                    y = num(y, A, "softConfidence", "Ignore below", 5, "% sure");
                    y = heading(y, "Helpers");
                    y = bool(y, A, "assistant", "'@" + ChatConfig.server.botName + " …' and /ask answer", "");
                    y = num(y, A, "askCooldown", "Seconds between questions", 5, " s");
                    y = bool(y, A, "catchUp", "'While you were away' summary", "");
                    y = num(y, A, "catchUpMinutes", "…after being away", 5, " min");
                    y = bool(y, A, "translate", "Translate (right-click a message)", "");
                    y = bool(y, A, "tone", "Kinder-wording suggestions", "");
                    y = bool(y, A, "autoReply", "AFK replies in your style", "");
                    y = heading(y, "Talking NPCs");
                    y = bool(y, A, "npcs", "NPCs answer chat near them", "Custom NPCs or any mob tagged: /tag @e[name=Mira] add " + ChatConfig.ai.npcTag);
                    y = text(y, A, "npcTag", "Tag that makes them talk");
                    y = num(y, A, "npcHearRange", "They hear within", 1, " blocks");
                    y = num(y, A, "npcCooldownSeconds", "One NPC answers at most every", 1, " s");
                    y = list(y, A, "npcPersonas", "Who they are", "Name | persona, e.g. Mira | the cheerful baker");
                }
                return y + 4;
            default: return y;
        }
    }

    // ------------------------------------------------------------------ tabs page
    private int tabsPage(int y) {
        y = heading(y, "Tabs §8(a line shows in every tab it matches)");
        if (q().isEmpty()) {
            List<String> tabs = new ArrayList<>(Arrays.asList(ChatConfig.tabList));
            for (int i = 0; i < tabs.size(); i++) {
                final int k = i;
                String[] p = tabs.get(i).split("\\s*\\|\\s*", -1);
                if (p.length < 3) continue;
                if (vis(y, 16)) {
                    int col;
                    try { col = Integer.parseInt(p[1].trim(), 16); } catch (NumberFormatException e) { col = 0xF5A9B8; }
                    Gui.drawRect(px, y + 2, px + 10, y + 12, 0xFF000000 | col);
                    click(px, y + 2, 10, 10, () -> recolour(k));
                    String flags = p.length > 4 ? p[4].trim() : "";
                    fontRenderer.drawStringWithShadow("§f" + p[0] + (flags.contains("m") ? " §8🔇" : "") + " §8· " + fontRenderer.trimStringToWidth(GuiPrideChat.describe(p[2].trim()), pw - 290), px + 14, y + 3, 0xFFFFFF);
                    if (mx >= px && mx < px + 10 && my >= y + 2 && my < y + 12) hover = "Click to change the colour";
                    int bx = px + pw - 192;
                    String snd = p.length > 3 ? p[3].trim() : "none";
                    btn(bx, y, 70, 14, "♪ " + snd, () -> part(k, 3, next(ClientSide.SOUNDS, snd)));
                    btn(bx + 72, y, 18, 14, "▲", () -> move(k, -1));
                    btn(bx + 92, y, 18, 14, "▼", () -> move(k, 1));
                    btn(bx + 112, y, 18, 14, flags.contains("m") ? "🔇" : "🔊", () -> part(k, 4, flags.contains("m") ? flags.replace("m", "") : flags + "m"));
                    btn(bx + 132, y, 24, 14, "✎", () -> editTab(k));
                    btn(bx + 158, y, 24, 14, "§c✖", () -> { List<String> t = new ArrayList<>(Arrays.asList(ChatConfig.tabList)); if (t.size() > 1) t.remove(k); ChatConfig.tabList = t.toArray(new String[0]); });
                }
                y += 18;
            }
        }
        y = row(y, "§7New tab", "§a+ Add", () -> ask("name | hex colour | rule | sound | flags", "Trade | 7CFC9A | (?i)(buy|sell|trade|wts|wtb) | pop | ",
                s -> { if (s.split("\\|").length >= 3) { List<String> t = new ArrayList<>(Arrays.asList(ChatConfig.tabList)); t.add(s.trim()); ChatConfig.tabList = t.toArray(new String[0]); } }));
        y = row(y, "§7Put the tabs back the way they came", "Reset", () -> ChatConfig.tabList = ChatConfig.defaultTabs());
        y = note(y, "Rules: a regex on the text, or @all @player @system @mention @local @global @dm @dm:Name #channel. Flags: m = muted, n = pop-ups. "
                + "Private-message tabs and tabs for channels/groups you're in appear by themselves; right-click a tab in the chat to close or mute it.");
        y = bool(y, null, "dmTabs", "Private-message tabs open by themselves", "");
        y = heading(y, "Extra windows");
        y = list(y, null, "windows", "Windows (right-click a tab → Pop out)", "rule | x | y | width | lines, e.g. @radio | 600 | 10 | 200 | 8");
        y = list(y, null, "tabFilters", "Hidden kinds per tab (☰ in the chat)", "rule | local,whisper,shout,emote,global,channel,dm,radio,zone,dim,announce,bot,system");
        return y + 4;
    }

    private static String next(String[] values, String cur) { int i = Arrays.asList(values).indexOf(cur); return values[(i + 1) % values.length]; }

    /** replace one | part of a tab spec */
    private void part(int k, int idx, String value) {
        String[] p = ChatConfig.tabList[k].split("\\s*\\|\\s*", -1);
        String[] q = Arrays.copyOf(p, Math.max(p.length, 5));
        for (int i = 0; i < q.length; i++) if (q[i] == null) q[i] = i == 3 ? "none" : "";
        q[idx] = value;
        ChatConfig.tabList[k] = String.join(" | ", Arrays.asList(q).subList(0, 5)).trim();
        ClientSide.saved();
    }

    private void move(int k, int d) {
        List<String> t = new ArrayList<>(Arrays.asList(ChatConfig.tabList));
        int j = k + d;
        if (j < 0 || j >= t.size()) return;
        Collections.swap(t, k, j);
        ChatConfig.tabList = t.toArray(new String[0]);
    }

    private void recolour(int k) {
        String[] p = ChatConfig.tabList[k].split("\\s*\\|\\s*", -1);
        int cur;
        try { cur = Integer.parseInt(p[1].trim(), 16); } catch (NumberFormatException e) { cur = 0; }
        int i = 0;
        while (i < PALETTE.length && PALETTE[i] != cur) i++;
        part(k, 1, String.format("%06X", PALETTE[(i + 1) % PALETTE.length]));
    }

    private void editTab(int k) {
        ask("name | hex colour | rule | sound | flags", ChatConfig.tabList[k], s -> { if (s.split("\\|").length >= 3) ChatConfig.tabList[k] = s.trim(); });
    }

    private void export() {
        try {
            File dir = new File(mc.mcDataDir, "logs/pridechat");
            dir.mkdirs();
            String name = ChatView.TABS.get(ChatView.active).name.replaceAll("[^A-Za-z0-9_-]", "");
            File out = new File(dir, "export-" + name + "-" + System.currentTimeMillis() / 1000 + ".txt");
            StringBuilder b = new StringBuilder();
            java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            long bit = 1L << ChatView.active;
            for (ChatView.Line l : ChatView.LINES) if ((l.tabBits & bit) != 0) b.append('[').append(f.format(new Date(l.time))).append("] ").append(l.plain).append('\n');
            Files.write(out.toPath(), b.toString().getBytes(StandardCharsets.UTF_8));
            ClientSide.info("§7Saved to " + out.getPath());
        } catch (Exception e) { ClientSide.info("§cCouldn't save: " + e); }
    }

    private static void openFolder(File dir) {
        dir.mkdirs();
        try { java.awt.Desktop.getDesktop().open(dir); }
        catch (Throwable t) { try { new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start(); } catch (Exception ignored) {} }
    }

    /** every client field back to its default (a fresh ChatConfig can't be made, so copy from known defaults by re-reading the class) */
    private void resetAll() {
        File cfg = new File(mc.mcDataDir, "config/pridechat.cfg");
        if (cfg.delete()) ClientSide.info("§7Settings reset — restart the game to finish.");
    }

    // ------------------------------------------------------------------ input
    @Override
    protected void mouseClicked(int x, int y, int button) throws IOException {
        find.mouseClicked(x, y, button);
        if (button != 0) return;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            int[] b = boxes.get(i);
            boolean inPage = b[0] >= px;
            if (x >= b[0] && x < b[0] + b[2] && y >= b[1] && y < b[1] + b[3] && (!inPage || y >= top && y < bottom)) {
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1f));
                clicks.get(i).run();
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE) { if (find.isFocused() && !find.getText().isEmpty()) { find.setText(""); return; } mc.displayGuiScreen(back); return; }
        if (find.isFocused() || Character.isLetterOrDigit(c) && !find.isFocused()) {
            if (!find.isFocused()) find.setFocused(true);
            find.textboxKeyTyped(c, key);
            scroll = 0;
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    public void updateScreen() { find.updateCursorCounter(); }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Integer.signum(Mouse.getEventDWheel());
        if (d != 0) scroll = Math.max(0, Math.min(Math.max(0, contentH - (bottom - top) + 10), scroll - d * 24));
    }
}
