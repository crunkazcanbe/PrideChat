package com.dogpound.pridechat;

import net.minecraftforge.common.config.Config;

/**
 * config/pridechat.cfg — every client option is also in the chat's ⚙ settings (Options → PrideChat…).
 * Adding an option = one field here + one line on a page in GuiChatSettings (it reads the range from @RangeInt).
 * "server" holds what the SERVER enforces (in singleplayer you edit it from the Server page too).
 */
@Config(modid = PrideChat.MODID, name = "pridechat")
public final class ChatConfig {
    // ---------------------------------------------------------------- general
    @Config.Comment("Use PrideChat's chat (off = Minecraft's normal chat).")
    public static boolean enabled = true;
    @Config.Comment("Show the tab strip.")
    public static boolean tabs = true;
    @Config.Comment("Longest message you can type (servers with PrideChat take it all; others get it split).") @Config.RangeInt(min = 100, max = 100000)
    public static int maxLength = 32000;
    @Config.Comment("On a server without PrideChat: split long messages into 256-letter pieces (off = refuse and warn).")
    public static boolean splitOnVanilla = true;
    @Config.Comment("Ticks between split pieces (20 = 1 s; too fast gets you kicked for spam).") @Config.RangeInt(min = 5, max = 100)
    public static int splitDelayTicks = 20;
    @Config.Comment("Most pieces one message may be split into on a server without PrideChat.") @Config.RangeInt(min = 1, max = 50)
    public static int maxSplitPieces = 10;
    @Config.Comment("Lines kept in memory.") @Config.RangeInt(min = 100, max = 20000)
    public static int historyLines = 2500;
    @Config.Comment("Keep what you typed when you close the chat without sending.")
    public static boolean keepDraft = true;
    @Config.Comment("Remember the messages you sent (↑ history) between game sessions.")
    public static boolean saveInputHistory = true;

    // ---------------------------------------------------------------- range (local / whisper / shout / global)
    @Config.Comment("How your messages travel by default: whisper, local, shout or global.")
    public static String sendMode = "local";
    @Config.Comment("Whisper reaches this many blocks.") @Config.RangeInt(min = 1, max = 64)
    public static int whisperRange = 8;
    @Config.Comment("Local chat reaches this many blocks.") @Config.RangeInt(min = 4, max = 512)
    public static int localRange = 48;
    @Config.Comment("Shouting reaches this many blocks.") @Config.RangeInt(min = 8, max = 2048)
    public static int shoutRange = 160;
    @Config.Comment("Start a message with this to send it to everyone.")
    public static String globalPrefix = "!";
    @Config.Comment("Start a message with this to shout.")
    public static String shoutPrefix = "^";
    @Config.Comment("Start a message with this to whisper.")
    public static String whisperPrefix = "~";

    // ---------------------------------------------------------------- look
    @Config.Comment("Chat width in pixels (GUI scale).") @Config.RangeInt(min = 160, max = 900)
    public static int width = 330;
    @Config.Comment("How many lines show while chat is closed.") @Config.RangeInt(min = 1, max = 40)
    public static int linesClosed = 10;
    @Config.Comment("How many lines show while chat is open.") @Config.RangeInt(min = 5, max = 80)
    public static int linesOpen = 20;
    @Config.Comment("Move the chat box: pixels right of where it normally sits.") @Config.RangeInt(min = -4000, max = 4000)
    public static int offsetX = 0;
    @Config.Comment("Move the chat box: pixels up from where it normally sits.") @Config.RangeInt(min = -4000, max = 4000)
    public static int offsetY = 0;
    @Config.Comment("Background darkness, 0-100.") @Config.RangeInt(min = 0, max = 100)
    public static int opacity = 55;
    @Config.Comment("Background style: glass, solid, pride, none.")
    public static String background = "glass";
    @Config.Comment("Background colour (hex).")
    public static String backgroundColor = "140E22";
    @Config.Comment("Rounded corners on the chat box.")
    public static boolean rounded = true;
    @Config.Comment("Rainbow edge on top of the chat box: open, always, never.")
    public static String rainbowEdge = "open";
    @Config.Comment("Scale of the chat text, percent.") @Config.RangeInt(min = 50, max = 200)
    public static int scale = 100;
    @Config.Comment("Extra pixels between lines.") @Config.RangeInt(min = 0, max = 8)
    public static int lineSpacing = 0;
    @Config.Comment("Shadow under the text.")
    public static boolean textShadow = true;
    @Config.Comment("Seconds a new line stays visible while chat is closed.") @Config.RangeInt(min = 1, max = 120)
    public static int fadeSeconds = 10;
    @Config.Comment("How lines leave while chat is closed: fade, instant, never.")
    public static String fadeStyle = "fade";
    @Config.Comment("New lines slide in.")
    public static boolean slideIn = true;
    @Config.Comment("How new chat lines arrive: none, fade, slide, pop, bounce, typewriter, scale, dissolve, enchant, hologram, float.")
    public static String lineAnimation = "fade";
    @Config.Comment("Animation speed, percent (200 = twice as fast).") @Config.RangeInt(min = 10, max = 500)
    public static int animSpeed = 100;
    @Config.Comment("Compact: no heads, no gaps, short timestamps.")
    public static boolean compact = false;
    @Config.Comment("Unread counts on the tabs.")
    public static boolean unreadBadges = true;
    @Config.Comment("Show the player's face in front of their lines.")
    public static boolean chatHeads = true;
    @Config.Comment("Show the time in front of each line.")
    public static boolean timestamps = true;
    @Config.Comment("Timestamp format (Java SimpleDateFormat), or 'relative' for '2m ago'.")
    public static String timeFormat = "HH:mm";
    @Config.Comment("Timestamp colour (hex).")
    public static String timeColor = "6E6680";
    @Config.Comment("Tint the lines you sent.")
    public static boolean tintOwn = false;
    @Config.Comment("Colour for your own lines' tint (hex).")
    public static String ownColor = "5BCEFA";
    @Config.Comment("Show a '↓ new messages' button when you've scrolled up.")
    public static boolean jumpButton = true;

