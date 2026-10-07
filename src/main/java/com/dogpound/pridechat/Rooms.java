package com.dogpound.pridechat;

import com.dogpound.pridechat.api.PrideChatEvent;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.*;

/**
 * Rooms, soundproof rooms and zones (roadmap #16/#17), cuboids saved with the world (Store section "rooms").
 *  - room: what's said inside stays inside; talk from outside comes in muffled (faded).
 *  - soundproof: nothing gets out AND nothing gets in.
 *  - zone (a town, a base, a region): doesn't change who hears you nearby, but /zone talks to everyone inside it,
 *    however far. /dimchat talks to everyone in your dimension.
 * Made with the Room Marker: right-click a block = corner A, sneak + right-click = corner B, right-click the air = save
 * (asks name + kind). /room list | here | delete <name> | kind <name> <room/soundproof/zone>.
 * Rooms work through PrideChatEvent.Deliver — the same hook any other mod can use.
 */
public final class Rooms {
    static final String ROOM = "room", SOUNDPROOF = "soundproof", ZONE = "zone";
    static Item wand;

    // ------------------------------------------------------------------ the store
    static NBTTagCompound all() { return Store.section("rooms"); }

    static boolean inside(NBTTagCompound r, int dim, double x, double y, double z) {
        return r.getInteger("d") == dim
                && x >= r.getInteger("x1") && x < r.getInteger("x2") + 1
                && y >= r.getInteger("y1") && y < r.getInteger("y2") + 1
                && z >= r.getInteger("z1") && z < r.getInteger("z2") + 1;
    }

    static long volume(NBTTagCompound r) {
        return (long) (r.getInteger("x2") - r.getInteger("x1") + 1) * (r.getInteger("y2") - r.getInteger("y1") + 1) * (r.getInteger("z2") - r.getInteger("z1") + 1);
    }

    /** the smallest room / soundproof room (zone = false) or zone (zone = true) around a player, or null */
    static NBTTagCompound around(EntityPlayer p, boolean zone) {
        if (Router.server() == null) return null;
        NBTTagCompound all = all(), best = null;
        for (String k : all.getKeySet()) {
            NBTTagCompound r = all.getCompoundTag(k);
            if (ZONE.equals(r.getString("k")) != zone) continue;
            if (!inside(r, p.dimension, p.posX, p.posY + 0.5, p.posZ)) continue;
            if (best == null || volume(r) < volume(best)) best = r;
        }
        return best;
    }

    static String zoneOf(EntityPlayer p) { NBTTagCompound z = around(p, true); return z == null ? "" : z.getString("n"); }

    // ------------------------------------------------------------------ who hears what
    @SubscribeEvent
    public static void deliver(PrideChatEvent.Deliver e) {
        Router.Msg m = e.msg;
        if (!ChatConfig.server.rooms || m.range <= 0 || m.sender == null || e.listener == m.sender || Router.CHANNEL.equals(m.mode)) return;
        NBTTagCompound a = around(m.sender, false), b = around(e.listener, false);
        String an = a == null ? "" : a.getString("n"), bn = b == null ? "" : b.getString("n");
        if (an.equals(bn)) return;                                        // same room, or both outside
        if (a != null) { e.setCanceled(true); return; }                   // what's said in a room stays in it
        if (SOUNDPROOF.equals(b.getString("k"))) { e.setCanceled(true); return; }
        e.band = Math.max(e.band, 1);                                     // from outside, through the walls: muffled
    }

