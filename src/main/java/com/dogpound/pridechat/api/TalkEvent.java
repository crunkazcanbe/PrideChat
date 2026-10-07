package com.dogpound.pridechat.api;

import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * CLIENT side, Forge event bus (roadmap #15): a player's line just arrived from a PrideChat server, with where they
 * stood. Map mods can draw a "talking" icon at (x, z) — PrideChat itself shows "Name 43m ↗" for far / unseen talkers.
 * kind = local, whisper, shout, emote, global, channel, radio, zone, dim, announce.
 */
public class TalkEvent extends Event {
    public final String name, kind, text;
    public final int dimension;
    public final double x, y, z, distance;

    public TalkEvent(String name, String kind, String text, int dimension, double x, double y, double z, double distance) {
        this.name = name; this.kind = kind; this.text = text; this.dimension = dimension; this.x = x; this.y = y; this.z = z; this.distance = distance;
    }
}
