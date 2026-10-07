package com.dogpound.pridechat;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;
import net.minecraft.world.storage.MapStorage;
import net.minecraft.world.storage.WorldSavedData;

/**
 * PrideChat's saved data for the whole world (data/pridechat.dat in the save): placed devices (radios, phones…),
 * rooms and zones. One NBT root with a section per feature; call dirty() after changing a section.
 */
public final class Store extends WorldSavedData {
    private static final String NAME = "pridechat";
    NBTTagCompound root = new NBTTagCompound();

    public Store(String name) { super(name); }

    @Override public void readFromNBT(NBTTagCompound nbt) { root = nbt.getCompoundTag("root"); }
    @Override public NBTTagCompound writeToNBT(NBTTagCompound nbt) { nbt.setTag("root", root); return nbt; }

    static Store get() {
        World w = Router.server().getWorld(0);
        MapStorage ms = w.getMapStorage();
        Store s = (Store) ms.getOrLoadData(Store.class, NAME);
        if (s == null) { s = new Store(NAME); ms.setData(NAME, s); }
        return s;
    }

    /** one feature's compound (made on first use) */
    static NBTTagCompound section(String name) {
        Store s = get();
        if (!s.root.hasKey(name, 10)) s.root.setTag(name, new NBTTagCompound());
        return s.root.getCompoundTag(name);
    }

    static void dirty() { get().markDirty(); }
}