    // ---------------------------------------------------------------- input
    @Config.Comment("Show a preview of your message (with emoji) above the input box.")
    public static boolean inputPreview = true;
    @Config.Comment("Show how many letters you've typed.")
    public static boolean charCounter = true;
    @Config.Comment(":shortcode suggestions while typing (Tab picks).")
    public static boolean emojiAutocomplete = true;
    @Config.Comment("Shift+Enter makes a new line (⏎) in your message.")
    public static boolean multiLine = true;
    @Config.Comment("The format buttons (colours, bold, rainbow…) next to the input box. The server turns &codes into colours.")
    public static boolean formatButtons = true;
    @Config.Comment("Red wavy line under words the dictionary doesn't know.")
    public static boolean spellCheck = true;
    @Config.Comment("Words the spell check should accept.")
    public static String[] myWords = {};
    @Config.Comment("Turn whole messages in CAPS into lower case (other people's).")
    public static boolean calmCaps = false;

    // ---------------------------------------------------------------- emoji
    @Config.Comment("Draw emoji as colour pictures (shortcodes like :heart: and real emoji characters).")
    public static boolean emoji = true;
    @Config.Comment("Emoji size in pixels (line height is 9).") @Config.RangeInt(min = 8, max = 14)
    public static int emojiSize = 9;
    @Config.Comment("Skin tone for hand + people emoji you pick: 0 default, 1 light … 5 dark.") @Config.RangeInt(min = 0, max = 5)
    public static int skinTone = 0;
    @Config.Comment("Messages that are only emoji show bigger.")
    public static boolean bigEmoji = true;

    // ---------------------------------------------------------------- mentions, notifications, sounds
    @Config.Comment("Highlight lines that mention you (your name or a word below).")
    public static boolean mentions = true;
    @Config.Comment("Mention highlight colour (hex).")
    public static String mentionColor = "F5A9B8";
    @Config.Comment("Extra words that count as a mention (besides your name).")
    public static String[] mentionWords = {};
    @Config.Comment("Blink the game in the taskbar when you're mentioned or get a private message while it's in the background.")
    public static boolean flashOnMention = true;
    @Config.Comment("Do not disturb: no sounds, no pop-ups.")
    public static boolean dnd = false;
    @Config.Comment("Show 'Name talking 43m ↗' at the top for players talking far away or behind you (roadmap #15).")
    public static boolean talkIndicator = true;
    @Config.Comment("…when they're at least this far (or behind you).") @Config.RangeInt(min = 0, max = 1000)
    public static int talkIndicatorDistance = 16;
    @Config.Comment("Seconds the talking indicator stays.") @Config.RangeInt(min = 1, max = 30)
    public static int talkIndicatorSeconds = 5;
    @Config.Comment("Pop-up when someone mentions you while chat is closed.")
    public static boolean toastMentions = true;
    @Config.Comment("Pop-up for private messages.")
    public static boolean toastDMs = true;
    @Config.Comment("Pop-up when a friend joins or leaves.")
    public static boolean toastFriends = true;
    @Config.Comment("Seconds a pop-up stays.") @Config.RangeInt(min = 2, max = 30)
    public static int toastSeconds = 5;
    @Config.Comment("Sound for a mention: none, ding, bell, chime, pop, xylophone, flute, guitar, harp, xp.")
    public static String mentionSound = "ding";
    @Config.Comment("Sound for a private message.")
    public static String dmSound = "chime";
    @Config.Comment("Sound for any new message (usually none).")
    public static String messageSound = "none";
    @Config.Comment("Sound when you send.")
    public static String sendSound = "none";
    @Config.Comment("Sound when a friend joins.")
    public static String friendSound = "bell";
    @Config.Comment("Volume of PrideChat sounds, percent.") @Config.RangeInt(min = 0, max = 100)
    public static int volume = 70;

