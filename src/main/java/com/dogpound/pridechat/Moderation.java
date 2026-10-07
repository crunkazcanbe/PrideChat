package com.dogpound.pridechat;

import com.google.gson.JsonObject;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.util.text.TextComponentString;

import java.io.File;
import java.io.FileWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Server-side moderation (roadmap #28-30): rules first, always — caps, repeats, mention spam, zalgo, ads, personal
 * info, a word list — each category with its own action. The AI (AI.secondOpinion) only adds a second look afterwards.
 * Every hit goes to logs/pridechat/moderation.jsonl with a plain reason a player could appeal.
 * Actions: allow, soften (censor/calm), hide (sender told), shadow (only the sender sees it), warn, mute, kick, staff (tell staff).
 */
public final class Moderation {
    public static final class Verdict {
        public boolean block, shadow;
        public String text, tell;
    }

    private static final Map<UUID, Long> MUTED = new HashMap<>();
    private static final Set<UUID> SHADOW = new HashSet<>();
    private static final Map<UUID, Object[]> LAST = new HashMap<>();   // {text, time}
    private static final Map<UUID, Integer> STRIKES = new HashMap<>();
    private static final Pattern AD = Pattern.compile("(?i)(discord\\.gg/|\\b\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?\\b|join my server|\\.(?:net|com|org)(:\\d{2,5})\\b)");
    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(\\+?\\d[\\d -]{8,}\\d)(?!\\d)");
    private static final Pattern MENTION = Pattern.compile("@[A-Za-z0-9_]{2,16}");

    static boolean muted(EntityPlayerMP p) {
        Long until = MUTED.get(p.getUniqueID());
        if (until == null) return false;
        if (until > 0 && until < System.currentTimeMillis()) { MUTED.remove(p.getUniqueID()); return false; }
        return true;
    }

    static String mutedFor(EntityPlayerMP p) {
        Long until = MUTED.get(p.getUniqueID());
        return until == null || until <= 0 ? "" : " for " + Math.max(1, (until - System.currentTimeMillis()) / 60000) + " more min";
    }

    static void mute(UUID u, int minutes) { MUTED.put(u, minutes <= 0 ? 0L : System.currentTimeMillis() + minutes * 60000L); }
    static boolean unmute(UUID u) { SHADOW.remove(u); return MUTED.remove(u) != null; }
    static boolean toggleShadow(UUID u) { if (!SHADOW.remove(u)) { SHADOW.add(u); return true; } return false; }

    static String action(String category) {
        for (String s : ChatConfig.server.modActions) {
            String[] p = s.split("\\s*\\|\\s*");
            if (p.length >= 2 && p[0].trim().equalsIgnoreCase(category)) return p[1].trim().toLowerCase(Locale.ROOT);
        }
        return "allow";
    }

    public static Verdict check(EntityPlayerMP p, String text) {
        Verdict v = new Verdict();
        v.text = text;
        v.shadow = SHADOW.contains(p.getUniqueID());
        if (!ChatConfig.server.moderation) return v;
        boolean op = Router.server().getPlayerList().canSendCommands(p.getGameProfile());
        if (op && ChatConfig.server.opsExempt) return v;

        // repeats
        Object[] last = LAST.get(p.getUniqueID());
        LAST.put(p.getUniqueID(), new Object[]{text, System.currentTimeMillis()});
        if (last != null && text.equalsIgnoreCase((String) last[0]) && System.currentTimeMillis() - (long) last[1] < 30000)
            apply(p, v, "repeat", "you already said that");
        // shouting in caps
        int letters = 0, caps = 0;
        for (char c : text.toCharArray()) if (Character.isLetter(c)) { letters++; if (Character.isUpperCase(c)) caps++; }
        if (letters >= 8 && caps * 100 / letters >= 75) { if (apply(p, v, "caps", "too many capitals")) v.text = v.text.toLowerCase(Locale.ROOT); }
        // mention spam
        Matcher mm = MENTION.matcher(text);
        int mentions = 0;
        while (mm.find()) mentions++;
        if (mentions > 5) apply(p, v, "mentionspam", "too many @mentions");
        // zalgo / unicode spam
        int marks = 0;
        for (char c : text.toCharArray()) if (Character.getType(c) == Character.NON_SPACING_MARK) marks++;
        if (marks > 8) { if (apply(p, v, "unicode", "unicode spam")) v.text = v.text.replaceAll("\\p{Mn}+", ""); }
        // ads / server IPs
        if (AD.matcher(text).find()) apply(p, v, "ads", "looks like an advert or server address");
        // personal info
        if (EMAIL.matcher(text).find() || PHONE.matcher(text).find()) {
            if (apply(p, v, "personal", "personal info (email/phone) — keep yourself safe ♥")) {
                v.text = PHONE.matcher(EMAIL.matcher(v.text).replaceAll("[email hidden]")).replaceAll("[number hidden]");
            }
        }
        // word list
        String low = text.toLowerCase(Locale.ROOT);
        for (String w : ChatConfig.server.badWords) {
            if (w.trim().isEmpty() || !low.contains(w.trim().toLowerCase(Locale.ROOT))) continue;
            if (apply(p, v, "profanity", "language")) v.text = censor(v.text, ChatConfig.server.badWords);
            break;
        }
        return v;
    }

    /** do what the config says for this category; true = "soften" (caller fixes the text) */
    static boolean apply(EntityPlayerMP p, Verdict v, String cat, String reason) {
        String a = action(cat);
        if ("allow".equals(a)) return false;
        log(p, cat, reason, a, v.text, "rules", 1.0);
        consequence(p, a, cat, reason, v, true);
        return "soften".equals(a);
    }

    /** used by rules and by the AI's second opinion (then v is null: the message has already gone) */
    static void consequence(EntityPlayerMP p, String a, String cat, String reason, Verdict v, boolean fromRules) {
        switch (a) {
            case "hide": if (v != null) { v.block = true; v.tell = "§eNot sent: " + reason + "."; } break;
            case "shadow": if (v != null) v.shadow = true; break;
            case "warn":
                Router.tell(p, "§e⚠ Please watch it: " + reason + ".");
                break;
            case "mute": {
                int strikes = STRIKES.merge(p.getUniqueID(), 1, Integer::sum);
                int min = ChatConfig.server.muteMinutes * strikes;
                mute(p.getUniqueID(), min);
                Router.tell(p, "§cMuted for " + min + " min: " + reason + ". Ask staff if this is wrong.");
                if (v != null) { v.block = true; v.tell = null; }
                break;
            }
            case "kick": p.connection.disconnect(new TextComponentString("Chat: " + reason)); if (v != null) v.block = true; break;
            case "staff": staff("§c[Mod] §f" + p.getName() + " §7— " + cat + ": " + reason); break;
            default: break;
        }
        if (!"staff".equals(a) && ChatConfig.server.notifyStaff && !"soften".equals(a)) staff("§c[Mod] §f" + p.getName() + " §7— " + cat + " (" + a + "): " + reason);
    }

    static void staff(String s) {
        for (EntityPlayerMP r : Router.server().getPlayerList().getPlayers())
            if (Router.perm(r, "pridechat.channel.staff")) Router.tell(r, s);
    }

    static String censor(String text, String[] words) {
        String out = text;
        for (String w : words) {
            if (w.trim().isEmpty()) continue;
            out = Pattern.compile(Pattern.quote(w.trim()), Pattern.CASE_INSENSITIVE).matcher(out).replaceAll(new String(new char[w.trim().length()]).replace('\0', '*'));
        }
        return out;
    }

    static synchronized void log(EntityPlayerMP p, String cat, String reason, String action, String text, String by, double conf) {
        try {
            File dir = new File("logs/pridechat");
            dir.mkdirs();
            JsonObject o = new JsonObject();
            o.addProperty("time", System.currentTimeMillis());
            o.addProperty("player", p.getName());
            o.addProperty("uuid", p.getUniqueID().toString());
            o.addProperty("category", cat);
            o.addProperty("reason", reason);
            o.addProperty("action", action);
            o.addProperty("by", by);
            o.addProperty("confidence", conf);
            o.addProperty("text", text.length() > 500 ? text.substring(0, 500) : text);
            try (Writer w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(new File(dir, "moderation.jsonl"), true), StandardCharsets.UTF_8)) {
                w.write(o.toString());
                w.write('\n');
            }
        } catch (Exception ignored) {}
    }

    private Moderation() {}
}
