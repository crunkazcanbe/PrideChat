package com.dogpound.pridechat;

import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;
import net.minecraft.util.text.TextFormatting;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.io.IOException;
import java.util.*;

/**
 * The chat screen: Minecraft's chat input (so tab-complete, ↑ history and clicking links all still work) plus a tab
 * strip with unread counts, an emoji picker (groups, search, recent), search inside the tab, a right-click menu on
 * messages (message / mention / pay / tpa / copy / ignore) and ⚙ PrideChat settings.
 */
public class GuiPrideChat extends GuiChat {
    private boolean picker, searching;
    private int pickGroup = -2, pickScroll;                               // -2 = recent, -1 = all
    private GuiTextField pickSearch, searchBox;
    private final List<int[]> boxes = new ArrayList<>();
    private final List<Runnable> clicks = new ArrayList<>();
    private String hover;
    private static final LinkedList<String> RECENT = new LinkedList<>();
    private List<String[]> menu;                                          // {label, action}
    private int menuX, menuY;
    private ChatView.Line menuLine;

    public GuiPrideChat(String start) { super(start); }

    @Override
    public void initGui() {
        super.initGui();
        inputField.width = width - 4 - 66;                                     // room for ☺ ⌕ ⚙
        pickSearch = new GuiTextField(90, fontRenderer, 0, 0, 120, 12);
        pickSearch.setMaxStringLength(30);
        searchBox = new GuiTextField(91, fontRenderer, 0, 0, 140, 12);
        searchBox.setMaxStringLength(40);
        searchBox.setText(ChatView.search);
        searching = !ChatView.search.isEmpty();
    }

    @Override
    public void onGuiClosed() {
        super.onGuiClosed();
        ChatView.search = "";
        ChatView.scroll = 0;
    }

    private void click(int x, int y, int w, int h, Runnable r) { boxes.add(new int[]{x, y, w, h}); clicks.add(r); }

    private boolean btn(int x, int y, int w, int h, String label, int color, Runnable r, int mx, int my) {
        boolean over = PrideFrame.button(x, y, w, h, label, color, mx, my);
        click(x, y, w, h, r);
        return over;
    }

    @Override
    public void drawScreen(int mx, int my, float pt) {
        boxes.clear(); clicks.clear(); hover = null;
        super.drawScreen(mx, my, pt);                                          // input line, link hovers
        // prettier input bar + the three buttons
        Gui.drawRect(2, height - 14, width - 2, height - 13, 0x80F5A9B8);
        int bx = width - 66;
        if (btn(bx, height - 14, 20, 12, "☺", picker ? PrideFrame.TILE_ON : PrideFrame.BUTTON, () -> { picker = !picker; pickSearch.setFocused(picker); }, mx, my)) hover = "Emoji";
        if (btn(bx + 22, height - 14, 20, 12, "⌕", searching ? PrideFrame.TILE_ON : PrideFrame.BUTTON, this::toggleSearch, mx, my)) hover = "Search this tab (Ctrl+F)";
        if (btn(bx + 44, height - 14, 20, 12, "⚙", PrideFrame.BUTTON, () -> mc.displayGuiScreen(new GuiChatSettings(this)), mx, my)) hover = "PrideChat settings";
        if (ChatConfig.enabled) drawTabs(mx, my);
        if (picker) drawPicker(mx, my);
        if (menu != null) drawMenu(mx, my);
        if (hover != null) drawHoveringText(Arrays.asList(hover.split("\n")), mx, my);
    }

    private void toggleSearch() {
        searching = !searching;
        searchBox.setFocused(searching);
        if (!searching) { searchBox.setText(""); ChatView.search = ""; }
        ChatView.scroll = 0;
    }

    // ------------------------------------------------------------------ tabs (+ the search box beside them)
    private void drawTabs(int mx, int my) {
        if (!ChatConfig.tabs && !searching) return;
        int x = ChatView.boxX, y = ChatView.boxY - 14;
        if (ChatView.boxH == 0) y = height - 48 - ChatConfig.linesOpen * 9 - 16;
        if (ChatConfig.tabs)
            for (int i = 0; i < ChatView.TABS.size(); i++) {
                ChatView.Tab t = ChatView.TABS.get(i);
                boolean on = i == ChatView.active;
                String label = t.name + (t.unread > 0 && !on ? " §c" + Math.min(99, t.unread) : "");
                int w = fontRenderer.getStringWidth(label) + 10;
                boolean over = mx >= x && mx < x + w && my >= y && my < y + 12;
                Gui.drawRect(x, y, x + w, y + 12, on ? 0xF06A3FA0 : over ? 0xE02A2140 : 0xC01C1530);
                Gui.drawRect(x, y, x + w, y + 1, 0xFF000000 | t.color);
                fontRenderer.drawStringWithShadow(label, x + 5, y + 2, on ? 0xFFFFFF : 0xC8C0D8);
                final int k = i;
                click(x, y, w, 12, () -> { ChatView.active = k; ChatView.TABS.get(k).unread = 0; ChatView.scroll = 0; });
                if (over) hover = t.name + "\n§7shows: " + describe(t.rule) + "\n§8⚙ settings to add or change tabs";
                x += w + 2;
            }
        if (searching) {
            searchBox.x = Math.max(x + 6, ChatView.boxX + ChatView.boxW - 140);
            searchBox.y = y;
            searchBox.drawTextBox();
            if (searchBox.getText().isEmpty() && !searchBox.isFocused()) fontRenderer.drawString("§8search…", searchBox.x + 4, y + 2, 0xFFFFFF);
        }
    }

