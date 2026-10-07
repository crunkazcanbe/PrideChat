package com.dogpound.pridechat;

import net.minecraft.block.Block;
import net.minecraft.block.SoundType;
import net.minecraft.block.material.Material;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.SoundEvents;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.*;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;

/**
 * Radios + devices (roadmap #18/#19). Everything rides on the normal ranged chat: whatever you SAY near/with a device
 * also goes out through it.
 *  - Walkie-talkie: hold it while talking → everyone carrying a walkie/radio on the same frequency within walkieRange
 *    (or anywhere, when both ends are near a radio tower). Right-click = pick the frequency.
 *  - Radio: the same, but the whole dimension. Satellite dishes (one in each dimension) carry radio across dimensions.
 *  - Radio encryption key: carry one with a code → your transmissions are ░▒▓ to anyone without the same code (#34).
 *  - Speaker (block, has a frequency): plays that frequency out loud to everyone nearby, and talking right next to
 *    it transmits on it (a base station).
 *  - Intercom (block, has a channel): talk next to one → heard next to every intercom on that channel close by.
 *  - Telephone (block, gets a number): right-click to dial, the other phone rings, right-click it to pick up; then
 *    talking next to either phone is heard at the other. Right-click again to hang up.
 *  - Megaphone: hold it → your local chat becomes a big shout. PA system: right-click → announcement to the server.
 * Placed blocks live in the world's Store ("devs"), so nothing needs a tile entity.
 */
public final class Devices {
    public static final String RADIO = "radio";

    static final CreativeTabs TAB = new CreativeTabs(PrideChat.MODID) {
        @SideOnly(Side.CLIENT) @Override public ItemStack getTabIconItem() { return new ItemStack(walkie); }
    };

    static Item walkie, radio, megaphone, key;
    static final List<Item> ITEMS = new ArrayList<>();
    static final List<Block> BLOCKS = new ArrayList<>();

    // ------------------------------------------------------------------ registration
    @SubscribeEvent
    public static void blocks(RegistryEvent.Register<Block> e) {
        BLOCKS.add(new DevBlock("radio_tower", null));
        BLOCKS.add(new DevBlock("satellite_dish", new AxisAlignedBB(1 / 16.0, 0, 1 / 16.0, 15 / 16.0, 15 / 16.0, 15 / 16.0)));
        BLOCKS.add(new DevBlock("telephone", new AxisAlignedBB(1 / 16.0, 0, 3 / 16.0, 15 / 16.0, 9 / 16.0, 13 / 16.0)));
        BLOCKS.add(new DevBlock("intercom", null));
        BLOCKS.add(new DevBlock("speaker", null));
        BLOCKS.add(new DevBlock("pa_system", null));
        for (Block b : BLOCKS) e.getRegistry().register(b);
    }

    @SubscribeEvent
    public static void items(RegistryEvent.Register<Item> e) {
        ITEMS.add(walkie = new DevItem("walkie_talkie"));
        ITEMS.add(radio = new DevItem("radio"));
        ITEMS.add(megaphone = new DevItem("megaphone"));
        ITEMS.add(key = new DevItem("radio_key"));
        ITEMS.add(Rooms.wand = new Rooms.Wand());
        for (Block b : BLOCKS) ITEMS.add(new ItemBlock(b).setRegistryName(b.getRegistryName()));
        for (Item i : ITEMS) e.getRegistry().register(i);
    }

    // ------------------------------------------------------------------ items
    static final class DevItem extends Item {
        final String kind;
        DevItem(String kind) {
            this.kind = kind;
            setRegistryName(PrideChat.MODID, kind);
            setUnlocalizedName(PrideChat.MODID + "." + kind);
            setCreativeTab(TAB);
            setMaxStackSize(1);
        }

