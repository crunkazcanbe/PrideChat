package com.dogpound.pridechat;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import java.util.*;

/**
 * Channels (roadmap #1/#33): the server's fixed ones from the config (staff by permission, trade, help, rp…) plus
 * groups players make themselves with /party (invite-only). Each has a tag, an optional permission and an optional
 * range (rp = local roleplay). PrideChat clients get the list so each channel can have its own tab.
 */
public final class Channels {
    public static final class Ch {
        public final String name;
        public String tag = "", perm = "", key = "";
        public int range, dim = Integer.MIN_VALUE;
        public boolean open = true, group;
        public UUID owner;
        public final Set<UUID> members = new HashSet<>(), left = new HashSet<>(), invited = new HashSet<>();
        Ch(String name) { this.name = name; }

        public boolean isMember(EntityPlayerMP p) {
            if (!perm.isEmpty() && !Router.perm(p, perm)) return false;
            if (group || !open) return members.contains(p.getUniqueID());
            return !left.contains(p.getUniqueID());
        }
    }

    private static final Map<String, Ch> ALL = new LinkedHashMap<>();
    private static String loadedFrom = "";

    static void load() {
        String key = String.join("\n", ChatConfig.server.channels);
        if (key.equals(loadedFrom)) return;
        loadedFrom = key;
        ALL.values().removeIf(c -> !c.group);
        for (String line : ChatConfig.server.channels) {
            String[] p = line.split("\\s*\\|\\s*", -1);
            if (p.length < 1 || p[0].trim().isEmpty()) continue;
            Ch c = new Ch(p[0].trim().toLowerCase(Locale.ROOT));
            if (p.length > 1) c.tag = p[1].trim().isEmpty() ? "" : p[1].trim() + " ";
            if (p.length > 2) c.perm = p[2].trim();
            if (p.length > 3) try { c.range = Integer.parseInt(p[3].trim()); } catch (NumberFormatException ignored) {}
            if (p.length > 4) c.open = !"invite".equalsIgnoreCase(p[4].trim());
            if (p.length > 5) c.key = p[5].trim();                          // an item you must carry to understand it (encrypted, #34)
            if (p.length > 6 && !p[6].trim().isEmpty()) try { c.dim = Integer.parseInt(p[6].trim()); } catch (NumberFormatException ignored) {}   // only in one dimension (#16)
            ALL.put(c.name, c);
        }
    }

    public static Ch get(String name) { load(); return name == null ? null : ALL.get(name.toLowerCase(Locale.ROOT)); }

    public static Collection<Ch> all() { load(); return ALL.values(); }

    static void joined(EntityPlayerMP p) { load(); }

    /** tell a PrideChat client which channels it's in (for its tabs) */
    static void sendList(EntityPlayerMP p) {
        if (!Router.hasPrideChat(p)) return;
        NBTTagList l = new NBTTagList();
        for (Ch c : all()) {
            if (!c.isMember(p)) continue;
            NBTTagCompound t = new NBTTagCompound();
            t.setString("n", c.name);
            t.setInteger("r", c.range);
            t.setBoolean("g", c.group);
            l.appendTag(t);
        }
        NBTTagCompound o = new NBTTagCompound();
        o.setTag("list", l);
        Net.send(p, Net.S_CHANNELS, o, "");
    }

    /** the Channel event (#37): false = another mod said no */
    private static boolean allowed(EntityPlayerMP p, String name, String action) {
        return !net.minecraftforge.common.MinecraftForge.EVENT_BUS.post(new com.dogpound.pridechat.api.PrideChatEvent.Channel(p, name, action));
    }

    static String join(EntityPlayerMP p, String name) {
        Ch c = get(name);
        if (c == null) return "§cNo channel #" + name + " (/ch list)";
        if (!allowed(p, c.name, "join")) return "§cYou can't join #" + c.name + " right now.";
        if (!c.perm.isEmpty() && !Router.perm(p, c.perm)) return "§cYou can't join #" + c.name + ".";
        if ((c.group || !c.open) && !c.invited.contains(p.getUniqueID()) && !c.members.contains(p.getUniqueID())) return "§c#" + c.name + " is invite-only.";
        c.left.remove(p.getUniqueID());
        c.members.add(p.getUniqueID());
        c.invited.remove(p.getUniqueID());
        sendList(p);
        return "§aYou joined #" + c.name + ". Talk there with its tab or /ch " + c.name + " <message>.";
    }

    static String leave(EntityPlayerMP p, String name) {
        Ch c = get(name);
        if (c == null) return "§cNo channel #" + name;
        if (!allowed(p, c.name, "leave")) return "§cYou can't leave #" + c.name + " right now.";
        c.members.remove(p.getUniqueID());
        if (!c.group && c.open) c.left.add(p.getUniqueID());
        if (c.group && c.members.isEmpty()) ALL.remove(c.name);
        sendList(p);
        return "§eYou left #" + c.name + ".";
    }

    static String create(EntityPlayerMP p, String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (!n.matches("[a-z0-9_]{2,16}")) return "§cGroup names: 2-16 letters, numbers or _.";
        load();
        if (ALL.containsKey(n)) return "§c#" + n + " already exists.";
        if (!allowed(p, n, "create")) return "§cYou can't make #" + n + " right now.";
        Ch c = new Ch(n);
        c.group = true; c.open = false; c.owner = p.getUniqueID();
        c.tag = "&d[#" + n + "] ";
        c.members.add(p.getUniqueID());
        ALL.put(n, c);
        sendList(p);
        return "§aMade the group #" + n + ". /party invite <player> to add people.";
    }

    static String invite(EntityPlayerMP p, String name, EntityPlayerMP who) {
        Ch c = get(name);
        if (c == null || !c.members.contains(p.getUniqueID())) return "§cYou're not in #" + name + ".";
        c.invited.add(who.getUniqueID());
        Router.tell(who, "§d" + p.getName() + " invited you to #" + c.name + " — §f/party join " + c.name);
        return "§aInvited " + who.getName() + " to #" + c.name + ".";
    }

    /** the first group this player is in (for /party chat without a name) */
    static Ch groupOf(EntityPlayerMP p) {
        for (Ch c : all()) if (c.group && c.members.contains(p.getUniqueID())) return c;
        return null;
    }

    private Channels() {}
}
