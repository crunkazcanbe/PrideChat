package com.dogpound.pridechat;

import net.minecraft.command.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Chat commands. Players: /local /global /whisper /shout (with a message = send it, alone = switch your mode),
 * /ch, /party, /mail, /ask, /catchup, /emote. Staff: /chatmute /chatunmute /chatshadow /chatslow /announce.
 */
final class ChatCommands {
    interface Body { void run(MinecraftServer s, ICommandSender who, String[] a) throws CommandException; }

    static final class Cmd extends CommandBase {
        final String name, usage;
        final int level;
        final List<String> aliases;
        final Body body;
        final String[] subs;
        Cmd(String name, int level, String usage, Body body, String[] subs, String... aliases) {
            this.name = name; this.level = level; this.usage = usage; this.body = body; this.subs = subs; this.aliases = Arrays.asList(aliases);
        }
        @Override public String getName() { return name; }
        @Override public String getUsage(ICommandSender s) { return usage; }
        @Override public List<String> getAliases() { return aliases; }
        @Override public int getRequiredPermissionLevel() { return level; }
        @Override public boolean checkPermission(MinecraftServer s, ICommandSender who) { return level == 0 || who.canUseCommand(level, name); }
        @Override public void execute(MinecraftServer s, ICommandSender who, String[] a) throws CommandException { body.run(s, who, a); }
        @Override public List<String> getTabCompletions(MinecraftServer s, ICommandSender who, String[] a, @Nullable BlockPos pos) {
            if (a.length == 1 && subs != null && subs.length > 0 && subs[0].startsWith("@")) {    // selectors + names
                List<String> l = new ArrayList<>(Arrays.asList(subs));
                l.addAll(Arrays.asList(s.getOnlinePlayerNames()));
                return getListOfStringsMatchingLastWord(a, l);
            }
            if (a.length == 1 && subs != null) return getListOfStringsMatchingLastWord(a, subs);
            return getListOfStringsMatchingLastWord(a, s.getOnlinePlayerNames());
        }
    }

    static String join(String[] a, int from) { return a.length <= from ? "" : String.join(" ", Arrays.copyOfRange(a, from, a.length)); }

    static void mode(EntityPlayerMP p, String mode, String target) {
        if (!Router.hasPrideChat(p)) { Router.tell(p, "§7Add a message: /" + mode + " <message>"); return; }
        NBTTagCompound t = new NBTTagCompound();
        t.setString("mode", mode);
        t.setString("target", target);
        Net.send(p, Net.S_MODE, t, "");
        Router.tell(p, "§7You're now talking §f" + (Router.CHANNEL.equals(mode) ? "in #" + target : mode) + "§7.");
    }

