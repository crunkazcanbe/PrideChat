# PrideChat

A nicer chat box for Minecraft 1.12.2: tabs with unread counts, full-colour emoji with a picker, mentions, timestamps, search and right-click player menus.

## About

PrideChat replaces Minecraft's chat box and chat screen with its own. Every message still goes through the normal chat path, so lines printed by other mods show up too, and clickable links and name hovers keep working.

It was made for the Pride modpack, a 1.12.2 pack of about 750 mods, but it is client-side only and has no dependencies, so it works in other packs and on any server, including vanilla ones. The server does not need it installed.

Every option can be changed from an in-game settings panel (the gear button next to the chat input) and is saved to `config/pridechat.cfg`. Turning PrideChat off gives you Minecraft's normal chat back without losing any lines.

## Features

### Chat box
- Replaces the HUD chat box. Keeps up to 2,500 lines per session, each with its time, sender and the tabs it belongs to.
- Optional timestamp in front of each line, with a selectable format.
- Adjustable width, text scale, background darkness, and how many lines show while chat is open or closed. On small windows the line count shrinks to fit.
- New lines fade out after a set number of seconds while chat is closed.
- Rainbow top edge and a scroll bar while chat is open. Scrolling up to read older lines stays in place when new messages arrive.
- Long words are split cleanly; colour codes carry across wrapped rows.
- Your sent-message history (the up arrow) is kept when PrideChat takes over.
- Logs a warning if TabbyChat is also installed, since only one chat mod should be active.

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
- In the settings panel you can add, edit, recolour (click the colour square to cycle a palette), move up/down, delete and reset tabs.

### Sender detection
The sender of a line is found from the name component's insertion text, a `/msg`, `/tell`, `/w`, `/whisper` or `/m` suggest-command on click, a `<Name>` prefix, or a `Name:` / `Name »` prefix when that name is an online player. This drives the `@player` / `@system` rules, the ignore list and the right-click menu.

### Emoji
- 1,162 full-colour Twemoji images packed into one texture.
- Drawn inline for `:shortcodes:` (for example `:heart:`) and for real emoji characters, including plain symbols such as ❤ ★ ✔. Variation selectors are ignored, so ❤ and ❤️ look the same.
- Extra aliases: `:heart:`, `:love:`, `:lol:`, `:pride:`, `:trans:`.
- Emoji picker (the ☺ button): Recent (last 36 used), Smileys, People, Animals & nature, Food & drink, Activities, Travel & places, Objects, Symbols, Flags; plus a search box. Hover shows the name and shortcode. Clicking inserts the `:shortcode:`, which is plain text and safe on any server.
- Adjustable emoji size and an on/off switch.

### Mentions
- Lines that contain your name (optionally with `@`) or one of your extra mention words get a pink edge and a light tint. Your own `<Name>` prefix does not count.
- Optional soft note sound when you are mentioned.

### Search
- The ⌕ button or Ctrl+F opens a search box beside the tabs. It filters the current tab to lines containing the text (case-insensitive). Search is cleared when the chat is closed.

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
Messages from ignored players are hidden, not deleted, and do not count toward unread totals. Clear the name from the list to see them again.

### Settings panel
Opened with the ⚙ button. Sections: Chat, Look, Emoji (with a preview row), Mentions, Tabs, Ignored players, and Tools:
- **Try it** shows a pretend mention so you can see how mentions and emoji look.
- **Clear** clears this session's chat.

Changes save as you click.

## Commands

PrideChat adds no commands and no key bindings. Ctrl+F (search) and Esc (close the picker or menu) work inside the chat screen.

## Config

File: `config/pridechat.cfg` (Forge `@Config`, also editable from the Mods list and the in-game panel).

| Key | Default | Range | Effect |
|-----|---------|-------|--------|
| `enabled` | `true` | | Use PrideChat's chat. Off = Minecraft's normal chat. |
| `width` | `330` | 160–640 | Chat width in pixels (GUI scale). |
| `linesClosed` | `10` | 3–30 | Lines shown while chat is closed. |
| `linesOpen` | `20` | 5–60 | Lines shown while chat is open. |
| `opacity` | `55` | 0–100 | Background darkness. |
| `fadeSeconds` | `10` | 3–60 | Seconds a new line stays visible while chat is closed. |
| `timestamps` | `true` | | Show the time in front of each line. |
| `timeFormat` | `HH:mm` | | Java `SimpleDateFormat` pattern. The panel cycles `HH:mm`, `HH:mm:ss`, `h:mm a`, `h:mm:ss a`. |
| `emoji` | `true` | | Draw emoji as colour pictures. |
| `emojiSize` | `9` | 8–14 | Emoji size in pixels (line height is 9). |
| `mentions` | `true` | | Highlight lines that mention you. |
| `mentionSound` | `true` | | Play a sound when someone mentions you. |
| `mentionWords` | *(empty)* | | Extra words that count as a mention. |
| `tabs` | `true` | | Show the tab strip. |
| `tabList` | 7 default tabs | | Tabs as `name \| colour \| rule` (see Tabs above). |
| `ignored` | *(empty)* | | Players whose messages are hidden. |
| `scale` | `100` | 50–200 | Chat text scale, percent. |

## Requirements

- Minecraft 1.12.2
- Forge 14.23.5.2860 or newer, or Cleanroom
- Client only. Not needed on the server.

## Install

1. Download `PrideChat-1.12.2-<version>.jar`.
2. Put it in your `mods` folder.
3. Start the game and open chat (T). Click ⚙ to change settings.

## Building

```
./gradlew build
```

The jar is written to `build/libs/`. The project targets Java 8 (ForgeGradle 3, MCP snapshot `20171003-1.12`).

## License

MIT License. © 2026 crunkazcanbe.

Emoji graphics are Twemoji, licensed under CC-BY 4.0 (see `TWEMOJI-LICENSE.txt`). Emoji names and shortcodes come from Emojibase (MIT).

## Credits

Made by crunkazcanbe, with Claude.