    // ---------------------------------------------------------------- tabs
    @Config.Comment("Tabs: name | colour | rule | sound | flags. Rules: a regex on the plain text, or @all @player @system @mention @local @global "
            + "@dm (all private) @dm:Name #channel. Flags: m = muted (no unread/sound), n = pop-up for new lines, s = sends to that channel.")
    public static String[] tabList = defaultTabs();
    @Config.Comment("Kinds hidden per tab (the ☰ filter in the open chat): rule | local,whisper,shout,emote,global,channel,dm,radio,zone,dim,announce,bot,system")
    public static String[] tabFilters = {};
    @Config.Comment("Extra chat windows: rule | x | y | width | lines (right-click a tab → Pop out; drag the header while chat is open).")
    public static String[] windows = {};
    @Config.Comment("A tab opens by itself for every server channel you're in (groups always get one).")
    public static boolean channelTabs = false;
    @Config.Comment("A private-message tab opens by itself for each person who messages you.")
    public static boolean dmTabs = true;
    @Config.Comment("Your server's private message command (without /).")
    public static String msgCommand = "msg";

    static String[] defaultTabs() {
        return new String[]{
                "All | F5A9B8 | @all | none | ",
                "Local | 7CFC9A | @local | none | ",
                "Global | 5BCEFA | @global | none | ",
                "Private | B070FF | @dm | none | ",
                "Town | FFD700 | (?i)^\\[(town|t|nation|n|ally|a|region|r)\\] | none | ",
                "Mentions | FF8C00 | @mention | none | ",
                "Staff | E40303 | #staff | none | ",
                "System | 8A8499 | @system | none | m"};
    }

    // ---------------------------------------------------------------- people
    @Config.Comment("Players whose messages, bubbles and private messages you don't want (hidden, not deleted).")
    public static String[] ignored = {};
    @Config.Comment("Muted players: their lines stay but grey and quiet (no sounds, pop-ups, pings, bubbles, typing dots).")
    public static String[] muted = {};
    @Config.Comment("Your friends (online dot, join/leave pop-ups, their lines can be tinted).")
    public static String[] friends = {};
    @Config.Comment("Reply to private messages automatically.")
    public static boolean autoReply = false;
    @Config.Comment("The automatic reply.")
    public static String autoReplyText = "I'm away right now — I'll answer later :heart:";
    @Config.Comment("Only auto-reply while you're AFK.")
    public static boolean autoReplyOnlyAfk = true;
    @Config.Comment("Minutes without moving before you count as AFK (0 = never).") @Config.RangeInt(min = 0, max = 120)
    public static int afkMinutes = 5;
    @Config.Comment("Tell people you're typing (dots above your head) — PrideChat servers only.")
    public static boolean sendTyping = true;
    @Config.Comment("Show when others are typing.")
    public static boolean showTyping = true;
    @Config.Comment("Send how fast I type (my typing dots speed up for others) — off = the speed is never sent.")
    public static boolean sendTypingSpeed = true;
    @Config.Comment("Show others when I'm in a menu / chest / crafting (a little holo screen in front of me) — off = never sent.")
    public static boolean sendGui = true;
    @Config.Comment("Show others when I'm AFK (I fall asleep: head droops, Zz) — off = never sent. Uses afkMinutes (the server may set its own).")
    public static boolean sendIdle = true;
    @Config.Comment("A chat line when someone falls asleep (AFK) or wakes up.")
    public static boolean idleNotices = false;
    @Config.Comment("Tell people you read their private messages (✓✓).")
    public static boolean sendReadReceipts = true;