    // ------------------------------------------------------------------ making rooms
    static String make(EntityPlayerMP p, String name, String kind, int dim, BlockPos a, BlockPos b) {
        String n = name.trim();
        if (!n.matches("[A-Za-z0-9 _'-]{1,32}")) return "§cRoom names: 1-32 letters, numbers, spaces, ' _ -";
        if (!Arrays.asList(ROOM, SOUNDPROOF, ZONE).contains(kind)) return "§cThe kind is room, soundproof or zone.";
        if (ZONE.equals(kind) && !Router.perm(p, "pridechat.zones")) return "§cOnly staff can make zones here.";
        NBTTagCompound all = all();
        String key = n.toLowerCase(Locale.ROOT);
        if (all.hasKey(key)) {
            NBTTagCompound old = all.getCompoundTag(key);
            if (!old.getString("o").equals(p.getUniqueID().toString()) && !op(p)) return "§c\"" + n + "\" belongs to someone else.";
        }
        long vol = (long) (Math.abs(a.getX() - b.getX()) + 1) * (Math.abs(a.getY() - b.getY()) + 1) * (Math.abs(a.getZ() - b.getZ()) + 1);
        if (!ZONE.equals(kind) && vol > ChatConfig.server.maxRoomVolume && !op(p)) return "§cThat room is too big (" + vol + " blocks, most " + ChatConfig.server.maxRoomVolume + "). Zones can be bigger.";
        NBTTagCompound r = new NBTTagCompound();
        r.setString("n", n); r.setString("k", kind); r.setString("o", p.getUniqueID().toString());
        r.setInteger("d", dim);
        r.setInteger("x1", Math.min(a.getX(), b.getX())); r.setInteger("x2", Math.max(a.getX(), b.getX()));
        r.setInteger("y1", Math.min(a.getY(), b.getY())); r.setInteger("y2", Math.max(a.getY(), b.getY()));
        r.setInteger("z1", Math.min(a.getZ(), b.getZ())); r.setInteger("z2", Math.max(a.getZ(), b.getZ()));
        all.setTag(key, r);
        Store.dirty();
        return "§a" + describe(r) + " saved.";
    }

    static boolean op(EntityPlayerMP p) { return Router.server().getPlayerList().canSendCommands(p.getGameProfile()); }

    static String describe(NBTTagCompound r) {
        String k = r.getString("k");
        String icon = ZONE.equals(k) ? "⌂ zone" : SOUNDPROOF.equals(k) ? "🔇 soundproof room" : "🚪 room";
        return "§f" + r.getString("n") + " §7(" + icon + ", " + (r.getInteger("x2") - r.getInteger("x1") + 1) + "×" + (r.getInteger("y2") - r.getInteger("y1") + 1)
                + "×" + (r.getInteger("z2") - r.getInteger("z1") + 1) + " at " + r.getInteger("x1") + " " + r.getInteger("y1") + " " + r.getInteger("z1") + ", dim " + r.getInteger("d") + ")";
    }

    static String delete(EntityPlayerMP p, String name) {
        NBTTagCompound all = all();
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (!all.hasKey(key)) return "§cNo room called \"" + name + "\".";
        if (p != null && !all.getCompoundTag(key).getString("o").equals(p.getUniqueID().toString()) && !op(p)) return "§cThat room isn't yours.";
        all.removeTag(key);
        Store.dirty();
        return "§eRemoved \"" + name + "\".";
    }

    static String kind(EntityPlayerMP p, String name, String kind) {
        NBTTagCompound r = all().getCompoundTag(name.trim().toLowerCase(Locale.ROOT));
        if (!r.hasKey("n")) return "§cNo room called \"" + name + "\".";
        return make(p, r.getString("n"), kind.toLowerCase(Locale.ROOT), r.getInteger("d"),
                new BlockPos(r.getInteger("x1"), r.getInteger("y1"), r.getInteger("z1")), new BlockPos(r.getInteger("x2"), r.getInteger("y2"), r.getInteger("z2")));
    }

    static List<String> list(int dim) {
        List<String> out = new ArrayList<>();
        NBTTagCompound all = all();
        for (String k : all.getKeySet()) if (dim == Integer.MIN_VALUE || all.getCompoundTag(k).getInteger("d") == dim) out.add(describe(all.getCompoundTag(k)));
        return out;
    }

    // ------------------------------------------------------------------ the Room Marker
    static final class Wand extends Item {
        Wand() {
            setRegistryName(PrideChat.MODID, "room_marker");
            setUnlocalizedName(PrideChat.MODID + ".room_marker");
            setCreativeTab(Devices.TAB);
            setMaxStackSize(1);
        }

