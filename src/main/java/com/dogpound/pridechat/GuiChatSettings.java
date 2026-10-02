package com.dogpound.pridechat;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.text.TextComponentString;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.*;

/**
 * PrideChat settings — every option on one Pride panel: the chat on/off, how it looks, emoji, mentions, the TABS
 * (add / edit / colour / move / delete / reset), ignored players, and a couple of tools. Saves as you click.
 */
public class GuiChatSettings extends GuiScreen {
    private final GuiScreen back;
    private PrideFrame f;
    private int scroll, contentH, top, bottom, px, pw;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> clicks = new ArrayList<>();
    private String hover;
    private static final String[] TIME_FORMATS = {"HH:mm", "HH:mm:ss", "h:mm a", "h:mm:ss a"};
    private static final int[] PALETTE = {0xF5A9B8, 0x5BCEFA, 0xFFD700, 0xB070FF, 0xFF8C00, 0xE40303, 0x008026, 0x24408E, 0x8A8499, 0xFFFFFF};

    GuiChatSettings(GuiScreen back) { this.back = back; }

    @Override
    public void initGui() {
        f = PrideFrame.fit(width, height);
        px = f.cx; pw = f.cw;
        top = f.cy; bottom = f.cy + f.ch;
    }

    @Override public boolean doesGuiPauseGame() { return false; }

    private void click(int x, int y, int w, int h, Runnable r) { boxes.add(new int[]{x, y, w, h}); clicks.add(r); }
    private boolean vis(int y, int h) { return y + h > top && y < bottom; }

    private void btn(int x, int y, int w, int h, String label, Runnable r, int mx, int my) {
        if (!vis(y, h)) return;
        PrideFrame.button(x, y, w, h, label, PrideFrame.BUTTON, mx, my);
        click(x, y, w, h, () -> { r.run(); PrideChat.saved(); });
    }

    private int heading(int y, String t) {
        if (vis(y, 14)) { fontRenderer.drawStringWithShadow(t, px, y + 2, 0xFFFFFF); Gui.drawRect(px, y + 12, px + pw - 8, y + 13, 0x30FFFFFF); }
        return y + 17;
    }

    private int toggle(int y, String label, String desc, boolean on, Runnable flip, int mx, int my) {
        if (vis(y, 20)) {
            fontRenderer.drawStringWithShadow(label, px, y + 1, 0xFFFFFF);
            if (!desc.isEmpty()) fontRenderer.drawStringWithShadow("§8" + fontRenderer.trimStringToWidth(desc, pw - 60), px, y + 11, 0xFFFFFF);
            int sx = px + pw - 42;
            boolean over = mx >= sx && mx < sx + 30 && my >= y && my < y + 14;
            Gui.drawRect(sx, y + 1, sx + 30, y + 13, on ? (over ? 0xFF2FA055 : 0xFF1F7A3A) : (over ? 0xFF4A4060 : 0xFF2A2238));
            Gui.drawRect(on ? sx + 17 : sx + 2, y + 3, on ? sx + 28 : sx + 13, y + 11, 0xFFFFFFFF);
            click(sx, y, 30, 14, () -> { flip.run(); PrideChat.saved(); });
        }
        return y + (desc.isEmpty() ? 16 : 24);
    }