    // ---------------------------------------------------------------- bubbles & heads
    @Config.Comment("Speech bubbles above players who talk.")
    public static boolean bubbles = true;
    @Config.Comment("The talker's skin head floats above them with the bubble.")
    public static boolean bubbleHead = true;
    @Config.Comment("Float everyone's head above them all the time.")
    public static boolean headAlways = false;
    @Config.Comment("Your own bubble (third person).")
    public static boolean bubbleOwn = true;
    @Config.Comment("Seconds a bubble stays (plus a little per letter).") @Config.RangeInt(min = 2, max = 60)
    public static int bubbleSeconds = 6;
    @Config.Comment("Bubbles show for players up to this many blocks away.") @Config.RangeInt(min = 4, max = 128)
    public static int bubbleDistance = 32;
    @Config.Comment("Bubble width in pixels.") @Config.RangeInt(min = 60, max = 400)
    public static int bubbleWidth = 160;
    @Config.Comment("Most text lines in a bubble.") @Config.RangeInt(min = 1, max = 12)
    public static int bubbleMaxLines = 4;
    @Config.Comment("Most bubbles stacked above one player.") @Config.RangeInt(min = 1, max = 5)
    public static int bubbleStack = 2;
    @Config.Comment("Bubble background opacity, 0-100.") @Config.RangeInt(min = 0, max = 100)
    public static int bubbleOpacity = 80;
    @Config.Comment("Bubble size, percent.") @Config.RangeInt(min = 50, max = 300)
    public static int bubbleScale = 100;
    @Config.Comment("Bubbles show through walls.")
    public static boolean bubbleSeeThrough = false;
    @Config.Comment("YOUR bubble style (others with PrideChat see it): rounded, square, mc, comic, speech, thought, dark, transparent, glass, pixel, holo, minimal.")
    public static String bubbleStyle = "rounded";
    @Config.Comment("Other people's bubbles: 'theirs' (the style they picked) or one style for everyone.")
    public static String bubbleStyleOthers = "theirs";
    @Config.Comment("The floating head is a real 3D head that turns (off = a flat face).")
    public static boolean head3d = true;
    @Config.Comment("Where floating heads look: smart (at whoever is talking to them, or at a listener while they talk), you, forward.")
    public static String headLook = "smart";
    @Config.Comment("Shake my screen a little when someone near me /emote screams (off = never).")
    public static boolean screenShake = true;
    @Config.Comment("Players whose emotes (lines, bubbles, screen shakes) you don't want.")
    public static String[] hiddenEmotes = {};
    @Config.Comment("Players whose chat lines you hide (their bubbles still show — ignore hides everything).")
    public static String[] hiddenChat = {};
    @Config.Comment("Players whose typing dots you don't want.")
    public static String[] hiddenTyping = {};
    @Config.Comment("Typing dots above players who are typing.")
    public static boolean typingBubble = true;
    @Config.Comment("Typing dots get faster and wackier (bouncy, shaky, rainbow) the faster they really type.")
    public static boolean typingWacky = true;
    @Config.Comment("A little holo screen in front of players who are in a menu (inventory, chest, crafting, furnace, modded).")
    public static boolean showGuiHolo = true;
    @Config.Comment("Their arm taps at the holo screen.")
    public static boolean guiArmSwing = true;
    @Config.Comment("AFK players fall asleep: head droops, Zz float up.")
    public static boolean showSleep = true;
    @Config.Comment("Zz next to AFK players in the tab list.")
    public static boolean sleepTabList = true;

    // ---------------------------------------------------------------- my look (roadmap #7) — others with PrideChat see it, the server limits it
    @Config.Comment("My bubble's colour (hex, empty = the style's own).")
    public static String myBubbleColor = "";
    @Config.Comment("My bubble's border colour (hex, empty = none / the style's own).")
    public static String myBubbleBorder = "";
    @Config.Comment("My bubble's border thickness.") @Config.RangeInt(min = 0, max = 4)
    public static int myBubbleBorderSize = 1;
    @Config.Comment("My bubble's opacity, percent (the server may set a minimum).") @Config.RangeInt(min = 0, max = 100)
    public static int myBubbleAlpha = 80;
    @Config.Comment("Seconds my bubbles stay (0 = each viewer's own setting; the server caps it).") @Config.RangeInt(min = 0, max = 60)
    public static int myBubbleSeconds = 0;
    @Config.Comment("How my bubbles appear for others (empty = their own setting): fade, slide, pop, bounce, typewriter, scale, dissolve, enchant, hologram, float.")
    public static String myBubbleAnimation = "";
    @Config.Comment("My floating head's size, percent.") @Config.RangeInt(min = 50, max = 300)
    public static int myHeadSize = 100;
    @Config.Comment("A frame around my floating head (hex, empty = none).")
    public static String myHeadBorder = "";
    @Config.Comment("My name's colour in chat (hex → nearest chat colour; needs permission pridechat.appearance.colors).")
    public static String myNameColor = "";
    @Config.Comment("My messages' colour (hex → nearest chat colour; needs permission pridechat.appearance.colors).")
    public static String myMessageColor = "";
    @Config.Comment("The colour of the [L] / [#channel] tag on my messages (needs permission pridechat.appearance.colors).")
    public static String myChannelColor = "";
    @Config.Comment("Show other players' own bubble colours, heads and animations (off = everyone looks like your settings).")
    public static boolean othersLook = true;

