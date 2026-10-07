# PrideChat

A complete communication system for Minecraft 1.12.2: a nicer chat box with tabs, full-colour emoji, mentions and search, plus ranged and channel chat, DMs, speech bubbles, emotes, radios and phones, rooms, moderation and an API for other mods.

## About

PrideChat replaces Minecraft's chat box and chat screen with its own. Every message still goes through the normal chat path, so lines printed by other mods show up too, and clickable links and name hovers keep working.

It was made for the Pride modpack, a 1.12.2 pack of about 750 mods, but it has no dependencies and works in other packs.

PrideChat works on two levels:

- **Client only:** the chat box, tabs, emoji, mentions, search, input tools and appearance work on any server, including vanilla ones. Long messages are split into paced 256-character pieces there.
- **Client and server:** with PrideChat on the server too, you also get ranged chat (whisper / local / shout / everyone), channels, parties, DMs with read receipts, mail, speech bubbles, emotes, rooms and zones, radios and other devices, moderation and the API. Install it on both sides for these.

Every option can be changed from an in-game settings panel (the ⚙ button next to the chat input, or **Options > PrideChat…**) and is saved to `config/pridechat.cfg`. Turning PrideChat off gives you Minecraft's normal chat back without losing any lines.

## Features

### Chat box
- Replaces the HUD chat box. Keeps 2,500 lines per session by default (up to 20,000), each with its time, sender and the tabs it belongs to.
- Optional timestamp in front of each line, with a selectable format.
- Adjustable width, position, text scale, line spacing, background style and darkness, and how many lines show while chat is open or closed. On small windows the line count shrinks to fit. Drag and resize it while chat is open.
- New lines fade out after a set number of seconds while chat is closed. Lines can animate in (fade, slide, pop, bounce, typewriter, scale, dissolve, enchant, hologram, float).
- Chat heads, unread badges, a jump-to-latest button, duplicate collapsing and optional join/leave, death and advancement hiding.
- Pop out any tab into its own extra chat window.
- Rainbow top edge and a scroll bar while chat is open. Scrolling up to read older lines stays in place when new messages arrive.
- Long words are split cleanly; colour codes carry across wrapped rows.
- Your sent-message history (the up arrow) is kept when PrideChat takes over, and can be remembered between sessions. An unsent draft is kept when you close the chat.
- Chat can be logged to `logs/pridechat/<date>.log`.
- Logs a warning if TabbyChat is also installed, since only one chat mod should be active.

### Input
- **Unlimited length:** type up to 32,000 characters. PrideChat servers receive the whole message; vanilla servers get it split into paced pieces.
- Multi-line messages (Shift+Enter), a live preview, a character counter, emoji autocomplete, a format palette with colours, rainbow and gradient text, and `*text*` in italics for roleplay.
- Spell check (about 275,000 English words, plus your own words and the names of everyone online).
- Macros and variables.
- Mode button and prefixes for range: `~` whisper, `^` shout, `!` everyone. Ctrl+Enter shouts, Alt+Enter whispers.

### Tabs
- A tab strip above the chat while it is open. Each tab shows an unread count when it is not selected.
- A line appears in every tab it matches.
- Each tab is `name | hex colour | rule`. The rule is a Java regex matched against the plain text of the line, or one of these special rules:

| Rule | Shows |
|------|-------|
| `@all` | everything |
| `@player` | messages sent by a player |
| `@system` | everything that is not a player message (server and mod messages) |
| `@mention` | lines that mention you |

- Default tabs: All, Chat, Town (town/nation/ally/region prefixes), Private (whispers and `/msg`), Mentions, Staff (staff/helpop/ticket/mod/admin prefixes), System.
- DM tabs open automatically for private conversations, and channel tabs can be added too. Each tab can have its own sound, mute and pop-up settings and filter checkboxes.
- In the settings panel you can add, edit, recolour (click the colour square to cycle a palette), move up/down, delete and reset tabs.

### Sender detection
The sender of a line is found from the name component's insertion text, a `/msg`, `/tell`, `/w`, `/whisper` or `/m` suggest-command on click, a `<Name>` prefix, or a `Name:` / `Name »` prefix when that name is an online player. This drives the `@player` / `@system` rules, the ignore list and the right-click menu.