        @Override
        public ActionResult<ItemStack> onItemRightClick(World w, EntityPlayer p, EnumHand hand) {
            ItemStack s = p.getHeldItem(hand);
            if (this == megaphone) return new ActionResult<>(EnumActionResult.PASS, s);
            if (!w.isRemote && p instanceof EntityPlayerMP) {
                EntityPlayerMP mp = (EntityPlayerMP) p;
                boolean isKey = this == key;
                ask(mp, isKey ? "Secret code (radios with the same code understand each other)" : "Frequency (like 145.5, or a word)",
                        isKey ? tag(s, "code") : freq(s), v -> {
                    ItemStack now = mp.getHeldItem(hand);
                    if (now.getItem() != this) return;
                    String c = clean(v);
                    setTag(now, isKey ? "code" : "freq", c);
                    Router.tell(mp, isKey ? (c.isEmpty() ? "§7Key cleared." : "§a🔑 Key set — your radio talk is now coded.")
                            : "§b📻 Tuned to §f" + (c.isEmpty() ? "1" : c) + "§b.");
                });
            }
            return new ActionResult<>(EnumActionResult.SUCCESS, s);
        }

        @Override
        public String getItemStackDisplayName(ItemStack s) {
            String base = super.getItemStackDisplayName(s);
            if (this == key) return tag(s, "code").isEmpty() ? base : base + " (coded)";
            if (this == walkie || this == radio) return base + " (" + freq(s) + ")";
            return base;
        }

        @SideOnly(Side.CLIENT) @Override
        public void addInformation(ItemStack s, @Nullable World w, List<String> tip, ITooltipFlag flag) {
            if (this == walkie) { tip.add("§7Hold it while you talk: heard on the same frequency"); tip.add("§7within " + ChatConfig.server.walkieRange + " blocks (or between radio towers)."); }
            else if (this == radio) { tip.add("§7Hold it while you talk: heard on the same frequency"); tip.add("§7anywhere in this dimension (satellite dishes: everywhere)."); }
            else if (this == megaphone) tip.add("§7Hold it while you talk: a huge shout (" + ChatConfig.server.megaphoneRange + " blocks).");
            else tip.add("§7Carry it: radio talk is scrambled ░▒▓ for anyone without the same code.");
            if (this != megaphone) tip.add("§8Right-click to set " + (this == key ? "the code" : "the frequency"));
        }
    }