    // ---------------------------------------------------------------- filters
    @Config.Comment("Hide 'joined/left the game' lines.")
    public static boolean hideJoinLeave = false;
    @Config.Comment("Hide death messages.")
    public static boolean hideDeaths = false;
    @Config.Comment("Hide advancement messages.")
    public static boolean hideAdvancements = false;
    @Config.Comment("Same message twice in a row becomes one line with ×2.")
    public static boolean collapseDuplicates = true;
    @Config.Comment("Hide a player repeating the same message within 30 s.")
    public static boolean spamRepeat = true;
    @Config.Comment("Hide a player's lines past this many in 5 seconds (0 = off).") @Config.RangeInt(min = 0, max = 30)
    public static int floodLimit = 6;
    @Config.Comment("Word filter on.")
    public static boolean wordFilter = false;
    @Config.Comment("Word filter: censor (****) or hide the line.")
    public static String wordFilterMode = "censor";
    @Config.Comment("Words the filter catches.")
    public static String[] filterWords = {"fuck", "shit", "bitch", "cunt", "asshole"};
    @Config.Comment("Command used by 'Report' in the right-click menu ({name} and {msg} are filled in; empty = copy instead).")
    public static String reportCommand = "";

    // ---------------------------------------------------------------- tools
    @Config.Comment("Write the chat to logs/pridechat/<date>.log.")
    public static boolean logToFile = true;
    @Config.Comment("Middle-click a line to copy it.")
    public static boolean middleClickCopy = true;
    @Config.Comment("Players named in the server list are clickable to mention them (shift-click a name).")
    public static boolean clickNames = true;

    // ---------------------------------------------------------------- server side
    @Config.Comment("What a server with PrideChat enforces (its own pridechat.cfg).")
    public static final Server server = new Server();