    static Cmd ranged(String mode, String... aliases) {
        return new Cmd(mode, 0, "/" + mode + " [message]", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (a.length == 0) mode(p, mode, "");
            else Router.handle(p, join(a, 0), mode, 0, "");
        }, null, aliases);
    }

    static void register(FMLServerStartingEvent e) {
        e.registerServerCommand(ranged(Router.LOCAL, "l"));
        e.registerServerCommand(ranged(Router.GLOBAL, "g"));
        e.registerServerCommand(ranged(Router.WHISPER, "quiet"));
        e.registerServerCommand(ranged(Router.SHOUT, "yell"));
        e.registerServerCommand(ranged(Router.ZONE));
        e.registerServerCommand(new Cmd("dimchat", 0, "/dimchat [message] — everyone in your dimension", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (a.length == 0) mode(p, Router.DIM, "");
            else Router.handle(p, join(a, 0), Router.DIM, 0, "");
        }, null, "dc"));
        e.registerServerCommand(new Cmd("room", 0, "/room list [all] | here | delete <name> | kind <name> <room/soundproof/zone>", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            String sub = a.length == 0 ? "here" : a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "list": {
                    List<String> l = Rooms.list(a.length > 1 && "all".equalsIgnoreCase(a[1]) ? Integer.MIN_VALUE : p.dimension);
                    Router.tell(p, "§d" + l.size() + " rooms/zones" + (l.isEmpty() ? " — make one with the Room Marker" : ":"));
                    for (String x : l) Router.tell(p, " " + x);
                    return;
                }
                case "delete": case "remove": if (a.length < 2) throw new WrongUsageException("/room delete <name>"); Router.tell(p, Rooms.delete(p, join(a, 1))); return;
                case "kind": if (a.length < 3) throw new WrongUsageException("/room kind <name> <room/soundproof/zone>"); Router.tell(p, Rooms.kind(p, join(a, 1).substring(0, join(a, 1).lastIndexOf(' ')), a[a.length - 1])); return;
                default: {
                    net.minecraft.nbt.NBTTagCompound in = Rooms.around(p, false), z = Rooms.around(p, true);
                    Router.tell(p, "§7You're " + (in == null ? "outside any room" : "in " + Rooms.describe(in)) + (z == null ? "" : "§7, zone §f" + z.getString("n")) + "§7.");
                }
            }
        }, new String[]{"list", "here", "delete", "kind"}, "rooms"));
        e.registerServerCommand(new Cmd("emote", 0, "/emote <action>  (only people near you see it)", (s, who, a) -> {
            if (a.length == 0) throw new WrongUsageException("/emote <action>");
            Router.Msg m = new Router.Msg();
            m.sender = CommandBase.getCommandSenderAsPlayer(who); m.text = join(a, 0); m.mode = Router.EMOTE; m.range = Router.rangeFor(Router.EMOTE, 0);
            Emotes.prepare(m);                                           // whispers tiny + close, shouts giant + far; effects after delivery (#23/#48)
            if (Router.gate(m.sender)) Router.route(m);
        }, null));

        e.registerServerCommand(new Cmd("ch", 0, "/ch list | join <name> | leave <name> | <name> [message]", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (a.length == 0 || "list".equalsIgnoreCase(a[0])) {
                StringBuilder b = new StringBuilder("§dChannels: ");
                for (Channels.Ch c : Channels.all()) {
                    if (!c.perm.isEmpty() && !Router.perm(p, c.perm)) continue;
                    if (c.group && !c.members.contains(p.getUniqueID())) continue;
                    b.append(c.isMember(p) ? "§a#" : "§7#").append(c.name).append(c.range > 0 ? "§8(" + c.range + "m)" : "").append(" ");
                }
                Router.tell(p, b.toString() + "§8(green = you're in it)");
                return;
            }
            if ("join".equalsIgnoreCase(a[0]) && a.length > 1) { Router.tell(p, Channels.join(p, a[1])); return; }
            if ("leave".equalsIgnoreCase(a[0]) && a.length > 1) { Router.tell(p, Channels.leave(p, a[1])); return; }
            if (a.length == 1) { Channels.Ch c = Channels.get(a[0]); if (c != null && c.isMember(p)) mode(p, Router.CHANNEL, c.name); else Router.tell(p, "§cYou're not in #" + a[0]); return; }
            Router.handle(p, join(a, 1), Router.CHANNEL, 0, a[0]);
        }, new String[]{"list", "join", "leave"}, "channel"));

        e.registerServerCommand(new Cmd("party", 0, "/party create <name> | invite <player> | join <name> | leave | members | <message>", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            Channels.Ch g = Channels.groupOf(p);
            String sub = a.length == 0 ? "" : a[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "create": if (a.length < 2) throw new WrongUsageException("/party create <name>"); Router.tell(p, Channels.create(p, a[1])); return;
                case "invite": {
                    if (a.length < 2) throw new WrongUsageException("/party invite <player>");
                    if (g == null) { Router.tell(p, "§cMake a group first: /party create <name>"); return; }
                    EntityPlayerMP to = CommandBase.getPlayer(s, who, a[1]);
                    Router.tell(p, Channels.invite(p, a.length > 2 ? a[2] : g.name, to));
                    return;
                }
                case "join": if (a.length < 2) throw new WrongUsageException("/party join <name>"); Router.tell(p, Channels.join(p, a[1])); return;
                case "leave": if (g == null && a.length < 2) { Router.tell(p, "§cYou're not in a group."); return; } Router.tell(p, Channels.leave(p, a.length > 1 ? a[1] : g.name)); return;
                case "members": case "list": {
                    if (g == null) { Router.tell(p, "§7You're not in a group. /party create <name>"); return; }
                    List<String> names = new ArrayList<>();
                    for (UUID u : g.members) { EntityPlayerMP m = s.getPlayerList().getPlayerByUUID(u); names.add(m == null ? "§8(offline)" : "§f" + m.getName()); }
                    Router.tell(p, "§d#" + g.name + ": " + String.join("§7, ", names));
                    return;
                }
                case "": if (g != null) mode(p, Router.CHANNEL, g.name); else Router.tell(p, "§7/party create <name> to start a group."); return;
                default:
                    if (g == null) { Router.tell(p, "§cYou're not in a group."); return; }
                    Router.handle(p, join(a, 0), Router.CHANNEL, 0, g.name);
            }
        }, new String[]{"create", "invite", "join", "leave", "members"}, "group"));

        e.registerServerCommand(new Cmd("mail", 0, "/mail send <player> <message> | read | clear", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            String sub = a.length == 0 ? "read" : a[0].toLowerCase(Locale.ROOT);
            if ("send".equals(sub)) {
                if (a.length < 3) throw new WrongUsageException("/mail send <player> <message>");
                Router.tell(p, Mail.send(p.getName(), a[1], join(a, 2)) ? "§d✉ Mail sent to " + a[1] + "." : "§cCouldn't send (mailbox full or bad name).");
                EntityPlayerMP to = s.getPlayerList().getPlayerByUsername(a[1]);
                if (to != null) Router.tell(to, "§d✉ New mail from " + p.getName() + " — §f/mail read");
            } else if ("clear".equals(sub)) { Mail.clear(p.getName()); Router.tell(p, "§7Mailbox emptied."); }
            else {
                List<String> l = Mail.read(p.getName());
                if (l.isEmpty()) Router.tell(p, "§7No mail.");
                for (String x : l) Router.tell(p, x);
                if (!l.isEmpty()) Router.tell(p, "§8/mail clear to empty it");
            }
        }, new String[]{"send", "read", "clear"}));

        e.registerServerCommand(new Cmd("ask", 0, "/ask <question>  (the " + ChatConfig.server.botName + " helper answers just you)", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (!ChatConfig.ai.enabled) { Router.tell(p, "§7The AI helper is off on this server."); return; }
            AI.answer(p, join(a, 0), Collections.singletonList(p));
        }, null));
        e.registerServerCommand(new Cmd("catchup", 0, "/catchup — what did I miss?", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (!ChatConfig.ai.enabled) { Router.tell(p, "§7The AI helper is off on this server."); return; }
            AI.summarize(p, 0);
        }, null));

        e.registerServerCommand(new Cmd("rpmode", 0, "/rpmode chat | actions | novel | script — how your messages look (roadmap #24)", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (a.length == 0 || !Arrays.asList(Router.RP_MODES).contains(a[0].toLowerCase(Locale.ROOT))) {
                String cur = Router.RPMODE.getOrDefault(p.getUniqueID(), "chat");
                Router.tell(p, "§dRoleplay style: §f" + cur + " §8— /rpmode chat | actions (*waves* = action) | novel (outside \"quotes\" = action) | script ([Name]: text)");
                return;
            }
            Router.RPMODE.put(p.getUniqueID(), a[0].toLowerCase(Locale.ROOT));
            if (Router.hasPrideChat(p)) {                                // their PrideChat remembers it (Settings → Input)
                NBTTagCompound t = new NBTTagCompound();
                t.setString("kind", "rpmode"); t.setString("v", a[0].toLowerCase(Locale.ROOT));
                Net.send(p, Net.S_EFFECT, t, "");
            }
            Router.tell(p, "§7Your messages now look like §f" + a[0].toLowerCase(Locale.ROOT) + "§7.");
        }, Router.RP_MODES, "rp"));
        e.registerServerCommand(new Cmd("anon", 0, "/anon <message> — to everyone, without your name", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (!Router.perm(p, "pridechat.anonymous")) { Router.tell(p, "§cYou can't send anonymous messages here."); return; }
            if (a.length == 0) throw new WrongUsageException("/anon <message>");
            Router.Msg m = new Router.Msg();
            m.sender = p; m.text = join(a, 0); m.mode = Router.GLOBAL; m.anonymous = true;
            if (Router.gate(p)) Router.route(m);
        }, null));
        e.registerServerCommand(new Cmd("chatnick", 0, "/chatnick <name> | off — talk under another name", (s, who, a) -> {
            EntityPlayerMP p = CommandBase.getCommandSenderAsPlayer(who);
            if (!Router.perm(p, "pridechat.nick")) { Router.tell(p, "§cYou can't use chat nicknames here."); return; }
            if (a.length == 0 || "off".equalsIgnoreCase(a[0])) { Router.NICKS.remove(p.getUniqueID()); Router.tell(p, "§7You talk as yourself again."); return; }
            String n = join(a, 0);
            if (n.length() > 32) n = n.substring(0, 32);
            Router.NICKS.put(p.getUniqueID(), n);
            Router.tell(p, "§7You now talk as §f" + Router.colors(n) + "§7. /chatnick off to stop.");
        }, null));
        e.registerServerCommand(new Cmd("chatstats", 0, "/chatstats — messages per minute, most active, channels, spam blocked, peak hours", (s, who, a) -> Stats.show(who), null));

        // ---------------- staff
        e.registerServerCommand(new Cmd("chatmute", 2, "/chatmute <player> [minutes, 0 = until unmuted]", (s, who, a) -> {
            if (a.length < 1) throw new WrongUsageException("/chatmute <player> [minutes]");
            EntityPlayerMP p = CommandBase.getPlayer(s, who, a[0]);
            int min = a.length > 1 ? CommandBase.parseInt(a[1], 0, 100000) : 0;
            Moderation.mute(p.getUniqueID(), min);
            Moderation.log(p, "staff", join(a, 2), "mute " + min, "", who.getName(), 1);
            CommandBase.notifyCommandListener(who, this0(), "§e" + p.getName() + " muted" + (min > 0 ? " for " + min + " min" : ""));
            Router.tell(p, "§cA moderator muted you" + (min > 0 ? " for " + min + " min" : "") + ".");
        }, null));
        e.registerServerCommand(new Cmd("chatunmute", 2, "/chatunmute <player>", (s, who, a) -> {
            if (a.length < 1) throw new WrongUsageException("/chatunmute <player>");
            EntityPlayerMP p = CommandBase.getPlayer(s, who, a[0]);
            Moderation.unmute(p.getUniqueID());
            CommandBase.notifyCommandListener(who, this0(), "§a" + p.getName() + " can talk again");
        }, null));
        e.registerServerCommand(new Cmd("chatshadow", 2, "/chatshadow <player> — toggle: only they see their own messages", (s, who, a) -> {
            if (a.length < 1) throw new WrongUsageException("/chatshadow <player>");
            EntityPlayerMP p = CommandBase.getPlayer(s, who, a[0]);
            boolean on = Moderation.toggleShadow(p.getUniqueID());
            Moderation.log(p, "staff", "shadow mute", on ? "shadow on" : "shadow off", "", who.getName(), 1);
            CommandBase.notifyCommandListener(who, this0(), "§e" + p.getName() + " shadow-muted: " + on);
        }, null));
        e.registerServerCommand(new Cmd("chatslow", 2, "/chatslow <seconds, 0 = off>", (s, who, a) -> {
            ChatConfig.server.slowMode = a.length > 0 ? CommandBase.parseInt(a[0], 0, 600) : 0;
            for (EntityPlayerMP p : s.getPlayerList().getPlayers())
                Router.tell(p, ChatConfig.server.slowMode > 0 ? "§eSlow mode: one message every " + ChatConfig.server.slowMode + " s." : "§aSlow mode is off.");
        }, null));
        e.registerServerCommand(new Cmd("announce", 2, "/announce [@nearest|@here|@all|@team|@party|@radius N] <message>", (s, who, a) -> {
            if (a.length == 0) throw new WrongUsageException("/announce [@here|@radius N|…] <message>");
            Object[] sel = Selectors.resolve(who, a, 0);
            @SuppressWarnings("unchecked") List<EntityPlayerMP> only = sel == null ? null : (List<EntityPlayerMP>) sel[0];
            String text = join(a, sel == null ? 0 : (int) sel[1]);
            if (text.isEmpty()) throw new WrongUsageException("/announce [@selector] <message>");
            if (only != null && only.isEmpty()) { who.sendMessage(new net.minecraft.util.text.TextComponentString("§7Nobody matches " + a[0] + ".")); return; }
            Router.Msg m = new Router.Msg();
            m.sender = who instanceof EntityPlayerMP ? (EntityPlayerMP) who : null;
            m.text = text; m.mode = Router.ANNOUNCE; m.only = only;
            if (m.sender == null) { Router.bot(only != null ? only : s.getPlayerList().getPlayers(), "Announcement", m.text); return; }
            Router.deliver(m);
            if (only != null) Router.tell(m.sender, "§7(announced to " + only.size() + " player" + (only.size() == 1 ? "" : "s") + ")");
        }, Selectors.ALL));
        if (ChatConfig.server.takeOverMsg) e.registerServerCommand(new Cmd("msg", 0, "/msg <player|@nearest|@here|@all|@team|@party|@radius N> <message>", (s, who, a) -> {
            if (a.length < 2) throw new WrongUsageException("/msg <player|@selector> <message>");
            Object[] sel = Selectors.resolve(who, a, 0);
            List<EntityPlayerMP> to;
            int used;
            if (sel != null) { @SuppressWarnings("unchecked") List<EntityPlayerMP> l = (List<EntityPlayerMP>) sel[0]; to = l; used = (int) sel[1]; }
            else {
                used = 1;
                EntityPlayerMP one = s.getPlayerList().getPlayerByUsername(a[0]);
                if (one == null && who instanceof EntityPlayerMP && ChatConfig.server.mail && !a[0].startsWith("@")) {   // offline: mail (as before)
                    Router.handle((EntityPlayerMP) who, join(a, 1), Router.DM, 0, a[0]);
                    return;
                }
                to = one != null ? Collections.singletonList(one) : CommandBase.getPlayers(s, who, a[0]);
            }
            String text = join(a, used);
            if (text.isEmpty()) throw new WrongUsageException("/msg <player|@selector> <message>");
            if (to.isEmpty()) { who.sendMessage(new net.minecraft.util.text.TextComponentString("§7Nobody matches " + a[0] + ".")); return; }
            if (!(who instanceof EntityPlayerMP)) {
                for (EntityPlayerMP r : to) Router.tell(r, "§d✉ " + who.getName() + " → you: §r" + text);
                return;
            }
            EntityPlayerMP p = (EntityPlayerMP) who;
            if (to.size() == 1) { Router.handle(p, text, Router.DM, 0, to.get(0).getName()); return; }
            if (!Router.gate(p)) return;                                 // several people: one private line to just them
            Router.Msg m = new Router.Msg();
            m.sender = p; m.text = Router.clean(text); m.mode = Router.GLOBAL; m.only = to;
            Router.route(m);
        }, Selectors.ALL, "pm"));
    }

    /** notifyCommandListener wants an ICommand; any of ours will do */
    private static ICommand this0() { return new Cmd("pridechat", 2, "", (s, w, a) -> {}, null); }

    private ChatCommands() {}
}
