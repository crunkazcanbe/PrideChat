package com.dogpound.pridechat;

import com.dogpound.pridechat.api.PrideChatEvent;
import com.google.gson.*;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentString;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Cross-server chat (roadmap #39), OFF by default. Every server runs PrideChat with the same relay URL + secret:
 * everyone-chat and the listed channels are POSTed to the relay, and one background thread long-polls it for the
 * other servers' lines, which arrive tagged [ServerName]. The relay is tools/pridechat-relay.py (Python, no installs).
 * Design + protocol: docs/CROSS-SERVER.md. Never touches the network on the game thread.
 */
public final class CrossServer {
    private static final LinkedBlockingQueue<JsonObject> OUT = new LinkedBlockingQueue<>(500);
    private static volatile Thread worker, sender;
    private static volatile boolean running;
    private static long since = -1;

    static boolean on() { return ChatConfig.server.crossServer && !ChatConfig.server.relayUrl.trim().isEmpty(); }

    static void start() {
        if (!on() || worker != null) return;
        running = true;
        since = -1;
        Thread t = new Thread(CrossServer::loop, "PrideChat-CrossServer-in");
        t.setDaemon(true);
        worker = t;
        t.start();
        Thread s = new Thread(CrossServer::sendLoop, "PrideChat-CrossServer-out");   // separate, so a long poll never delays sending
        s.setDaemon(true);
        sender = s;
        s.start();
        PrideChat.LOG.info("[PrideChat] cross-server chat on: " + ChatConfig.server.relayUrl + " as " + ChatConfig.server.serverName);
    }

    static void stop() {
        running = false;
        for (Thread t : new Thread[]{worker, sender}) if (t != null) t.interrupt();
        worker = null; sender = null;
        OUT.clear();
    }

    private static void sendLoop() {
        String base = ChatConfig.server.relayUrl.replaceAll("/+$", "");
        while (running) {
            try { http("POST", base + "/send", OUT.take().toString(), 5000); }
            catch (InterruptedException e) { return; }
            catch (Exception e) { if (!running) return; }                  // relay down: that line is lost, keep going
        }
    }

    /** does this message cross? (everyone-chat + the listed channels; not shadow, not selector-only, not from the relay) */
    @SubscribeEvent
    public static void talked(PrideChatEvent.Talked e) {
        Router.Msg m = e.msg;
        if (!on() || m.sender == null || m.shadow || m.only != null || m.anonymous) return;
        String key = Router.GLOBAL.equals(m.mode) ? "global" : Router.CHANNEL.equals(m.mode) ? m.channel : null;
        if (key == null || !java.util.Arrays.asList(ChatConfig.server.crossChannels).contains(key)) return;
        JsonObject o = new JsonObject();
        o.addProperty("server", ChatConfig.server.serverName);
        o.addProperty("name", m.sender.getName());
        o.addProperty("mode", m.mode);
        o.addProperty("channel", m.channel);
        o.addProperty("text", m.text);
        OUT.offer(o);
    }

    private static void loop() {
        String base = ChatConfig.server.relayUrl.replaceAll("/+$", "");
        while (running) {
            try {
                String me = URLEncoder.encode(ChatConfig.server.serverName, "UTF-8");
                String got = http("GET", base + "/poll?since=" + since + "&server=" + me, null, 25000);
                JsonObject r = new JsonParser().parse(got).getAsJsonObject();
                long seq = r.get("seq").getAsLong();
                boolean first = since < 0;
                since = seq;
                if (first) continue;                                     // don't replay what was said before we joined
                for (JsonElement el : r.getAsJsonArray("messages")) {
                    JsonObject x = el.getAsJsonObject();
                    if (ChatConfig.server.serverName.equals(x.get("server").getAsString())) continue;
                    Router.server().addScheduledTask(() -> arrive(x));
                }
            } catch (InterruptedIOException e) {
                if (!running) return;
            } catch (Exception e) {
                if (!running) return;
                try { Thread.sleep(5000); } catch (InterruptedException ie) { return; }   // relay down: try again later
            }
        }
    }

    private static String http(String method, String url, String body, int timeout) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(3000);
        c.setReadTimeout(timeout);
        c.setRequestProperty("X-PrideChat-Secret", ChatConfig.server.relaySecret);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = c.getOutputStream()) { os.write(body.getBytes(StandardCharsets.UTF_8)); }
        }
        if (c.getResponseCode() != 200) throw new IOException("relay said " + c.getResponseCode());
        try (InputStream in = c.getInputStream(); ByteArrayOutputStream b = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    /** a line from another server: to everyone (everyone-chat) or the channel's members here */
    private static void arrive(JsonObject x) {
        String server = Router.clean(x.get("server").getAsString()), name = Router.clean(x.get("name").getAsString());
        String mode = x.get("mode").getAsString(), channel = x.has("channel") ? x.get("channel").getAsString() : "";
        String text = Router.clean(x.get("text").getAsString());
        if (text.length() > ChatConfig.server.maxLength) text = text.substring(0, ChatConfig.server.maxLength);
        Channels.Ch ch = Router.CHANNEL.equals(mode) ? Channels.get(channel) : null;
        if (Router.CHANNEL.equals(mode) && ch == null) return;
        ITextComponent line = new TextComponentString(Router.colors(String.format(ChatConfig.server.crossTag, server))
                + (ch != null ? Router.colors(String.format(ch.tag.isEmpty() ? ChatConfig.server.channelTag : ch.tag, ch.name)) : "") + "<" + name + "> ")
                .appendSibling(net.minecraftforge.common.ForgeHooks.newChatWithLinks(text));
        Router.Msg m = new Router.Msg();
        m.mode = ch != null ? Router.CHANNEL : Router.GLOBAL; m.channel = ch != null ? ch.name : ""; m.text = text;
        String json = ITextComponent.Serializer.componentToJson(line);
        for (EntityPlayerMP r : Router.server().getPlayerList().getPlayers()) {
            if (ch != null && !ch.isMember(r)) continue;
            if (!Router.hasPrideChat(r)) { Router.vanillaSend(r, line); continue; }
            NBTTagCompound t = Router.meta(m, 0, 0, false);
            t.setString("s", name);
            t.setString("xs", server);
            Net.send(r, Net.S_LINE, t, json);
        }
        Router.server().sendMessage(line);
    }

    private CrossServer() {}
}