    public static final class Server {
        @Config.Comment("Longest message the server accepts.") @Config.RangeInt(min = 256, max = 100000)
        public int maxLength = 32000;
        @Config.Comment("Furthest any local/shout message may reach.") @Config.RangeInt(min = 4, max = 10000)
        public int maxRange = 2048;
        @Config.Comment("Players may send to everyone (off = local only, ops still can).")
        public boolean allowGlobal = true;
        @Config.Comment("Turn &-colour codes into colours (needs permission pridechat.color, everyone by default).")
        public boolean allowColors = true;
        @Config.Comment("[item] / [hand] in a message shows the held item (hover for its tooltip).")
        public boolean itemLinks = true;
        @Config.Comment("Tell the sender when nobody was close enough to hear.")
        public boolean tellNobodyHeard = true;
        @Config.Comment("Tags in front of ranged messages.")
        public String whisperTag = "&8[W] ";
        public String localTag = "&a[L] ";
        public String shoutTag = "&6[S] ";
        public String channelTag = "&d[#%s] ";
        @Config.Comment("Seconds between messages for non-ops (0 = no slow mode). /chatslow changes it live.") @Config.RangeInt(min = 0, max = 600)
        public int slowMode = 0;
        @Config.Comment("Most messages per 5 s before they're dropped (spam guard).") @Config.RangeInt(min = 1, max = 100)
        public int floodLimit = 8;
        @Config.Comment("'What are they up to' (0.4.1): players see who is in a menu (holo screen + tapping arm).")
        public boolean activityGui = true;
        @Config.Comment("…AFK players fall asleep (head droops, Zz above them and in the tab list).")
        public boolean activityIdle = true;
        @Config.Comment("…typing dots speed up with how fast someone types.")
        public boolean activityTypingSpeed = true;
        @Config.Comment("Minutes without moving before a player falls asleep (0 = each player's own AFK time).") @Config.RangeInt(min = 0, max = 120)
        public int idleMinutes = 0;
        @Config.Comment("Lines sent to each player when they join (empty = none). & colours allowed.")
        public String[] rules = {};
        @Config.Comment("Keep mail for offline players.")
        public boolean mail = true;
        @Config.Comment("Most mails kept per player.") @Config.RangeInt(min = 1, max = 500)
        public int mailMax = 50;
        @Config.Comment("PrideChat's /msg (and /pm) with selectors @nearest @here @all @team @party @radius N, ✓✓ and mail (roadmap #45). /tell and /w stay vanilla.")
        public boolean takeOverMsg = true;
        @Config.Comment("Text effects [glow] [shadow] [color] [type] in front of a message (roadmap #47; permission pridechat.effects).")
        public boolean textEffects = true;
        @Config.Comment("Which effects are allowed: glow, shadow, color (animated colours), type (typewriter).")
        public String[] allowedEffects = {"glow", "shadow", "color", "type"};
        @Config.Comment("Cross-server chat through a relay (roadmap #39, docs/CROSS-SERVER.md). Off by default.")
        public boolean crossServer = false;
        @Config.Comment("The relay's address (tools/pridechat-relay.py), e.g. http://10.0.0.5:8765")
        public String relayUrl = "";
        @Config.Comment("This server's name on the other servers ([Name] in front of its lines).")
        public String serverName = "Pride";
        @Config.Comment("Shared secret — the same on the relay and every server.")
        public String relaySecret = "";
        @Config.Comment("What crosses: global (everyone-chat) and channel names.")
        public String[] crossChannels = {"global", "trade"};
        @Config.Comment("Tag in front of lines from another server (%s = its name).")
        public String crossTag = "&9[%s] ";
        @Config.Comment("Announcement style for /announce.")
        public String announceTag = "&d&l✦ &f";
        @Config.Comment("Text fades and breaks up near the edge of the range (clear → faded → faint).")
        public boolean falloff = true;
        @Config.Comment("Walls muffle ranged chat.")
        public boolean occlusion = true;
        @Config.Comment("Percent of range each solid block in the way takes off.") @Config.RangeInt(min = 0, max = 90)
        public int wallDamping = 30;
        @Config.Comment("Percent of range each floor / ceiling in the way takes off (sound goes through walls easier than floors).") @Config.RangeInt(min = 0, max = 90)
        public int floorDamping = 45;
        @Config.Comment("Percent further a voice carries along a clear tunnel or under a roof (both people under cover, nothing in between).") @Config.RangeInt(min = 0, max = 300)
        public int undergroundBonus = 40;
        @Config.Comment("People standing at linked Nether portals hear each other across the Overworld/Nether (roadmap #38).")
        public boolean portalChat = true;
        @Config.Comment("How close to a portal you must stand to talk / hear through it.") @Config.RangeInt(min = 1, max = 16)
        public int portalHearRange = 6;
        @Config.Comment("Two portals count as linked when they're within this many Nether blocks of each other (vanilla links within 16).") @Config.RangeInt(min = 1, max = 128)
        public int portalLinkRange = 16;
        @Config.Comment("Per-dimension sound: dimension id | range multiplier (0.75 = voices carry 3/4 as far).")
        public String[] dimensionRules = {"-1 | 0.75", "1 | 1.5"};
        @Config.Comment("Players may pick their own bubble colours, head size, name/message colours… (roadmap #7; permissions pridechat.appearance + pridechat.appearance.colors).")
        public boolean appearance = true;
        @Config.Comment("Longest a player may make their bubbles stay, seconds.") @Config.RangeInt(min = 2, max = 60)
        public int maxBubbleSeconds = 20;
        @Config.Comment("Least opaque a player's bubble may be, percent.") @Config.RangeInt(min = 0, max = 100)
        public int minBubbleAlpha = 25;
        @Config.Comment("Biggest floating head a player may pick, percent.") @Config.RangeInt(min = 50, max = 300)
        public int maxHeadSize = 200;
        @Config.Comment("Public lines a joining player gets to scroll back through.") @Config.RangeInt(min = 0, max = 2000)
        public int historyKeep = 100;
        @Config.Comment("How long the server keeps public chat on disk (world/pridechat-history.dat): off, 1h, 1d, 7d, 30d, forever.")
        public String historyRetention = "7d";
        @Config.Comment("Most lines kept on disk.") @Config.RangeInt(min = 0, max = 200000)
        public int historyStore = 5000;
        @Config.Comment("Channels: name | tag (%s = name) | permission (empty = anyone) | range (0 = everywhere) | open/invite. "
                + "Players make their own groups with /party.")
        public String[] channels = {"staff | &c[Staff] | pridechat.channel.staff | 0 | open", "trade | &e[Trade] |  | 0 | open",
                "help | &b[Help] |  | 0 | open", "rp | &5[RP] |  | 48 | open"};
        @Config.Comment("Auto-responders: regex | reply ({name} = who said it). A reply starting with / runs as a command.")
        public String[] bots = {"(?i)^!rules$ | Be kind, no griefing, have fun {name} :heart:", "(?i)^!help$ | Try /ch list, /party, /mail and /ask <question>"};
        @Config.Comment("Whispers show in italics, shouts in bold (roadmap #20).")
        public boolean rangeStyle = true;
        @Config.Comment("Whispering while looking at someone close whispers to just them (roadmap #21).")
        public boolean whisperFacing = true;
        @Config.Comment("How close they must be for a facing whisper.") @Config.RangeInt(min = 1, max = 16)
        public int whisperFacingRange = 3;
        @Config.Comment("Sneaking turns local chat into a whisper (roadmap #25).")
        public boolean sneakWhisper = false;
        @Config.Comment("*text between stars* shows in italics (roleplay, roadmap #24).")
        public boolean rpFormat = true;
        @Config.Comment("Channels that force a roleplay style: channel | chat/actions/novel/script.")
        public String[] rpModeChannels = {"rp | novel"};
        @Config.Comment("Context-aware chat (roadmap #25): what changes how you sound — underwater (blub), fire, sprint, riding, hurt. Remove one to turn it off.")
        public String[] contextEffects = {"underwater", "fire", "sprint", "riding", "hurt"};
        @Config.Comment("Particles for /emote words like hug, dance, laugh (roadmap #48).")
        public boolean emoteParticles = true;
        @Config.Comment("What /emote actions do (roadmap #23/#48): regex | effects. Effects: wave, clap, point, dance, swing, "
                + "particles:heart|note|happy|angry|splash|cloud|magic|flame, shake:N (listeners' screens), tiny, giant (bubble), range:whisper|shout.")
        public String[] emoteEffects = {"\\bwaves?\\b | wave", "\\bclaps?\\b | clap", "\\bpoints?\\b | point", "\\b(dances?|vibes?)\\b | dance",
                "\\b(laughs?|giggles?|cheers?|celebrates?)\\b | particles:happy", "\\b(hugs?|loves?|kiss(es)?|cuddles?)\\b | particles:heart, swing",
                "\\bsings?\\b | particles:note", "\\b(rages?|angry|fumes|stomps?)\\b | particles:angry", "\\b(cr(y|ies)|sobs?)\\b | particles:splash",
                "\\b(sneezes?|coughs?|sighs?)\\b | particles:cloud", "\\b(casts?|magic|enchants?)\\b | particles:magic, swing",
                "\\b(screams?|roars?|shrieks?)\\b | shake:8, giant, range:shout", "\\b(shouts?|yells?)\\b | giant, range:shout",
                "\\b(whispers?|murmurs?|mutters?)\\b | tiny, range:whisper"};
        @Config.Comment("Name the helper bot uses.")
        public String botName = "Pride";
        @Config.Comment("Rule-based moderation on.")
        public boolean moderation = true;
        @Config.Comment("Ops skip moderation.")
        public boolean opsExempt = true;
        @Config.Comment("What each kind of problem does: category | allow/soften/hide/shadow/warn/mute/kick/staff. "
                + "ai.<category> lines are for the AI's second opinion (toxicity, harassment, slurs, spam, ads, personal, selfharm).")
        public String[] modActions = {"repeat | hide", "caps | soften", "mentionspam | hide", "unicode | soften", "ads | warn", "personal | soften",
                "profanity | allow", "ai.toxicity | warn", "ai.harassment | staff", "ai.slurs | mute", "ai.spam | warn", "ai.ads | warn",
                "ai.personal | warn", "ai.selfharm | staff"};
        @Config.Comment("Words the server's filter catches (used when 'profanity' isn't allow).")
        public String[] badWords = {};
        @Config.Comment("Minutes for an automatic mute (doubles with each strike).") @Config.RangeInt(min = 1, max = 1440)
        public int muteMinutes = 5;
        @Config.Comment("Tell staff (pridechat.channel.staff) about moderation hits.")
        public boolean notifyStaff = true;
        @Config.Comment("Radios, walkie-talkies, phones, intercoms, speakers, megaphones and PA systems (roadmap #18/#19).")
        public boolean devices = true;
        @Config.Comment("Rooms keep talk inside, soundproof rooms let nothing in or out (made with the Room Marker, roadmap #17).")
        public boolean rooms = true;
        @Config.Comment("Biggest room a non-op may make, in blocks (zones have no limit).") @Config.RangeInt(min = 8, max = 100000000)
        public int maxRoomVolume = 200000;
        @Config.Comment("Tag in front of /zone messages (%s = zone name) and /dimchat messages (%s = dimension).")
        public String zoneTag = "&2[⌂ %s] ";
        public String dimTag = "&3[%s] ";
        @Config.Comment("Walkie-talkies reach this many blocks (radios: the whole dimension).") @Config.RangeInt(min = 16, max = 100000)
        public int walkieRange = 256;
        @Config.Comment("Walkie-talkies within this many blocks of a radio tower reach anyone near any other tower in the dimension.") @Config.RangeInt(min = 16, max = 100000)
        public int towerRadius = 384;
        @Config.Comment("A megaphone in hand turns local chat into a shout this far.") @Config.RangeInt(min = 16, max = 10000)
        public int megaphoneRange = 320;
        @Config.Comment("Speakers play their frequency to players within this many blocks.") @Config.RangeInt(min = 2, max = 128)
        public int speakerRange = 12;
        @Config.Comment("Intercoms on the same channel within this many blocks of each other are connected.") @Config.RangeInt(min = 8, max = 1024)
        public int intercomRange = 64;
        @Config.Comment("You hear a phone call within this many blocks of the phone.") @Config.RangeInt(min = 1, max = 16)
        public int phoneRange = 4;
    }