### Emoji
- 1,162 full-colour Twemoji images packed into one texture, with skin tones.
- Drawn inline for `:shortcodes:` (for example `:heart:`) and for real emoji characters, including plain symbols such as ❤ ★ ✔. Variation selectors are ignored, so ❤ and ❤️ look the same.
- Extra aliases: `:heart:`, `:love:`, `:lol:`, `:pride:`, `:trans:`.
- Emoji picker (the ☺ button): Recent (last 36 used), Smileys, People, Animals & nature, Food & drink, Activities, Travel & places, Objects, Symbols, Flags; plus a search box. Hover shows the name and shortcode. Clicking inserts the `:shortcode:`, which is plain text and safe on any server.
- Adjustable emoji size and an on/off switch.

### Mentions
- Lines that contain your name (optionally with `@`) or one of your extra mention words get a pink edge and a light tint. Your own `<Name>` prefix does not count.
- Optional sound when you are mentioned, and pop-up toasts for mentions and DMs.
- An on-screen "Name talking 43m" indicator with a direction arrow shows who is talking nearby.
- Do-not-disturb and an away auto-reply.

### Search
- The ⌕ button or Ctrl+F opens a search box beside the tabs. It filters the current tab to lines containing the text (case-insensitive). Search is cleared when the chat is closed.
- Filters: `from:name`, `in:#channel`, `in:local`, `since:30m`, `after:14:00`, `before:16:30`, `on:2026-10-05` / `today` / `yesterday`, `dim:nether`, `dim:here`, `near:100`, `at:x,y,z`.

### Right-click menu on a message
On another player's message:

| Entry | Action |
|-------|--------|
| ✉ Message *name* | puts `/msg name ` in the input |
| @ Mention *name* | inserts `@name ` |
| $ Pay *name*… | puts `/pay name ` in the input |
| ➤ Ask to teleport to *name* | sends `/tpa name` |
| ⧉ Copy name | copies the name to the clipboard |
| ✖ Ignore / ☺ Stop ignoring *name* | toggles the name in the ignore list |

On any message: ⧉ Copy message, ✖ Close.

### Ignore list
Messages from ignored players are hidden, not deleted, and do not count toward unread totals. Clear the name from the list to see them again. Muting someone only silences their sounds and pop-ups.

### Ranged chat (server)
- Messages travel **whisper** (8 blocks), **local** (48), **shout** (160) or to **everyone**; all ranges are configurable.
- Distance falloff, muffling through walls and floors, an underground bonus, per-dimension sound rules (the Nether carries less, the End more) and context effects (underwater, on fire, sprinting, riding, hurt).
- Optional: sneaking turns local chat into a whisper; whisper to the player you are facing.
- Players near linked Nether portals hear each other across dimensions.
- "Nobody heard you" notice when no one was in range.

### Channels, parties and messages (server)
- Channels from the config (staff, trade, …) with permissions, plus encrypted and dimension channels. `/ch` to list, join, leave and talk.
- Parties with `/party` (create, invite, join, leave, members).
- `/msg` with delivered and read receipts, and selectors `@nearest`, `@here`, `@all`, `@team`, `@party`, `@radius N`.
- Mail for offline players (`/mail`), chat history on join, `/catchup` for what you missed, and chat history saved with the world (1 hour to forever).
- Reactions, typing dots and auto-responder bots (`!rules`, `!help` and your own).

### Rooms and zones (server)
- Mark an area with the **Room Marker** (right-click corner A, sneak-right-click corner B, right-click the air to save).
- **Rooms** keep talk inside; **soundproof rooms** let nothing in or out; **zones** get their own chat (`/zone`). Manage them with `/room`.

### Radios and devices (server)
- Walkie-talkie (256 blocks, or between radio towers), radio (whole dimension), encryption key, megaphone, radio tower, satellite dish, telephone, intercom, speaker and PA system. All have recipes and their own creative tab.

