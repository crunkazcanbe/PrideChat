package com.dogpound.pridechat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/** Mail for offline players (world/pridechat-mail.json): /mail send, /mail read, /mail clear; DMs to someone offline land here. */
public final class Mail {
    public static final class Letter { public String from, text; public long time; }

    private static Map<String, List<Letter>> box;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static File file() {
        return new File(Router.server().getEntityWorld().getSaveHandler().getWorldDirectory(), "pridechat-mail.json");
    }

    private static Map<String, List<Letter>> box() {
        if (box != null) return box;
        box = new HashMap<>();
        try {
            File f = file();
            if (f.exists()) {
                Map<String, List<Letter>> m = GSON.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8),
                        new TypeToken<Map<String, List<Letter>>>() {}.getType());
                if (m != null) box = m;
            }
        } catch (Exception e) { PrideChat.LOG.warn("mail couldn't be read: " + e); }
        return box;
    }

    private static void save() {
        try { Files.write(file().toPath(), GSON.toJson(box()).getBytes(StandardCharsets.UTF_8)); }
        catch (Exception e) { PrideChat.LOG.warn("mail couldn't be saved: " + e); }
    }

    static boolean send(String from, String to, String text) {
        if (!to.matches("[A-Za-z0-9_]{2,16}")) return false;
        List<Letter> l = box().computeIfAbsent(to.toLowerCase(Locale.ROOT), k -> new ArrayList<>());
        if (l.size() >= ChatConfig.server.mailMax) return false;
        Letter x = new Letter();
        x.from = from; x.text = text.length() > 4000 ? text.substring(0, 4000) : text; x.time = System.currentTimeMillis();
        l.add(x);
        save();
        return true;
    }

    static int count(String name) { List<Letter> l = box().get(name.toLowerCase(Locale.ROOT)); return l == null ? 0 : l.size(); }

    static List<String> read(String name) {
        List<String> out = new ArrayList<>();
        List<Letter> l = box().get(name.toLowerCase(Locale.ROOT));
        if (l == null) return out;
        SimpleDateFormat f = new SimpleDateFormat("MMM d HH:mm");
        for (Letter x : l) out.add("§d✉ §7" + f.format(new Date(x.time)) + " §f" + x.from + "§7: §r" + x.text);
        return out;
    }

    static void clear(String name) { box().remove(name.toLowerCase(Locale.ROOT)); save(); }

    /** a new world/server: forget the old box */
    static void reset() { box = null; }

    private Mail() {}
}