    // ---------------------------------------------------------------- AI (server side; nothing runs until enabled)
    @Config.Comment("AI helpers through PrideMind or Buddy Builder's local model. Everything here is off until 'enabled'.")
    public static final Ai ai = new Ai();

    public static final class Ai {
        @Config.Comment("Master switch. Off = rules only, no model is ever called.")
        public boolean enabled = false;
        @Config.Comment("Use PrideMind when it's installed and has a model connected.")
        public boolean usePrideMind = true;
        @Config.Comment("Otherwise call the OpenAI-style endpoint directly.")
        public boolean direct = true;
        @Config.Comment("Endpoint URL (empty = Buddy Builder's from config/buddybuilder.cfg, else http://127.0.0.1:8096/v1/chat/completions).")
        public String endpoint = "";
        @Config.Comment("Model (empty = Buddy Builder's modelCpu, else buddy-cpu — CPU only, leaves the GPU to Minecraft).")
        public String model = "";
        @Config.Comment("Most AI calls per minute for the whole server.") @Config.RangeInt(min = 1, max = 600)
        public int maxPerMinute = 30;
        @Config.Comment("AI second opinion on messages (after the rules).")
        public boolean moderation = true;
        @Config.Comment("Hold messages until the AI has looked (up to the deadline) instead of checking afterwards.")
        public boolean holdForReview = false;
        @Config.Comment("Longest wait when holding for review, ms.") @Config.RangeInt(min = 200, max = 10000)
        public int reviewDeadlineMs = 1500;
        @Config.Comment("Only messages at least this long get an AI look.") @Config.RangeInt(min = 1, max = 500)
        public int minLength = 12;
        @Config.Comment("Confidence (percent) needed to act.") @Config.RangeInt(min = 0, max = 100)
        public int actConfidence = 70;
        @Config.Comment("Below this confidence (percent) the AI's opinion is ignored; between = only logged.") @Config.RangeInt(min = 0, max = 100)
        public int softConfidence = 40;
        @Config.Comment("What a player who seems to be hurting gets told.")
        public String careMessage = "Hey, you matter. If things feel heavy, talk to someone you trust or a helpline (US: call or text 988).";
        @Config.Comment("'@Pride …' in chat and /ask get answered.")
        public boolean assistant = true;
        @Config.Comment("Seconds between questions per player.") @Config.RangeInt(min = 0, max = 600)
        public int askCooldown = 20;
        @Config.Comment("'While you were away…' summary on join.")
        public boolean catchUp = true;
        @Config.Comment("Only after being away this many minutes.") @Config.RangeInt(min = 1, max = 1440)
        public int catchUpMinutes = 20;
        @Config.Comment("Right-click → Translate.")
        public boolean translate = true;
        @Config.Comment("Tone check before sending harsh-looking messages (client option too).")
        public boolean tone = true;
        @Config.Comment("AFK auto-replies written in the player's own style (client option too).")
        public boolean autoReply = true;
        @Config.Comment("NPCs answer chat near them in character (Custom NPCs or any mob with the tag below: /tag @e[name=Mira] add pridechat_ai).")
        public boolean npcs = true;
        @Config.Comment("The scoreboard tag that makes a mob / NPC talk back.")
        public String npcTag = "pridechat_ai";
        @Config.Comment("NPCs hear chat this close.") @Config.RangeInt(min = 2, max = 64)
        public int npcHearRange = 10;
        @Config.Comment("Seconds between answers from one NPC.") @Config.RangeInt(min = 1, max = 600)
        public int npcCooldownSeconds = 8;
        @Config.Comment("Who each NPC is: Name | persona, e.g. Mira | the cheerful baker who knows everyone's gossip.")
        public String[] npcPersonas = {};
    }

