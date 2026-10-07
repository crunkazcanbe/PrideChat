package com.dogpound.pridechat.api;

import com.dogpound.pridechat.Router;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * Hooks for other mods (roadmap #37), fired on the Forge event bus on the server. Full list + examples: docs/API.md.
 * Send: before routing — change msg.text / mode / range / channel, or cancel.
 * Range: how far a spoken message reaches — change range.
 * Context: what the talker is doing (underwater, fire…) — add / remove contexts.
 * Filter: after moderation looked — change the verdict, or cancel to ignore it.
 * Deliver: once per listener — change the band (0 clear, 1 faded, 2 faint) or cancel for that player (radios, rooms, regions…).
 * Mention: a listener is mentioned in it — cancel = no ping / highlight for them.
 * Talked: after a message went out — who heard it, and the sender's position (web maps / Dynmap "talking" markers, #15).
 * Typing (cancellable) and Channel (join / leave / create, cancellable) don't carry a message (msg is null).
 * Client side see {@link TalkEvent} and {@link BubbleEvent}.
 */
public class PrideChatEvent extends Event {
    public final Router.Msg msg;
    public PrideChatEvent(Router.Msg msg) { this.msg = msg; }

    @Cancelable
    public static class Send extends PrideChatEvent {
        public Send(Router.Msg msg) { super(msg); }
    }

    public static class Talked extends PrideChatEvent {
        public final java.util.List<EntityPlayerMP> heard;
        public final int dimension;
        public final double x, y, z;
        public Talked(Router.Msg msg, java.util.List<EntityPlayerMP> heard) {
            super(msg);
            this.heard = heard;
            dimension = msg.sender == null ? 0 : msg.sender.dimension;
            x = msg.sender == null ? 0 : msg.sender.posX; y = msg.sender == null ? 0 : msg.sender.posY; z = msg.sender == null ? 0 : msg.sender.posZ;
        }
    }

    /**
     * Context-aware chat (#25): fired before a spoken (ranged) message is sent, with what the talker is doing right now:
     * underwater, fire, sprint, riding, hurt, sneak. Add your own (e.g. "drunk", "radio-static") and change msg.text
     * yourself, or remove ours to stop PrideChat's built-in effect for this message.
     */
    public static class Context extends PrideChatEvent {
        public final java.util.List<String> contexts;
        public Context(Router.Msg msg, java.util.List<String> contexts) { super(msg); this.contexts = contexts; }
    }

    /** how far a spoken message will reach, in blocks (0 = no limit); set range to change it */
    public static class Range extends PrideChatEvent {
        public int range;
        public Range(Router.Msg msg, int range) { super(msg); this.range = range; }
    }

    /**
     * After PrideChat's moderation looked at a message (verdict.block / shadow / text / tell). Change the verdict, or
     * cancel to ignore it (the original text goes through). msg is null on the vanilla chat path (players without PrideChat).
     */
    @Cancelable
    public static class Filter extends PrideChatEvent {
        public final EntityPlayerMP player;
        public final String original;
        public final com.dogpound.pridechat.Moderation.Verdict verdict;
        public Filter(Router.Msg msg, EntityPlayerMP player, String original, com.dogpound.pridechat.Moderation.Verdict verdict) {
            super(msg); this.player = player; this.original = original; this.verdict = verdict;
        }
    }

    /** a listener is mentioned (@name / name / @here / @everyone) in a message they get; cancel = no ping or highlight for them */
    @Cancelable
    public static class Mention extends PrideChatEvent {
        public final EntityPlayerMP mentioned;
        public Mention(Router.Msg msg, EntityPlayerMP mentioned) { super(msg); this.mentioned = mentioned; }
    }

    /** a player started / stopped typing (mode + target as the client sent them); cancel = nobody sees the dots */
    @Cancelable
    public static class Typing extends PrideChatEvent {
        public final EntityPlayerMP player;
        public final boolean typing;
        public final String mode, target;
        public Typing(EntityPlayerMP player, boolean typing, String mode, String target) { super(null); this.player = player; this.typing = typing; this.mode = mode; this.target = target; }
    }

    /** a player joins / leaves / creates a channel or group (action = join, leave, create); cancel = refuse */
    @Cancelable
    public static class Channel extends PrideChatEvent {
        public final EntityPlayerMP player;
        public final String channel, action;
        public Channel(EntityPlayerMP player, String channel, String action) { super(null); this.player = player; this.channel = channel; this.action = action; }
    }

    @Cancelable
    public static class Deliver extends PrideChatEvent {
        public final EntityPlayerMP listener;
        public final float distance;
        public int band;
        public Deliver(Router.Msg msg, EntityPlayerMP listener, int band, float distance) { super(msg); this.listener = listener; this.band = band; this.distance = distance; }
    }
}