    static String describe(String rule) {
        switch (rule) {
            case "@all": return "everything";
            case "@player": return "players talking";
            case "@system": return "server and mod messages";
            case "@mention": return "lines that mention you";
            default: return "lines matching " + rule;
        }
    }

    // ------------------------------------------------------------------ emoji picker
    private static final int CELL = 16;

    private void drawPicker(int mx, int my) {
        int cols = 12, rows = 8, pw = cols * CELL + 8, ph = rows * CELL + 44;
        int px = width - pw - 4, py = height - 18 - ph;
        Gui.drawRect(px, py, px + pw, py + ph, 0xF0140E22);
        int sw = pw / PrideFrame.RAINBOW.length;
        for (int i = 0; i < PrideFrame.RAINBOW.length; i++) Gui.drawRect(px + i * sw, py, i == PrideFrame.RAINBOW.length - 1 ? px + pw : px + (i + 1) * sw, py + 2, PrideFrame.RAINBOW[i]);
        // group icons: recent, then each group's first emoji
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
        pickSearch.x = px + 4; pickSearch.y = py + 24; pickSearch.width = pw - 8;
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
            Emoji.E e = list.get(i);
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
        if (l.sender != null && (mc.player == null || !l.sender.equalsIgnoreCase(mc.player.getName()))) {
            String n = l.sender;
            menu.add(new String[]{"✉ Message " + n, "suggest:/msg " + n + " "});
            menu.add(new String[]{"@ Mention " + n, "insert:@" + n + " "});
            menu.add(new String[]{"$ Pay " + n + "…", "suggest:/pay " + n + " "});
            menu.add(new String[]{"➤ Ask to teleport to " + n, "run:/tpa " + n});
            menu.add(new String[]{"⧉ Copy name", "copy:" + n});
            menu.add(new String[]{ChatView.ignored(n) ? "☺ Stop ignoring " + n : "✖ Ignore " + n, "ignore:" + n});
        }
        menu.add(new String[]{"⧉ Copy message", "copy:" + l.plain});
        menu.add(new String[]{"✖ Close", "close:"});
        menuX = x; menuY = y;
    }

    private void drawMenu(int mx, int my) {
        int w = 0;
        for (String[] m : menu) w = Math.max(w, fontRenderer.getStringWidth(m[0]) + 16);
        int h = menu.size() * 14 + 4, x = Math.min(menuX, width - w - 2), y = Math.max(2, Math.min(menuY, height - h - 16));
        Gui.drawRect(x, y, x + w, y + h, 0xF0140E22);
        Gui.drawRect(x, y, x + w, y + 1, 0xFFF5A9B8);
        for (int i = 0; i < menu.size(); i++) {
            String[] m = menu.get(i);
            int ry = y + 2 + i * 14;
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
            case "run": sendChatMessage(v, false); break;
            case "copy": GuiScreen.setClipboardString(v); break;
            case "ignore": {
                List<String> list = new ArrayList<>(Arrays.asList(ChatConfig.ignored));
                if (!list.removeIf(s -> s.equalsIgnoreCase(v))) list.add(v);
                ChatConfig.ignored = list.toArray(new String[0]);
                PrideChat.saved();
                break;
            }
            default: break;
        }
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
            if (picker) { pickSearch.mouseClicked(mx, my, button); }
            if (searching) { searchBox.mouseClicked(mx, my, button); if (searchBox.isFocused()) return; }
        }
        if (button == 1 && ChatConfig.enabled) {                              // right-click a message
            ChatView.Line l = ChatView.lineAt(mx, my);
            if (l != null) { openMenu(l, mx, my); return; }
            menu = null;
        }
        super.mouseClicked(mx, my, button);
    }

    @Override
    protected void keyTyped(char c, int key) throws IOException {
        if (key == Keyboard.KEY_ESCAPE && (menu != null || picker)) { menu = null; picker = false; return; }
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
        super.keyTyped(c, key);
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
}
