# Changelog

## 0.5.0 - 2026-10-07

### Added
- **Server-side chat router.** With PrideChat on the server, every message carries its sender, mode, channel, position and range:
  - **Ranged chat:** whisper, local, shout and everyone, with distance falloff and muffling through walls and floors, per-dimension sound rules, and context effects (underwater, on fire, sprinting, riding, hurt). Switch with the mode button, prefixes (`~` whisper, `^` shout, `!` everyone), Ctrl+Enter (shout), Alt+Enter (whisper), sneaking (optional) or the `/whisper`, `/local`, `/shout` and `/global` commands.
  - **Channels and parties** (`/ch`, `/party`), encrypted and dimension channels, **zones and rooms** marked with the Room Marker (rooms keep talk inside; soundproof rooms let nothing in or out), `/dimchat`, and chat between players near linked Nether portals.
  - **Direct messages** with delivered/read receipts, DM tabs, offline **mail** (`/mail`), chat history on join, `/catchup`, and history saved with the world (retention setting).
  - Targeting selectors `@nearest`, `@here`, `@all`, `@team`, `@party` and `@radius N` in `/msg` and `/announce`.
- **Unlimited message length:** long messages and commands are sent whole to PrideChat servers, and split into paced 256-character pieces on vanilla servers.
- **Speech bubbles** above players with emoji, 12 bubble styles, 3D heads that turn toward the speaker, reactions, typing dots that follow real typing speed, a right-click menu and per-player visibility toggles. Players in menus show a hologram screen; AFK players sleep with a Zz in the tab list.
- **Emotes** (`/emote`) with arm animations (wave, clap, point, dance), particles and optional screen shake for listeners. Roleplay styles (`/rpmode`: chat, actions, novel, script), `*italic*` actions, `/anon` and `/chatnick`.
- **Radios and devices** (creative tab, recipes): walkie-talkie, radio, encryption key, megaphone, radio tower, satellite dish, telephone, intercom, speaker and PA system.
- **Chat box and input:** multi-line input (Shift+Enter), message preview, character counter, emoji autocomplete, emoji skin tones, format palette with rainbow and gradient text, spell check, macros and variables, pop-out chat windows, per-tab filters, conversation threads, toasts for mentions and DMs, per-tab sounds and an on-screen "Name talking 43m" indicator with a direction arrow.
- **Search by date and place:** `from:`, `in:`, `since:`, `after:`, `before:`, `on:`, `dim:`, `near:` and `at:` filters.
- **Message animations** for chat lines and bubbles (fade, slide, pop, bounce, typewriter, scale, dissolve, enchant, hologram, float) and permission-limited text effects (glow, animated colour, typewriter, shadow).
- **Per-player appearance** (bubble colour, border, name and message colour, head size) synced to other players, limited by permissions.
- **Moderation:** rule-based filters (repeats, caps, mention spam, ads and more) with configurable actions, auto-mutes, staff notifications, and `/chatmute`, `/chatunmute`, `/chatshadow`, `/chatslow`, `/announce`. `/chatstats` and a stats file in `logs/pridechat/`.
- **Optional AI helpers** (off by default): `/ask`, catch-up summaries, a moderation second opinion, and Custom NPCs or any mob tagged `pridechat_ai` answering nearby chat in character. Uses PrideMind when installed, or any OpenAI-compatible endpoint.
- **Cross-server chat** through an optional HTTP relay (off by default), with a sample relay script and `docs/CROSS-SERVER.md`.
- **API events** for other mods (Typing, Mention, Bubble, Range, Channel, Filter, Context, Talked), a voice-chat hook that exposes the proximity, room, portal and radio engine, and `docs/API.md`.
- **Key bindings:** reply to the last private message (R) and cycle chat range (unbound).
- Settings are now 15 pages (General, Range, Look, Input, Emoji, Alerts, Sounds, Tabs, People, Bubbles, My look, Filters, Tools, Server, AI) with search, and a **PrideChat…** button in Options.

### Fixed
- Removed the taskbar flash on mention, which could crash the game when a message arrived while the window was unfocused.
- The settings screen no longer crashes when opening the "My look" page.
- The AI settings page reads the endpoint once instead of every frame.

### Changed
- Settings from 0.1 are migrated automatically.
- The PrideChat menus follow the pack-wide menu theme chosen in PrideCanvas.