### Speech bubbles and emotes
- Bubbles above players with emoji and 3D skin heads that turn toward the speaker or listener. 12 bubble styles (per player, travelling with messages), reactions, whisper and shout styles, and pop or typewriter animations.
- Typing dots that speed up with real typing speed, a hologram screen and tapping arm for players in menus, and an AFK sleep pose with Zz (also in the tab list). Every one of these can be turned off for sending or showing.
- `/emote` with arm animations (wave, clap, point, dance), particles and optional screen shake for listeners.
- Right-click a bubble or head for a menu; hide one player's bubbles, chat, emotes or typing.
- **Per-player appearance** (bubble colour and border, name and message colours, head size) synced to others, limited by permissions.
- Text effects by permission: glow, animated colour, typewriter, shadow.
- Roleplay styles (`/rpmode chat | actions | novel | script`), `/anon` and `/chatnick`.

### Moderation (server)
- Rule-based moderation for repeats, caps, mention spam, odd Unicode, ads and more. Each category can allow, soften, hide, shadow, warn, mute, kick or alert staff. Auto-mutes double with each strike.
- Staff commands: `/chatmute`, `/chatunmute`, `/chatshadow`, `/chatslow`, `/announce`. Moderation hits are logged to `logs/pridechat/moderation.json`.
- `/chatstats`: messages per minute, most active players, channels, spam blocked and peak hours, also written to `logs/pridechat/stats.json` every 5 minutes.
- Client-side word filter (censor or hide) and flood protection.

### AI helpers (optional, off by default)
- `/ask` answers just you, `/catchup` can summarise what you missed, plus translation and tone hints, auto-replies and a moderation second opinion.
- Custom NPCs, or any mob tagged `pridechat_ai`, answer nearby chat in character, rate-limited, with bubbles.
- Uses PrideMind when installed, or any OpenAI-compatible endpoint you configure. Nothing is sent anywhere until you turn it on.

### Cross-server chat (optional, off by default)
- Several servers can share everyone-chat and chosen channels through a small HTTP relay. Lines from another server are tagged with its name. See `docs/CROSS-SERVER.md` and `tools/pridechat-relay.py`.

### Settings panel
Opened with the ⚙ button or **Options > PrideChat…**. 15 pages with a search box: General, Range, Look, Input, Emoji, Alerts, Sounds, Tabs, People, Bubbles, My look, Filters, Tools, Server and AI.
- **Try it** shows a pretend mention so you can see how mentions and emoji look.
- **Clear** clears this session's chat.

Changes save as you click. Settings from 0.1 are migrated automatically. The panel follows the pack-wide menu theme when PrideCanvas is installed.

## Commands

Server commands (need PrideChat on the server). Permission level 0 unless noted.

| Command | What it does |
|---------|--------------|
| `/whisper`, `/local`, `/shout`, `/global` `[message]` | Send one message at that range, or switch your mode with no message. Aliases: `/quiet`, `/l`, `/yell`, `/g`. |
| `/zone [message]` | Talk in the zone you are in. |
| `/dimchat [message]` | Everyone in your dimension. |
| `/ch list \| join <name> \| leave <name> \| <name> [message]` | Channels. |
| `/party create <name> \| invite <player> \| join <name> \| leave \| members \| <message>` | Party groups. |
| `/msg <player\|@selector> <message>` | Private message with receipts (replaces vanilla `/msg` unless turned off). |
| `/mail send <player> <message> \| read \| clear` | Mail for offline players. |
| `/room list [all] \| here \| delete <name> \| kind <name> <room/soundproof/zone>` | Manage rooms and zones. |
| `/emote <action>` | An action only people near you see. |
| `/rpmode chat \| actions \| novel \| script` | How your messages look. |
| `/anon <message>` | To everyone, without your name. |
| `/chatnick <name> \| off` | Talk under another name. |
| `/ask <question>` | Ask the helper (AI must be on). |
| `/catchup` | What did I miss? |
| `/chatstats` | Chat statistics. |
| `/chatmute <player> [minutes]` | Mute a player (op). |
| `/chatunmute <player>` | Unmute (op). |
| `/chatshadow <player>` | Toggle: only they see their own messages (op). |
| `/chatslow <seconds>` | Slow mode, 0 = off (op). |
| `/announce [@selector] <message>` | Announcement (op). |

## Key bindings

| Name | Default | What it does |
|------|---------|--------------|
| Reply to last private message | R | Opens chat on the DM tab (or with `/msg <last sender> `) for the last private message. |
| Chat range: whisper → local → shout → everyone | *(unbound)* | Cycles your default range. |