    static String tag(ItemStack s, String k) { return s.hasTagCompound() ? s.getTagCompound().getString(k) : ""; }
    static String freq(ItemStack s) { String f = tag(s, "freq"); return f.isEmpty() ? "1" : f; }
    static void setTag(ItemStack s, String k, String v) { if (!s.hasTagCompound()) s.setTagCompound(new NBTTagCompound()); s.getTagCompound().setString(k, v); }
    static String clean(String v) {
        String c = v.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "");
        return c.length() > 16 ? c.substring(0, 16) : c;
    }

    // ------------------------------------------------------------------ blocks
    static final class DevBlock extends Block {
        final String kind;
        final AxisAlignedBB box;
        DevBlock(String kind, AxisAlignedBB box) {
            super(Material.IRON);
            this.kind = kind; this.box = box;
            setRegistryName(PrideChat.MODID, kind);
            setUnlocalizedName(PrideChat.MODID + "." + kind);
            setCreativeTab(TAB);
            setHardness(2f);
            setResistance(6f);
            setSoundType(SoundType.METAL);
        }
        @Override public boolean isOpaqueCube(IBlockState s) { return box == null && !"radio_tower".equals(kind); }
        @Override public boolean isFullCube(IBlockState s) { return box == null; }
        @Override public AxisAlignedBB getBoundingBox(IBlockState s, IBlockAccess w, BlockPos pos) { return box == null ? FULL_BLOCK_AABB : box; }
        @SideOnly(Side.CLIENT) @Override public BlockRenderLayer getBlockLayer() { return "radio_tower".equals(kind) ? BlockRenderLayer.CUTOUT : BlockRenderLayer.SOLID; }

        @Override
        public void onBlockPlacedBy(World w, BlockPos pos, IBlockState s, EntityLivingBase placer, ItemStack stack) {
            if (!w.isRemote) placed(kind, w, pos, placer instanceof EntityPlayerMP ? (EntityPlayerMP) placer : null);
        }

        @Override
        public void breakBlock(World w, BlockPos pos, IBlockState s) {
            if (!w.isRemote) removed(w, pos);
            super.breakBlock(w, pos, s);
        }

        @Override
        public boolean onBlockActivated(World w, BlockPos pos, IBlockState s, EntityPlayer p, EnumHand hand, EnumFacing f, float hx, float hy, float hz) {
            if (!w.isRemote && hand == EnumHand.MAIN_HAND && p instanceof EntityPlayerMP) use(kind, w, pos, (EntityPlayerMP) p);
            return true;
        }
    }

    // ------------------------------------------------------------------ placed devices (world store)
    static String key(int dim, BlockPos p) { return dim + ":" + p.getX() + ":" + p.getY() + ":" + p.getZ(); }

    static List<NBTTagCompound> placed(String kind) {
        List<NBTTagCompound> out = new ArrayList<>();
        if (Router.server() == null) return out;
        NBTTagCompound devs = Store.section("devs");
        for (String k : devs.getKeySet()) { NBTTagCompound d = devs.getCompoundTag(k); if (kind == null || kind.equals(d.getString("k"))) out.add(d); }
        return out;
    }

    static BlockPos pos(NBTTagCompound d) { return new BlockPos(d.getInteger("x"), d.getInteger("y"), d.getInteger("z")); }
    static double distSq(EntityPlayer p, NBTTagCompound d) { return p.getDistanceSq(d.getInteger("x") + 0.5, d.getInteger("y") + 0.5, d.getInteger("z") + 0.5); }

    static void placed(String kind, World w, BlockPos pos, EntityPlayerMP by) {
        NBTTagCompound d = new NBTTagCompound();
        d.setString("k", kind);
        d.setInteger("d", w.provider.getDimension());
        d.setInteger("x", pos.getX()); d.setInteger("y", pos.getY()); d.setInteger("z", pos.getZ());
        d.setString("f", "1");
        if ("telephone".equals(kind)) {
            Set<String> used = new HashSet<>();
            for (NBTTagCompound o : placed("telephone")) used.add(o.getString("n"));
            String n;
            Random r = new Random();
            do n = String.valueOf(1000 + r.nextInt(9000)); while (used.contains(n));
            d.setString("n", n);
            if (by != null) Router.tell(by, "§d☎ This phone's number is §f" + n + "§d. Right-click it to dial another phone.");
        }
        Store.section("devs").setTag(key(d.getInteger("d"), pos), d);
        Store.dirty();
        if (by != null && !"telephone".equals(kind)) Router.tell(by, "§7" + help(kind));
    }

    static void removed(World w, BlockPos pos) {
        NBTTagCompound devs = Store.section("devs");
        String k = key(w.provider.getDimension(), pos);
        NBTTagCompound d = devs.getCompoundTag(k);
        if ("telephone".equals(d.getString("k"))) hangUp(d.getString("n"));
        devs.removeTag(k);
        Store.dirty();
    }

    static NBTTagCompound at(World w, BlockPos pos) {
        NBTTagCompound devs = Store.section("devs");
        String k = key(w.provider.getDimension(), pos);
        return devs.hasKey(k) ? devs.getCompoundTag(k) : null;
    }

    static String help(String kind) {
        switch (kind) {
            case "radio_tower": return "Radio tower: walkie-talkies within " + ChatConfig.server.towerRadius + " blocks reach anyone near any other tower in this dimension.";
            case "satellite_dish": return "Satellite dish: with a dish in each dimension, radios reach across dimensions.";
            case "intercom": return "Intercom: talk next to it → heard next to every intercom on the same channel within " + ChatConfig.server.intercomRange + " blocks. Right-click: channel.";
            case "speaker": return "Speaker: plays its frequency out loud (" + ChatConfig.server.speakerRange + " blocks), and talking right next to it transmits. Right-click: frequency.";
            case "pa_system": return "PA system: right-click to make an announcement to the whole server.";
            default: return "";
        }
    }

    static void use(String kind, World w, BlockPos pos, EntityPlayerMP p) {
        NBTTagCompound d = at(w, pos);
        if (d == null) { placed(kind, w, pos, null); d = at(w, pos); }       // placed before PrideChat 0.3 / by a machine
        final NBTTagCompound dev = d;
        switch (kind) {
            case "intercom": case "speaker":
                ask(p, ("intercom".equals(kind) ? "Intercom channel" : "Speaker frequency") + " (now " + dev.getString("f") + ")", dev.getString("f"), v -> {
                    String c = clean(v);
                    dev.setString("f", c.isEmpty() ? "1" : c);
                    Store.dirty();
                    Router.tell(p, "§b" + ("intercom".equals(kind) ? "🔊 Intercom channel " : "📻 Speaker tuned to ") + "§f" + dev.getString("f"));
                });
                break;
            case "telephone": phone(dev, p); break;
            case "pa_system":
                if (!Router.perm(p, "pridechat.pa")) { Router.tell(p, "§cOnly staff can use the PA system here."); return; }
                ask(p, "📢 Announcement for the whole server", "", v -> {
                    if (v.trim().isEmpty() || !Router.gate(p)) return;
                    Router.Msg m = new Router.Msg();
                    m.sender = p; m.text = v.trim(); m.mode = Router.ANNOUNCE;
                    Router.route(m);
                    w.playSound(null, pos, SoundEvents.BLOCK_NOTE_BELL, SoundCategory.BLOCKS, 2f, 0.8f);
                });
                break;
            case "radio_tower": {
                int n = 0;
                for (NBTTagCompound t : placed("radio_tower")) if (t.getInteger("d") == dev.getInteger("d")) n++;
                Router.tell(p, "§7" + help(kind) + " §8(" + n + " tower" + (n == 1 ? "" : "s") + " in this dimension)");
                break;
            }
            default: Router.tell(p, "§7" + help(kind)); break;
        }
    }

    // ------------------------------------------------------------------ asking a player for a line of text
    private static final Map<UUID, Consumer<String>> ASKS = new HashMap<>();

    static void ask(EntityPlayerMP p, String question, String start, Consumer<String> answer) {
        ASKS.put(p.getUniqueID(), answer);
        NBTTagCompound t = new NBTTagCompound();
        t.setString("q", question);
        t.setString("v", start == null ? "" : start);
        Net.send(p, Net.S_ASK, t, "");
    }

    static void answered(EntityPlayerMP p, String text) {
        Consumer<String> a = ASKS.remove(p.getUniqueID());
        if (a != null) a.accept(text.length() > 300 ? text.substring(0, 300) : text);
    }

    // ------------------------------------------------------------------ telephones
    /** ringing phone number → {caller number, ticks left}; connected calls both ways */
    private static final Map<String, Object[]> RING = new HashMap<>();
    private static final Map<String, String> CALL = new HashMap<>();

    static NBTTagCompound phoneByNumber(String n) {
        for (NBTTagCompound d : placed("telephone")) if (n.equals(d.getString("n"))) return d;
        return null;
    }

    private static void phone(NBTTagCompound d, EntityPlayerMP p) {
        String n = d.getString("n");
        if (CALL.containsKey(n)) { hangUp(n); return; }
        Object[] ring = RING.remove(n);
        if (ring != null) {
            String from = (String) ring[0];
            CALL.put(n, from); CALL.put(from, n);
            near(d, "§a☎ Connected with " + from + " — talk next to the phone. Right-click to hang up.");
            NBTTagCompound o = phoneByNumber(from);
            if (o != null) near(o, "§a☎ " + n + " picked up — talk next to the phone.");
            return;
        }
        ask(p, "☎ Dial a number (this phone is " + n + ")", "", v -> {
            String to = v.trim();
            NBTTagCompound o = phoneByNumber(to);
            if (o == null) { Router.tell(p, "§c☎ No phone has the number " + to + "."); return; }
            if (to.equals(n) || CALL.containsKey(to) || RING.containsKey(to)) { Router.tell(p, "§e☎ Busy — try again later."); return; }
            RING.put(to, new Object[]{n, 30 * 20});
            Router.tell(p, "§d☎ Calling " + to + "…");
        });
    }

    static void hangUp(String n) {
        String other = CALL.remove(n);
        RING.remove(n);
        RING.values().removeIf(r -> n.equals(r[0]));
        if (other == null) return;
        CALL.remove(other);
        for (String x : new String[]{n, other}) { NBTTagCompound d = phoneByNumber(x); if (d != null) near(d, "§7☎ Call ended."); }
    }

    /** a line to everyone standing near a placed device */
    private static void near(NBTTagCompound d, String text) {
        for (EntityPlayerMP r : Router.server().getPlayerList().getPlayers())
            if (r.dimension == d.getInteger("d") && distSq(r, d) <= 64) Router.tell(r, text);
    }

    @SubscribeEvent
    public static void tick(TickEvent.ServerTickEvent e) {
        if (e.phase != TickEvent.Phase.END || RING.isEmpty()) return;
        for (Iterator<Map.Entry<String, Object[]>> it = RING.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, Object[]> en = it.next();
            int left = (int) en.getValue()[1] - 1;
            en.getValue()[1] = left;
            NBTTagCompound d = phoneByNumber(en.getKey());
            if (d == null || left <= 0) {
                it.remove();
                NBTTagCompound c = phoneByNumber((String) en.getValue()[0]);
                if (c != null) near(c, "§7☎ No answer.");
                continue;
            }
            if (left % 40 == 0) {
                World w = Router.server().getWorld(d.getInteger("d"));
                if (w != null) {
                    w.playSound(null, pos(d), SoundEvents.BLOCK_NOTE_BELL, SoundCategory.BLOCKS, 1.5f, 1.4f);
                    w.playSound(null, pos(d), SoundEvents.BLOCK_NOTE_BELL, SoundCategory.BLOCKS, 1.5f, 1.6f);
                }
                if (left == 560 || left % 200 == 0) near(d, "§d☎ Phone " + en.getKey() + " is ringing (from " + en.getValue()[0] + ") — right-click it to answer.");
            }
        }
    }

    // ------------------------------------------------------------------ the chat side
    /** a message held in front of a megaphone becomes a shout (called before routing) */
    static void megaphone(Router.Msg m) {
        if (!ChatConfig.server.devices || m.sender == null) return;
        if (!(Router.LOCAL.equals(m.mode) || Router.SHOUT.equals(m.mode))) return;
        if (m.sender.getHeldItemMainhand().getItem() != megaphone && m.sender.getHeldItemOffhand().getItem() != megaphone) return;
        m.mode = Router.SHOUT;
        m.range = Math.min(ChatConfig.server.maxRange, ChatConfig.server.megaphoneRange);
    }

    private static ItemStack heldRadio(EntityPlayerMP p) {
        for (ItemStack s : new ItemStack[]{p.getHeldItemMainhand(), p.getHeldItemOffhand()})
            if (s.getItem() == radio || s.getItem() == walkie) return s;
        return null;
    }

    /** the code on a key this player carries ("" = none) */
    private static String keyCode(EntityPlayerMP p) {
        for (ItemStack s : p.inventory.mainInventory) if (s.getItem() == key && !tag(s, "code").isEmpty()) return tag(s, "code");
        for (ItemStack s : p.inventory.offHandInventory) if (s.getItem() == key && !tag(s, "code").isEmpty()) return tag(s, "code");
        return "";
    }

    private static boolean hasCode(EntityPlayerMP p, String code) {
        for (ItemStack s : p.inventory.mainInventory) if (s.getItem() == key && code.equals(tag(s, "code"))) return true;
        for (ItemStack s : p.inventory.offHandInventory) if (s.getItem() == key && code.equals(tag(s, "code"))) return true;
        return false;
    }

    /** how far this player can hear a frequency: -1 anywhere in the dimension, else blocks; -2 = can't hear it */
    private static int listens(EntityPlayerMP r, String f) {
        int best = -2;
        for (ItemStack s : r.inventory.mainInventory) {
            if (s.getItem() == radio && f.equals(freq(s))) return -1;
            if (s.getItem() == walkie && f.equals(freq(s))) best = ChatConfig.server.walkieRange;
        }
        for (ItemStack s : r.inventory.offHandInventory) {
            if (s.getItem() == radio && f.equals(freq(s))) return -1;
            if (s.getItem() == walkie && f.equals(freq(s))) best = ChatConfig.server.walkieRange;
        }
        int sr = ChatConfig.server.speakerRange;
        for (NBTTagCompound d : placed("speaker")) if (d.getInteger("d") == r.dimension && f.equals(d.getString("f")) && distSq(r, d) <= sr * sr) return -1;
        return best;
    }

    private static boolean nearTower(EntityPlayerMP p) {
        int tr = ChatConfig.server.towerRadius;
        for (NBTTagCompound d : placed("radio_tower")) if (d.getInteger("d") == p.dimension && distSq(p, d) <= (double) tr * tr) return true;
        return false;
    }

    private static boolean dish(int dim) {
        for (NBTTagCompound d : placed("satellite_dish")) if (d.getInteger("d") == dim) return true;
        return false;
    }

    private static boolean reaches(EntityPlayerMP a, int ra, EntityPlayerMP b, int rb) {
        if (a.dimension != b.dimension) return dish(a.dimension) && dish(b.dimension);
        if (ra == -1 && rb == -1) return true;
        int lim = ra == -1 ? rb : rb == -1 ? ra : Math.min(ra, rb);
        return a.getDistanceSq(b) <= (double) lim * lim || nearTower(a) && nearTower(b);
    }

    /** the nearest placed device of a kind within r blocks of the player, or null */
    private static NBTTagCompound next(EntityPlayerMP p, String kind, int r) {
        NBTTagCompound best = null;
        double bd = (double) r * r;
        for (NBTTagCompound d : placed(kind)) {
            if (d.getInteger("d") != p.dimension) continue;
            double ds = distSq(p, d);
            if (ds <= bd) { bd = ds; best = d; }
        }
        return best;
    }

    /**
     * After a ranged message was delivered normally: send it on through radios, speakers, intercoms and phones.
     * `line` is the "Name: text" component, `to` everyone who already got it (added to). Returns how many more heard it.
     */
    static int relay(Router.Msg m, ITextComponent line, List<EntityPlayerMP> to) { return relay(m, line, to, null); }

    /**
     * Who radios / intercoms / phones carry this player's voice to, without sending anything: listener → "radio 7",
     * "intercom A", "phone 555", with "!" in front when it's encrypted and they don't have the code (the voice API, #49).
     */
    static Map<EntityPlayerMP, String> reached(EntityPlayerMP p, Collection<EntityPlayerMP> already) {
        Map<EntityPlayerMP, String> out = new LinkedHashMap<>();
        Router.Msg m = new Router.Msg();
        m.sender = p; m.mode = Router.LOCAL; m.range = Router.rangeFor(Router.LOCAL, 0); m.text = "";
        relay(m, new TextComponentString(""), new ArrayList<>(already), out);
        return out;
    }

    private static int relay(Router.Msg m, ITextComponent line, List<EntityPlayerMP> to, Map<EntityPlayerMP, String> dry) {
        if (!ChatConfig.server.devices || m.sender == null || m.shadow || m.range == 0 || Router.server() == null) return 0;
        EntityPlayerMP p = m.sender;
        Set<EntityPlayerMP> got = new HashSet<>(to);
        int before = got.size();
        List<EntityPlayerMP> all = Router.server().getPlayerList().getPlayers();
        String code = keyCode(p);
        // radio: a walkie / radio in hand, or standing right at a speaker
        String f = null;
        int range = 0;
        ItemStack held = heldRadio(p);
        if (held != null) { f = freq(held); range = held.getItem() == radio ? -1 : ChatConfig.server.walkieRange; }
        else { NBTTagCompound sp = next(p, "speaker", 3); if (sp != null) { f = sp.getString("f"); range = -1; } }
        if (f != null) {
            for (EntityPlayerMP r : all) {
                if (got.contains(r)) continue;
                int rr = listens(r, f);
                if (rr == -2 || !reaches(p, range, r, rr)) continue;
                boolean locked = !code.isEmpty() && !hasCode(r, code);
                got.add(r);
                if (dry != null) { dry.put(r, (locked ? "!" : "") + "radio " + f); continue; }
                ITextComponent c = new TextComponentString("§b[📻 " + f + "] §r").appendSibling(line.createCopy());
                if (locked) c = Router.scrambled("§b[📻 " + f + "] ", line);
                Router.sendAs(r, m, c, RADIO, f, (float) Math.sqrt(r.getDistanceSq(p)));
            }
        }
        // intercom: everyone next to an intercom on the same channel nearby
        NBTTagCompound ic = next(p, "intercom", 4);
        if (ic != null) {
            int ir = ChatConfig.server.intercomRange;
            List<NBTTagCompound> outs = new ArrayList<>();
            for (NBTTagCompound o : placed("intercom")) {
                if (o.getInteger("d") != p.dimension || !o.getString("f").equals(ic.getString("f"))) continue;
                if (pos(o).distanceSq(pos(ic)) <= (double) ir * ir) outs.add(o);
            }
            for (EntityPlayerMP r : all) {
                if (got.contains(r) || r.dimension != p.dimension) continue;
                for (NBTTagCompound o : outs) if (distSq(r, o) <= 36) {
                    if (dry != null) { dry.put(r, "intercom " + ic.getString("f")); got.add(r); break; }
                    Router.sendAs(r, m, new TextComponentString("§e[🔊 Intercom " + ic.getString("f") + "] §r").appendSibling(line.createCopy()), RADIO, "intercom " + ic.getString("f"), 0);
                    got.add(r);
                    break;
                }
            }
        }
        // telephone: the other end of the call
        NBTTagCompound ph = next(p, "telephone", 3);
        String other = ph == null ? null : CALL.get(ph.getString("n"));
        NBTTagCompound o = other == null ? null : phoneByNumber(other);
        if (o != null) {
            int pr = ChatConfig.server.phoneRange;
            for (EntityPlayerMP r : all) {
                if (got.contains(r) || r.dimension != o.getInteger("d") || distSq(r, o) > (double) pr * pr) continue;
                if (dry != null) { dry.put(r, "phone " + ph.getString("n")); got.add(r); continue; }
                Router.sendAs(r, m, new TextComponentString("§d[☎ " + ph.getString("n") + "] §r").appendSibling(line.createCopy()), RADIO, "phone " + ph.getString("n"), 0);
                got.add(r);
            }
        }
        for (EntityPlayerMP r : got) if (!to.contains(r)) to.add(r);
        return got.size() - before;
    }

    private Devices() {}
}
