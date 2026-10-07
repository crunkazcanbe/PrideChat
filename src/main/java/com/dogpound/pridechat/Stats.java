package com.dogpound.pridechat;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.TextComponentString;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * Chat statistics (roadmap #46): messages per minute, most active people, busiest channels / kinds, spam blocked,
 * peak hours. /chatstats shows them; every 5 minutes a snapshot goes to logs/pridechat/stats.json for a web dashboard.
 * Since the server started (not saved between restarts).
 */
final class Stats {
    static long STARTED = System.currentTimeMillis();
    static final Map<String, Integer> PEOPLE = new HashMap<>(), KINDS = new HashMap<>(), BLOCKED = new HashMap<>();
    static final int[] HOURS = new int[24];
    /** messages per minute for the last hour: minute number → count */
    static final TreeMap<Long, Integer> MINUTES = new TreeMap<>();
    static int total;

    static void message(Router.Msg m) {
        if (m.sender == null) return;
        total++;
        PEOPLE.merge(m.sender.getName(), 1, Integer::sum);
        KINDS.merge(m.channel.isEmpty() || m.channel.startsWith("dim") ? m.mode : "#" + m.channel, 1, Integer::sum);
        HOURS[Calendar.getInstance().get(Calendar.HOUR_OF_DAY)]++;
        long min = System.currentTimeMillis() / 60000;
        MINUTES.merge(min, 1, Integer::sum);
        while (!MINUTES.isEmpty() && MINUTES.firstKey() < min - 60) MINUTES.pollFirstEntry();
    }

    /** a message that didn't go out: slow mode, flood, a moderation rule… */
    static void blocked(String why) { BLOCKED.merge(why, 1, Integer::sum); }

    static int blockedTotal() { int n = 0; for (int v : BLOCKED.values()) n += v; return n; }

    /** messages in the last `minutes` minutes (the current one included) */
    static int lastMinutes(int minutes) {
        long now = System.currentTimeMillis() / 60000;
        int n = 0;
        for (Map.Entry<Long, Integer> e : MINUTES.tailMap(now - minutes + 1).entrySet()) n += e.getValue();
        return n;
    }

    static List<Map.Entry<String, Integer>> top(Map<String, Integer> m, int n) {
        List<Map.Entry<String, Integer>> l = new ArrayList<>(m.entrySet());
        l.sort((x, y) -> y.getValue() - x.getValue());
        return l.subList(0, Math.min(n, l.size()));
    }

    static void show(ICommandSender who) {
        long up = (System.currentTimeMillis() - STARTED) / 60000;
        say(who, "§d✦ Chat stats §7— " + total + " messages from " + PEOPLE.size() + " people in " + (up >= 120 ? up / 60 + " h" : up + " min"));
        say(who, String.format("§7Per minute: §f%d §7now, §f%.1f §7last 10 min, §f%.1f §7last hour", lastMinutes(1), lastMinutes(10) / 10.0, lastMinutes(60) / 60.0));
        StringBuilder b = new StringBuilder("§7Most active: ");
        int i = 1;
        for (Map.Entry<String, Integer> e : top(PEOPLE, 5)) b.append("§f").append(i++).append(". ").append(e.getKey()).append(" §8(").append(e.getValue()).append(") ");
        say(who, b.toString());
        b = new StringBuilder("§7Where: ");
        for (Map.Entry<String, Integer> e : top(KINDS, 6)) b.append("§f").append(e.getKey()).append(" §8").append(e.getValue()).append("  ");
        say(who, b.toString());
        b = new StringBuilder("§7Spam / blocked: §f" + blockedTotal());
        for (Map.Entry<String, Integer> e : top(BLOCKED, 4)) b.append(" §8").append(e.getKey()).append(" ").append(e.getValue());
        say(who, b.toString());
        b = new StringBuilder("§7Busiest hours: ");
        Integer[] h = new Integer[24];
        for (int k = 0; k < 24; k++) h[k] = k;
        Arrays.sort(h, (x, y) -> HOURS[y] - HOURS[x]);
        for (int k = 0; k < 3 && HOURS[h[k]] > 0; k++) b.append("§f").append(String.format("%02d:00", h[k])).append(" §8(").append(HOURS[h[k]]).append(") ");
        say(who, b.toString());
        say(who, "§8Snapshot for dashboards: logs/pridechat/stats.json (every 5 min)");
    }

    private static void say(ICommandSender who, String s) { who.sendMessage(new TextComponentString(s)); }

    /** the JSON a web dashboard reads */
    static JsonObject json() {
        JsonObject o = new JsonObject();
        o.addProperty("time", System.currentTimeMillis());
        o.addProperty("started", STARTED);
        o.addProperty("total", total);
        o.addProperty("perMinuteNow", lastMinutes(1));
        o.addProperty("perMinute10", lastMinutes(10) / 10.0);
        o.addProperty("perMinute60", lastMinutes(60) / 60.0);
        o.addProperty("online", Router.server() == null ? 0 : Router.server().getPlayerList().getCurrentPlayerCount());
        o.addProperty("blocked", blockedTotal());
        o.add("people", map(PEOPLE));
        o.add("kinds", map(KINDS));
        o.add("blockedBy", map(BLOCKED));
        JsonArray hours = new JsonArray();
        for (int h : HOURS) hours.add(h);
        o.add("hours", hours);
        JsonObject mins = new JsonObject();
        for (Map.Entry<Long, Integer> e : MINUTES.entrySet()) mins.addProperty(String.valueOf(e.getKey() * 60000), e.getValue());
        o.add("minutes", mins);
        return o;
    }

    private static JsonObject map(Map<String, Integer> m) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : top(m, 100)) o.addProperty(e.getKey(), e.getValue());
        return o;
    }

    /** logs/pridechat/stats.json (written to a temp file, then swapped in) */
    static void snapshot() {
        if (Router.server() == null) return;
        try {
            File dir = Router.server().getFile("logs/pridechat");
            dir.mkdirs();
            File f = new File(dir, "stats.json"), tmp = new File(dir, "stats.json.tmp");
            Files.write(tmp.toPath(), new GsonBuilder().setPrettyPrinting().create().toJson(json()).getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), f.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception e) { PrideChat.LOG.warn("couldn't write chat stats: " + e); }
    }

    /** a new world / server start */
    static void reset() {
        STARTED = System.currentTimeMillis();
        PEOPLE.clear(); KINDS.clear(); BLOCKED.clear(); MINUTES.clear(); Arrays.fill(HOURS, 0); total = 0;
    }

    private Stats() {}
}