Inside the chat screen: Ctrl+F (search), Shift+Enter (new line), Ctrl+Enter (shout), Alt+Enter (whisper), Esc (close the picker or menu).

## Config

File: `config/pridechat.cfg` (Forge `@Config`, also editable from the Mods list and the in-game panel). It has three parts:

- **Client** (top level): chat box size, position, look and animations, input tools, emoji, mentions and alerts, sounds, tabs, people (ignored, muted, friends), auto-reply, bubbles, your own appearance, filters and logging.
- **`server`**: message length and range limits, tags for each range, slow mode and flood limit, mail, `/msg` takeover, text-effect permissions, chat physics (falloff, walls, floors, dimensions, portals), history retention, channels, bots, roleplay, emotes, moderation, devices and rooms, and cross-server relay settings.
- **`ai`**: everything for the AI helpers, off until `enabled` is set.

Some commonly changed keys:

| Key | Default | Effect |
|-----|---------|--------|
| `enabled` | `true` | Use PrideChat's chat. Off = Minecraft's normal chat. |
| `sendMode` | `local` | Default range: whisper, local, shout or global. |
| `whisperRange` / `localRange` / `shoutRange` | `8` / `48` / `160` | Ranges in blocks. |
| `globalPrefix` / `shoutPrefix` / `whisperPrefix` | `!` / `^` / `~` | Prefixes that pick a range for one message. |
| `width` | `330` | Chat width in pixels (160–900). |
| `linesClosed` / `linesOpen` | `10` / `20` | Lines shown while chat is closed / open. |
| `opacity` | `55` | Background darkness (0–100). |
| `historyLines` | `2500` | Lines kept in memory. |
| `timestamps` / `timeFormat` | `true` / `HH:mm` | Time in front of each line. |
| `emoji` / `emojiSize` | `true` / `9` | Colour emoji and their size. |
| `mentions` / `mentionWords` | `true` / *(empty)* | Highlight mentions, extra mention words. |
| `tabList` | 7 default tabs | Tabs as `name \| colour \| rule`. |
| `bubbles` | `true` | Speech bubbles above players. |
| `spellCheck` | `true` | Spell check while typing. |
| `logToFile` | `true` | Save chat to `logs/pridechat/<date>.log`. |
| `server.moderation` | `true` | Rule-based moderation. |
| `server.devices` / `server.rooms` | `true` / `true` | Radios and devices; rooms and zones. |
| `server.historyRetention` | `7d` | How long chat is kept with the world (`1h`, `1d`, `7d`, `30d`, `forever`, `off`). |
| `server.crossServer` | `false` | Cross-server relay. |
| `ai.enabled` | `false` | AI helpers. |

## API

PrideChat posts Forge events other mods can listen to (`PrideChatEvent.Talked`, Typing, Mention, Bubble, Range, Channel, Filter, Context, and a client `TalkEvent`) and exposes its proximity, room, portal and radio engine for voice-chat mods. See `docs/API.md`. It is an optional dependency: guard with `Loader.isModLoaded("pridechat")`.

## Requirements

- Minecraft 1.12.2
- Forge 14.23.5.2860 or newer, or Cleanroom
- Client only for the chat box. Install on the server too for ranged chat, channels, bubbles, devices and moderation.

## Install

1. Download `PrideChat-1.12.2-<version>.jar`.
2. Put it in your `mods` folder.
3. For server features, put the same jar in the server's `mods` folder too.
4. Start the game and open chat (T). Click ⚙ to change settings.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. The project targets Java 8 (ForgeGradle 3, MCP snapshot `20171003-1.12`).

## License

MIT License. © 2026 crunkazcanbe.

Emoji graphics are Twemoji, licensed under CC-BY 4.0 (see `TWEMOJI-LICENSE.txt`). Emoji names and shortcodes come from Emojibase (MIT). The spell-check word list comes from an-array-of-english-words (MIT, see `WORDS-LICENSE.txt`).

## Credits

Made with [Claude Code](https://claude.com/claude-code) and [Blockbench](https://www.blockbench.net).

Emoji: Twemoji (CC-BY 4.0) and Emojibase (MIT). Spell-check words: an-array-of-english-words (MIT).
