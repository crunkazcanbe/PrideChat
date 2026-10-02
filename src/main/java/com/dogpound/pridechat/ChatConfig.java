package com.dogpound.pridechat;

import net.minecraftforge.common.config.Config;

/** config/pridechat.cfg — every option is also in the chat's ⚙ settings menu */
@Config(modid = PrideChat.MODID, name = "pridechat")
public final class ChatConfig {
    @Config.Comment("Use PrideChat's chat (off = Minecraft's normal chat).")
    public static boolean enabled = true;

    @Config.Comment("Chat width in pixels (GUI scale).") @Config.RangeInt(min = 160, max = 640)
    public static int width = 330;

    @Config.Comment("How many lines show while chat is closed.") @Config.RangeInt(min = 3, max = 30)
    public static int linesClosed = 10;

    @Config.Comment("How many lines show while chat is open.") @Config.RangeInt(min = 5, max = 60)
    public static int linesOpen = 20;

    @Config.Comment("Background darkness, 0-100.") @Config.RangeInt(min = 0, max = 100)
    public static int opacity = 55;

    @Config.Comment("Seconds a new line stays visible while chat is closed.") @Config.RangeInt(min = 3, max = 60)
    public static int fadeSeconds = 10;

    @Config.Comment("Show the time in front of each line.")
    public static boolean timestamps = true;

    @Config.Comment("Timestamp format (Java SimpleDateFormat).")
    public static String timeFormat = "HH:mm";

    @Config.Comment("Draw emoji as colour pictures (shortcodes like :heart: and real emoji characters).")
    public static boolean emoji = true;

    @Config.Comment("Emoji size in pixels (line height is 9).") @Config.RangeInt(min = 8, max = 14)
    public static int emojiSize = 9;

    @Config.Comment("Highlight lines that mention you (your name or a word below).")
    public static boolean mentions = true;

    @Config.Comment("Play a sound when someone mentions you.")
    public static boolean mentionSound = true;

    @Config.Comment("Extra words that count as a mention (besides your name).")
    public static String[] mentionWords = {};

    @Config.Comment("Show the tab strip.")
    public static boolean tabs = true;

    @Config.Comment("Tabs: name | colour (hex) | regex a line must match (on the plain text). Special regexes: @player = a player's message, "
            + "@system = not a player's message, @mention = mentions you, @all = everything.")
    public static String[] tabList = {
            "All | F5A9B8 | @all",
            "Chat | 5BCEFA | @player",
            "Town | FFD700 | (?i)^\\[(town|t|nation|n|ally|a|region|r)\\]",
            "Private | B070FF | (?i)(whispers to you|-> ?me\\]|→ ?me|^from |\\[msg\\]|\\bwhispers\\b)",
            "Mentions | FF8C00 | @mention",
            "Staff | E40303 | (?i)^\\[(staff|helpop|ticket|mod|admin)\\]",
            "System | 8A8499 | @system"};

    @Config.Comment("Players whose messages you don't want to see (hidden, not deleted — clear the list to see them again).")
    public static String[] ignored = {};

    @Config.Comment("Scale of the chat text, percent.") @Config.RangeInt(min = 50, max = 200)
    public static int scale = 100;

    private ChatConfig() {}
}