        @Override
        public EnumActionResult onItemUse(EntityPlayer p, World w, BlockPos pos, EnumHand hand, EnumFacing f, float hx, float hy, float hz) {
            if (w.isRemote) return EnumActionResult.SUCCESS;
            ItemStack s = p.getHeldItem(hand);
            String c = p.isSneaking() ? "b" : "a";
            NBTTagCompound t = s.hasTagCompound() ? s.getTagCompound() : new NBTTagCompound();
            if (t.hasKey("d") && t.getInteger("d") != p.dimension) { t.removeTag("a"); t.removeTag("b"); }   // other dimension: start over
            t.setIntArray(c, new int[]{pos.getX(), pos.getY(), pos.getZ()});
            t.setInteger("d", p.dimension);
            s.setTagCompound(t);
            String size = "";
            if (t.hasKey("a") && t.hasKey("b")) {
                int[] a = t.getIntArray("a"), b = t.getIntArray("b");
                size = " §7— " + (Math.abs(a[0] - b[0]) + 1) + "×" + (Math.abs(a[1] - b[1]) + 1) + "×" + (Math.abs(a[2] - b[2]) + 1) + ". Right-click the air to save it.";
            }
            Router.tell((EntityPlayerMP) p, "§dCorner " + c.toUpperCase(Locale.ROOT) + " §f" + pos.getX() + " " + pos.getY() + " " + pos.getZ() + size
                    + (size.isEmpty() ? " §7(" + (p.isSneaking() ? "" : "sneak + ") + "right-click for the other corner)" : ""));
            return EnumActionResult.SUCCESS;
        }

        @Override
        public ActionResult<ItemStack> onItemRightClick(World w, EntityPlayer p, EnumHand hand) {
            ItemStack s = p.getHeldItem(hand);
            if (w.isRemote || !(p instanceof EntityPlayerMP)) return new ActionResult<>(EnumActionResult.SUCCESS, s);
            EntityPlayerMP mp = (EntityPlayerMP) p;
            NBTTagCompound t = s.getTagCompound();
            if (t == null || !t.hasKey("a") || !t.hasKey("b")) {
                NBTTagCompound in = around(p, false), z = around(p, true);
                Router.tell(mp, "§7Here: " + (in == null ? "outside any room" : describe(in)) + (z == null ? "" : " §7in zone §f" + z.getString("n"))
                        + "§7. Right-click a block = corner A, sneak + right-click = corner B.");
                return new ActionResult<>(EnumActionResult.SUCCESS, s);
            }
            int[] a = t.getIntArray("a"), b = t.getIntArray("b");
            int dim = t.getInteger("d");
            Devices.ask(mp, "Name this room", "", name -> {
                if (name.trim().isEmpty()) return;
                Devices.ask(mp, "Kind: room (talk stays in), soundproof (nothing in or out), zone (/zone chat)", ROOM, kind -> {
                    String msg = make(mp, name, kind.trim().toLowerCase(Locale.ROOT), dim, new BlockPos(a[0], a[1], a[2]), new BlockPos(b[0], b[1], b[2]));
                    Router.tell(mp, msg);
                    if (msg.startsWith("§a")) { ItemStack now = mp.getHeldItem(hand); if (now.getItem() == wand) { now.getTagCompound().removeTag("a"); now.getTagCompound().removeTag("b"); } }
                });
            });
            return new ActionResult<>(EnumActionResult.SUCCESS, s);
        }

        @SideOnly(Side.CLIENT) @Override
        public void addInformation(ItemStack s, @Nullable World w, List<String> tip, ITooltipFlag flag) {
            tip.add("§7Right-click a block: corner A · sneak + right-click: corner B");
            tip.add("§7Right-click the air: save as a room, soundproof room or zone");
            tip.add("§8Rooms keep talk inside; soundproof ones let nothing in or out.");
        }
    }

    private Rooms() {}
}
