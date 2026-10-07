package com.dogpound.pridechat;

import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fml.common.network.ByteBufUtils;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * PrideChat's own channel. Every packet is a type + an NBT tag (the metadata) + one big UTF-8 string (text or JSON),
 * so new features add a type, not a class. Client → server text comes in 8,000-letter chunks (a client packet is
 * capped at 32 KB); server → client lines carry the whole message plus its "awareness" metadata.
 */
public final class Net {
    public static final SimpleNetworkWrapper CH = NetworkRegistry.INSTANCE.newSimpleChannel("pridechat");
    public static final int CHUNK = 8000;

    // client → server
    public static final int C_CHUNK = 1, C_TYPING = 2, C_READ = 3, C_REACT = 4, C_HELLO = 5, C_AI = 6, C_ANSWER = 7, C_STATUS = 8;
    // server → client
    public static final int S_HELLO = 1, S_LINE = 2, S_TYPING = 3, S_READ = 4, S_REACT = 5, S_MODE = 6, S_CHANNELS = 7, S_DELIVERED = 8, S_AI = 9, S_ASK = 10, S_EFFECT = 11, S_STATUS = 12;

    public static class Pkt implements IMessage {
        public int type;
        public NBTTagCompound tag = new NBTTagCompound();
        public String text = "";
        public Pkt() {}
        public Pkt(int type, NBTTagCompound tag, String text) { this.type = type; if (tag != null) this.tag = tag; this.text = text == null ? "" : text; }
        @Override public void fromBytes(ByteBuf b) {
            type = b.readByte();
            tag = ByteBufUtils.readTag(b);
            if (tag == null) tag = new NBTTagCompound();
            text = ByteBufUtils.readUTF8String(b);
        }
        @Override public void toBytes(ByteBuf b) {
            b.writeByte(type);
            ByteBufUtils.writeTag(b, tag);
            ByteBufUtils.writeUTF8String(b, text);
        }
    }

    /** to the server */
    public static class ToServer extends Pkt {
        public ToServer() {}
        public ToServer(int type, NBTTagCompound tag, String text) { super(type, tag, text); }
    }

    /** to a client */
    public static class ToClient extends Pkt {
        public ToClient() {}
        public ToClient(int type, NBTTagCompound tag, String text) { super(type, tag, text); }
    }

    public static class ServerHandler implements IMessageHandler<ToServer, IMessage> {
        @Override public IMessage onMessage(ToServer m, MessageContext ctx) {
            EntityPlayerMP p = ctx.getServerHandler().player;
            p.getServerWorld().addScheduledTask(() -> Router.fromClient(p, m));
            return null;
        }
    }

    /** the body lives in ClientSide (never loaded on a dedicated server) */
    public static class ClientHandler implements IMessageHandler<ToClient, IMessage> {
        @Override public IMessage onMessage(ToClient m, MessageContext ctx) {
            PrideChat.proxyClientPacket(m);
            return null;
        }
    }

    static void init() {
        CH.registerMessage(ServerHandler.class, ToServer.class, 0, Side.SERVER);
        CH.registerMessage(ClientHandler.class, ToClient.class, 1, Side.CLIENT);
    }

    public static void send(EntityPlayerMP p, int type, NBTTagCompound tag, String text) { CH.sendTo(new ToClient(type, tag, text), p); }
    public static void toServer(int type, NBTTagCompound tag, String text) { CH.sendToServer(new ToServer(type, tag, text)); }

    private Net() {}
}