    private int stepper(int y, String label, String value, Runnable minus, Runnable plus, int mx, int my) {
        if (vis(y, 14)) {
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, pw - 120), px, y + 3, 0xFFFFFF);
            int x = px + pw - 110;
            btn(x, y, 20, 14, "−", minus, mx, my);
            String v = "§f" + value;
            fontRenderer.drawStringWithShadow(v, x + 24 + (56 - fontRenderer.getStringWidth(v)) / 2, y + 3, 0xFFFFFF);
            btn(x + 84, y, 20, 14, "+", plus, mx, my);
        }
        return y + 16;
    }

    private int row(int y, String label, String button, Runnable r, int mx, int my) {
        if (vis(y, 14)) {
            fontRenderer.drawStringWithShadow(fontRenderer.trimStringToWidth(label, pw - 90), px, y + 3, 0xFFFFFF);
            btn(px + pw - 80, y, 70, 14, button, r, mx, my);
        }
        return y + 16;
    }

    private void ask(String q, String start, java.util.function.Consumer<String> a) {
        mc.displayGuiScreen(new GuiAskText(this, q, start, s -> { a.accept(s); PrideChat.saved(); }));
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        boxes.clear(); clicks.clear(); hover = null;
        f.draw(this, "PrideChat settings", "§7" + ChatView.LINES.size() + " lines kept · " + ChatView.TABS.size() + " tabs");
        PrideFrame.clip(px, top, pw, bottom - top);
        int y = top - scroll;

        y = heading(y, "§fChat");
        y = toggle(y, "PrideChat on", "off = Minecraft's normal chat (your lines are kept)", ChatConfig.enabled, () -> ChatConfig.enabled = !ChatConfig.enabled, mx, my);
        y = toggle(y, "Tab strip", "the tabs above the chat while it's open", ChatConfig.tabs, () -> ChatConfig.tabs = !ChatConfig.tabs, mx, my);
        y = toggle(y, "Timestamps", "the time in front of each line", ChatConfig.timestamps, () -> ChatConfig.timestamps = !ChatConfig.timestamps, mx, my);
        y = row(y, "§7Time format: §f" + ChatConfig.timeFormat, "Change", () -> {
            int i = Arrays.asList(TIME_FORMATS).indexOf(ChatConfig.timeFormat);
            ChatConfig.timeFormat = TIME_FORMATS[(i + 1) % TIME_FORMATS.length];
        }, mx, my);
        y += 4;

        y = heading(y, "§fLook");
        y = stepper(y, "§7Width", ChatConfig.width + " px", () -> ChatConfig.width = Math.max(160, ChatConfig.width - 20), () -> ChatConfig.width = Math.min(640, ChatConfig.width + 20), mx, my);
        y = stepper(y, "§7Lines while closed", String.valueOf(ChatConfig.linesClosed), () -> ChatConfig.linesClosed = Math.max(3, ChatConfig.linesClosed - 1), () -> ChatConfig.linesClosed = Math.min(30, ChatConfig.linesClosed + 1), mx, my);
        y = stepper(y, "§7Lines while open", String.valueOf(ChatConfig.linesOpen), () -> ChatConfig.linesOpen = Math.max(5, ChatConfig.linesOpen - 1), () -> ChatConfig.linesOpen = Math.min(60, ChatConfig.linesOpen + 1), mx, my);
        y = stepper(y, "§7Background darkness", ChatConfig.opacity + "%", () -> ChatConfig.opacity = Math.max(0, ChatConfig.opacity - 5), () -> ChatConfig.opacity = Math.min(100, ChatConfig.opacity + 5), mx, my);
        y = stepper(y, "§7New lines stay visible for", ChatConfig.fadeSeconds + " s", () -> ChatConfig.fadeSeconds = Math.max(3, ChatConfig.fadeSeconds - 1), () -> ChatConfig.fadeSeconds = Math.min(60, ChatConfig.fadeSeconds + 1), mx, my);
        y = stepper(y, "§7Text size", ChatConfig.scale + "%", () -> ChatConfig.scale = Math.max(50, ChatConfig.scale - 10), () -> ChatConfig.scale = Math.min(200, ChatConfig.scale + 10), mx, my);
        y += 4;

        y = heading(y, "§fEmoji");
        y = toggle(y, "Colour emoji", ":heart: and ❤ show as pictures (1,162 Twemoji)", ChatConfig.emoji, () -> ChatConfig.emoji = !ChatConfig.emoji, mx, my);
        y = stepper(y, "§7Emoji size", ChatConfig.emojiSize + " px", () -> ChatConfig.emojiSize = Math.max(8, ChatConfig.emojiSize - 1), () -> ChatConfig.emojiSize = Math.min(14, ChatConfig.emojiSize + 1), mx, my);
        if (vis(y, 14)) {                                                    // a little preview
            fontRenderer.drawStringWithShadow("§7Preview:", px, y + 3, 0xFFFFFF);
            String[] sample = {"red_heart", "rainbow_flag", "transgender_flag", "sparkles", "smiling_face_with_hearts", "cat", "star_struck", "pizza"};
            int x = px + 60;
            for (String s : sample) { Emoji.E e = Emoji.byCode(s); if (e != null) { Emoji.draw(e, x, y + 2, ChatConfig.emojiSize); x += ChatConfig.emojiSize + 3; } }
        }
        y += 18;

        y = heading(y, "§fMentions");
        y = toggle(y, "Highlight mentions", "lines with your name get a pink edge", ChatConfig.mentions, () -> ChatConfig.mentions = !ChatConfig.mentions, mx, my);
        y = toggle(y, "Mention sound", "a soft ding when someone says your name", ChatConfig.mentionSound, () -> ChatConfig.mentionSound = !ChatConfig.mentionSound, mx, my);
        y = row(y, "§7Also count as a mention: §f" + (ChatConfig.mentionWords.length == 0 ? "§8nothing" : String.join(", ", ChatConfig.mentionWords)), "Change", () -> ask(
                "Words separated by commas (e.g. nickname, admin)", String.join(", ", ChatConfig.mentionWords),
                s -> { List<String> w = new ArrayList<>(); for (String p : s.split(",")) if (!p.trim().isEmpty()) w.add(p.trim()); ChatConfig.mentionWords = w.toArray(new String[0]); }), mx, my);
        y += 4;

        y = heading(y, "§fTabs §8(a line shows in every tab it matches)");
        List<String> tabs = new ArrayList<>(Arrays.asList(ChatConfig.tabList));
        for (int i = 0; i < tabs.size(); i++) {
            final int k = i;
            String[] p = tabs.get(i).split("\\s*\\|\\s*", 3);
            if (p.length < 3) continue;
            if (vis(y, 16)) {
                int col;
                try { col = Integer.parseInt(p[1], 16); } catch (NumberFormatException e) { col = 0xF5A9B8; }
                Gui.drawRect(px, y + 2, px + 10, y + 12, 0xFF000000 | col);
                click(px, y + 2, 10, 10, () -> recolour(k));
                fontRenderer.drawStringWithShadow("§f" + p[0] + " §8· " + fontRenderer.trimStringToWidth(GuiPrideChat.describe(p[2]), pw - 220), px + 14, y + 3, 0xFFFFFF);
                if (mx >= px && mx < px + 10 && my >= y + 2 && my < y + 12) hover = "Click to change the colour";
                int bx = px + pw - 118;
                btn(bx, y, 20, 14, "▲", () -> move(k, -1), mx, my);
                btn(bx + 24, y, 20, 14, "▼", () -> move(k, 1), mx, my);
                btn(bx + 48, y, 30, 14, "✎", () -> editTab(k), mx, my);
                btn(bx + 82, y, 30, 14, "§c✖", () -> { List<String> t = new ArrayList<>(Arrays.asList(ChatConfig.tabList)); if (t.size() > 1) t.remove(k); ChatConfig.tabList = t.toArray(new String[0]); }, mx, my);
            }
            y += 18;
        }
        y = row(y, "§7New tab (name | colour | what it shows)", "§a+ Add", () -> ask("name | hex colour | regex, or @all @player @system @mention",
                "Trade | 7CFC9A | (?i)(buy|sell|trade|wts|wtb)", s -> { if (s.split("\\|").length >= 3) { List<String> t = new ArrayList<>(Arrays.asList(ChatConfig.tabList)); t.add(s.trim()); ChatConfig.tabList = t.toArray(new String[0]); } }), mx, my);
        y = row(y, "§7Put the tabs back the way they came", "Reset", () -> ChatConfig.tabList = new String[]{
                "All | F5A9B8 | @all", "Chat | 5BCEFA | @player", "Town | FFD700 | (?i)^\\[(town|t|nation|n|ally|a|region|r)\\]",
                "Private | B070FF | (?i)(whispers to you|-> ?me\\]|→ ?me|^from |\\[msg\\]|\\bwhispers\\b)", "Mentions | FF8C00 | @mention",
                "Staff | E40303 | (?i)^\\[(staff|helpop|ticket|mod|admin)\\]", "System | 8A8499 | @system"}, mx, my);
        y += 4;

        y = heading(y, "§fIgnored players §8(right-click a message to ignore someone)");
        if (ChatConfig.ignored.length == 0 && vis(y, 12)) { fontRenderer.drawStringWithShadow("§8nobody", px, y + 2, 0xFFFFFF); y += 14; }
        for (String n : ChatConfig.ignored) {
            y = row(y, "§f" + n, "Unignore", () -> { List<String> l = new ArrayList<>(Arrays.asList(ChatConfig.ignored)); l.remove(n); ChatConfig.ignored = l.toArray(new String[0]); }, mx, my);
        }
        y += 4;

        y = heading(y, "§fTools");
        y = row(y, "§7Show a pretend mention (to see how it looks)", "Try it", () -> mc.ingameGUI.getChatGUI().printChatMessage(
                new TextComponentString("<Friend> hey " + (mc.player == null ? "you" : mc.player.getName()) + " look :sparkles: :rainbow_flag: ❤")), mx, my);
        y = row(y, "§7Clear this session's chat", "§cClear", () -> mc.ingameGUI.getChatGUI().clearChatMessages(false), mx, my);

        PrideFrame.unclip();
        contentH = y + scroll - top;
        PrideFrame.scrollbar(px + pw - 3, top, bottom - top, scroll, bottom - top, Math.max(1, contentH));
        if (hover != null) drawHoveringText(Collections.singletonList(hover), mx, my);
    }

    private void move(int k, int d) {
        List<String> t = new ArrayList<>(Arrays.asList(ChatConfig.tabList));
        int j = k + d;
        if (j < 0 || j >= t.size()) return;
        Collections.swap(t, k, j);
        ChatConfig.tabList = t.toArray(new String[0]);
    }

    private void recolour(int k) {
        String[] p = ChatConfig.tabList[k].split("\\s*\\|\\s*", 3);
        int cur;
        try { cur = Integer.parseInt(p[1], 16); } catch (NumberFormatException e) { cur = 0; }
        int i = 0;
        while (i < PALETTE.length && PALETTE[i] != cur) i++;
        int next = PALETTE[(i + 1) % PALETTE.length];
        ChatConfig.tabList[k] = p[0] + " | " + String.format("%06X", next) + " | " + p[2];
        PrideChat.saved();
    }

    private void editTab(int k) {
        ask("name | hex colour | regex, or @all @player @system @mention", ChatConfig.tabList[k], s -> {
            if (s.split("\\|").length >= 3) ChatConfig.tabList[k] = s.trim();
        });
    }

    @Override
    protected void mouseClicked(int mx, int my, int button) throws IOException {
        if (button != 0) return;
        for (int i = boxes.size() - 1; i >= 0; i--) {
            int[] b = boxes.get(i);
            if (mx >= b[0] && mx < b[0] + b[2] && my >= b[1] && my < b[1] + b[3] && my >= top && my < bottom) {
                mc.getSoundHandler().playSound(net.minecraft.client.audio.PositionedSoundRecord.getMasterRecord(net.minecraft.init.SoundEvents.UI_BUTTON_CLICK, 1f));
                clicks.get(i).run();
                return;
            }
        }
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == org.lwjgl.input.Keyboard.KEY_ESCAPE) { mc.displayGuiScreen(back); return; }
        super.keyTyped(c, key);
    }

    @Override
    public void handleMouseInput() throws IOException {
        super.handleMouseInput();
        int d = Integer.signum(Mouse.getEventDWheel());
        if (d != 0) scroll = Math.max(0, Math.min(Math.max(0, contentH - (bottom - top) + 10), scroll - d * 24));
    }
}