    // ---------------------------------------------------------------- client AI wishes
    @Config.Comment("Language for Translate.")
    public static String translateTo = "English";
    @Config.Comment("Nudge before sending something that might sound harsh.")
    public static boolean toneNudge = false;
    @Config.Comment("AFK auto-replies in your style (needs AI on the server).")
    public static boolean aiAutoReply = false;
    @Config.Comment("Players whose speech bubbles you don't want to see (their chat still shows).")
    public static String[] hiddenBubbles = {};
    @Config.Comment("How a new bubble appears: none, fade, slide, pop, bounce, typewriter, scale, dissolve, enchant, hologram, float ('theirs' = the talker's choice).")
    public static String bubbleAnimation = "pop";
    @Config.Comment("Shortcuts: trigger | text. Sending exactly the trigger sends the text instead (variables work in it).")
    public static String[] macros = {"!coords | I'm at %x %y %z in %dim :round_pushpin:", "!brb | brb :wave:", "!gg | gg everyone :tada:"};
    @Config.Comment("Roleplay style for my messages (roadmap #24): chat (<Name> text, *stars* italic), actions (*waves* = an action), "
            + "novel (outside \"quotes\" = what you do, inside = what you say), script ([Name]: text).")
    public static String rpMode = "chat";
    @Config.Comment("Fill in %x %y %z %dim %biome %health %name %time in what you send.")
    public static boolean macroVars = true;
    @Config.Comment("Mentions also catch short forms of your name (first 4+ letters).")
    public static boolean smartMentions = true;

    private ChatConfig() {}
}
