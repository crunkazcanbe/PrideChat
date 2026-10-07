package com.dogpound.pridechat;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.text.ITextComponent;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * PrideChat's AI side, all on the SERVER (singleplayer = same machine), never on the game thread, always with a deadline
 * and a rule-based fallback. Asks PrideMind when it's installed and thinking; otherwise the same OpenAI-style endpoint
 * Buddy Builder uses (config/buddybuilder.cfg endpoint + modelCpu = "buddy-cpu", so it never fights Minecraft for VRAM).
 * Everything is OFF until "AI on" is switched on in the chat settings (AI page) — nothing loads a model unasked.
 *
 * Features: moderation second opinion (or hold-for-review), "@Pride …" / /ask answers, catch-up summary on join,
 * translate / tone check / AFK auto-reply in your own style for clients that ask.
 */
public final class AI {
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "PrideChat-AI"); t.setDaemon(true); return t; });
    private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(r -> { Thread t = new Thread(r, "PrideChat-AI-timer"); t.setDaemon(true); return t; });
    private static final LinkedList<Long> CALLS = new LinkedList<>();
    private static final Map<UUID, Long> LAST_ASK = new HashMap<>();
    private static final Map<UUID, Long> LAST_SEEN = new HashMap<>();
    static volatile String status = "off";

    static boolean available() { return ChatConfig.ai.enabled; }

    // ------------------------------------------------------------------ the one way to ask
    /** completes with the answer, or null (off, over budget, endpoint down, too slow) — never throws, never blocks */
    static CompletableFuture<String> ask(String purpose, String system, String prompt, int maxTokens, long deadlineMs) {
        CompletableFuture<String> out = new CompletableFuture<>();
        if (!ChatConfig.ai.enabled || !budget()) { out.complete(null); return out; }
        Object mind = prideMind();
        if (mind != null) {
            try {
                Class<?> m = (Class<?>) mind;
                Object req = m.getMethod("request", String.class).invoke(null, "pridechat." + purpose);
                req.getClass().getField("system").set(req, system);
                req.getClass().getField("prompt").set(req, prompt);
                req.getClass().getField("maxTokens").set(req, maxTokens);
                req.getClass().getField("deadlineMs").set(req, deadlineMs);
                @SuppressWarnings("unchecked")
                CompletableFuture<String> f = (CompletableFuture<String>) m.getMethod("ask", req.getClass()).invoke(null, req);
                status = "PrideMind";
                f.whenComplete((a, e) -> out.complete(e == null ? a : null));
                return out;
            } catch (Throwable t) { status = "PrideMind error: " + t; }
        }
        if (!ChatConfig.ai.direct) { out.complete(null); return out; }
        POOL.submit(() -> {
            try { out.complete(http(system, prompt, maxTokens, deadlineMs)); status = "endpoint " + endpoint()[1]; }
            catch (Throwable t) { status = "endpoint down (" + t.getClass().getSimpleName() + ") — rules only"; out.complete(null); }
        });
        return out;
    }

    /** PrideMind's Mind class when it's installed AND has a model connected, else null */
    private static Object prideMind() {
        if (!ChatConfig.ai.usePrideMind) return null;
        try {
            Class<?> m = Class.forName("com.dogpound.pridemind.api.Mind");
            return Boolean.TRUE.equals(m.getMethod("thinking").invoke(null)) ? m : null;
        } catch (Throwable t) { return null; }
    }

    private static synchronized boolean budget() {
        long now = System.currentTimeMillis();
        while (!CALLS.isEmpty() && now - CALLS.getFirst() > 60000) CALLS.removeFirst();
        if (CALLS.size() >= ChatConfig.ai.maxPerMinute) return false;
        CALLS.add(now);
        return true;
    }

    /** {url, model}: the chat settings win, else Buddy Builder's config, else the pack default */
    static String[] endpoint() {
        String url = ChatConfig.ai.endpoint, model = ChatConfig.ai.model;
        if (url.isEmpty() || model.isEmpty()) {
            try {
                File f = new File("config/buddybuilder.cfg");
                if (f.exists()) for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                    String l = line.trim();
                    if (url.isEmpty() && l.startsWith("S:endpoint=")) url = l.substring(11).trim();
                    if (model.isEmpty() && l.startsWith("S:modelCpu=")) model = l.substring(11).trim();
                }
            } catch (Exception ignored) {}
        }
        if (url.isEmpty()) url = "http://127.0.0.1:8096/v1/chat/completions";
        if (model.isEmpty()) model = "buddy-cpu";
        return new String[]{url, model};
    }

    private static String http(String system, String prompt, int maxTokens, long deadlineMs) throws IOException {
        String[] ep = endpoint();
        JsonObject body = new JsonObject();
        body.addProperty("model", ep[1]);
        JsonArray msgs = new JsonArray();
        JsonObject s = new JsonObject(); s.addProperty("role", "system"); s.addProperty("content", system); msgs.add(s);
        JsonObject u = new JsonObject(); u.addProperty("role", "user"); u.addProperty("content", prompt); msgs.add(u);
        body.add("messages", msgs);
        body.addProperty("max_tokens", maxTokens);
        body.addProperty("temperature", 0.4);
        body.addProperty("stream", false);
        HttpURLConnection c = (HttpURLConnection) new URL(ep[0]).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout((int) Math.max(1000, deadlineMs));
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream o = c.getOutputStream()) { o.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        try (InputStream in = c.getInputStream()) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JsonObject r = new JsonParser().parse(new String(b.toByteArray(), StandardCharsets.UTF_8)).getAsJsonObject();
            String text = r.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message").get("content").getAsString();
            return text.replaceAll("(?s)<think>.*?</think>", "").trim();
        }
    }

    /** run on the server thread once the answer is in */
    private static void later(Runnable r) { Router.server().addScheduledTask(r); }

    // ------------------------------------------------------------------ moderation
    static final List<String> CATS = Arrays.asList("ok", "toxicity", "harassment", "slurs", "spam", "ads", "personal", "selfharm");
    private static final String MOD_SYSTEM = "You moderate chat in a friendly Minecraft server. Banter, swearing between friends and game talk are ok. "
            + "Classify the message into exactly one category: " + String.join(", ", CATS) + ". Reply with JSON only: "
            + "{\"decision\":\"<category>\",\"reason\":\"<a few kind words a player could read>\",\"confidence\":<0 to 1>}";

    static boolean holdForReview() { return ChatConfig.ai.enabled && ChatConfig.ai.moderation && ChatConfig.ai.holdForReview; }

    /** {category, reason, confidence} or null */
    private static Object[] parse(String a) {
        if (a == null) return null;
        try {
            int i = a.indexOf('{'), j = a.lastIndexOf('}');
            JsonObject o = new JsonParser().parse(a.substring(i, j + 1)).getAsJsonObject();
            String d = o.get("decision").getAsString().toLowerCase(Locale.ROOT).trim();
            if (!CATS.contains(d)) return null;
            return new Object[]{d, o.has("reason") ? o.get("reason").getAsString() : d, o.has("confidence") ? o.get("confidence").getAsDouble() : 0.6};
        } catch (Throwable t) { return null; }
    }

    private static boolean worth(String text) { return ChatConfig.ai.enabled && ChatConfig.ai.moderation && text.length() >= ChatConfig.ai.minLength; }

    /** hold-for-review: deliver when the AI says it's fine, or when it's too slow (deadline) */
    static void review(EntityPlayerMP p, String text, Consumer<Boolean> done) {
        if (!worth(text)) { done.accept(true); return; }
        boolean[] answered = {false};
        ask("moderate", MOD_SYSTEM, text, 80, ChatConfig.ai.reviewDeadlineMs).thenAccept(a -> later(() -> {
            if (answered[0]) return;
            answered[0] = true;
            Object[] v = parse(a);
            boolean bad = v != null && !"ok".equals(v[0]) && (double) v[2] >= ChatConfig.ai.actConfidence / 100.0;
            if (v != null && !"ok".equals(v[0])) judged(p, text, v, true);
            done.accept(!bad || !severe(Moderation.action("ai." + v[0])));
        }));
        TIMER.schedule(() -> later(() -> { if (!answered[0]) { answered[0] = true; done.accept(true); } }),   // the deadline: deliver anyway
                ChatConfig.ai.reviewDeadlineMs + 200L, TimeUnit.MILLISECONDS);
    }

    private static boolean severe(String a) { return "hide".equals(a) || "shadow".equals(a) || "mute".equals(a) || "kick".equals(a); }

    /** after the message went out: a second look that can warn / mute / tell staff / log */
    static void secondOpinion(EntityPlayerMP p, String text) {
        if (!worth(text) || holdForReview()) return;
        ask("moderate", MOD_SYSTEM, text, 80, 15000).thenAccept(a -> later(() -> {
            Object[] v = parse(a);
            if (v != null && !"ok".equals(v[0])) judged(p, text, v, false);
        }));
    }

    private static void judged(EntityPlayerMP p, String text, Object[] v, boolean held) {
        String cat = (String) v[0], reason = (String) v[1];
        double conf = (double) v[2];
        String a = Moderation.action("ai." + cat);
        if (conf < ChatConfig.ai.softConfidence / 100.0) return;                       // < 0.4: ignore
        if (conf < ChatConfig.ai.actConfidence / 100.0) a = "log";                    // 0.4-0.7: only note it
        Moderation.log(p, "ai." + cat, reason, a, text, "ai", conf);
        if ("selfharm".equals(cat)) Router.tell(p, "§d♥ " + ChatConfig.ai.careMessage);
        if (!"log".equals(a)) Moderation.consequence(p, held || !severe(a) ? a : ("mute".equals(a) || "kick".equals(a) ? a : "warn"), "ai." + cat, reason, null, false);
    }

    // ------------------------------------------------------------------ the helper bot
    private static final String BOT_SYSTEM = "You are %s, the friendly helper of a big Minecraft 1.12.2 modpack (hundreds of mods). "
            + "Answer in one or two short sentences, plain text, no markdown. If unsure, say so kindly.";

    static boolean askAllowed(EntityPlayerMP p) {
        if (!ChatConfig.ai.enabled || !ChatConfig.ai.assistant) return false;
        Long last = LAST_ASK.get(p.getUniqueID());
        if (last != null && System.currentTimeMillis() - last < ChatConfig.ai.askCooldown * 1000L) {
            Router.tell(p, "§8(" + ChatConfig.server.botName + " needs a moment — try again in a few seconds)");
            return false;
        }
        LAST_ASK.put(p.getUniqueID(), System.currentTimeMillis());
        return true;
    }

    /** answer a question to these players (with real recipe / item facts from PrideMind's knowledge tools when installed) */
    static void answer(EntityPlayerMP p, String question, Collection<EntityPlayerMP> to) {
        if (!askAllowed(p)) return;
        CompletableFuture.supplyAsync(() -> knowledge(p, question), POOL).thenAccept(facts -> {
            String prompt = p.getName() + " asks: " + question + (facts.isEmpty() ? "" : "\n\nFacts from the game (trust these, don't invent recipes):\n" + facts);
            ask("assistant", String.format(BOT_SYSTEM, ChatConfig.server.botName), prompt, 200, 30000)
                    .thenAccept(a -> later(() -> Router.bot(to, ChatConfig.server.botName,
                            a == null || a.isEmpty() ? "I can't think right now (the AI is off or busy) — try /ch help or ask a person :heart:" : a)));
        });
    }

    private static final Set<String> STOP = new HashSet<>(Arrays.asList("how", "do", "i", "you", "can", "make", "craft", "crafting", "a", "an", "the", "what", "is",
            "are", "for", "to", "of", "get", "where", "find", "use", "used", "uses", "with", "recipe", "recipes", "does", "it", "my", "in", "on", "and", "or", "me",
            "please", "pride", "item", "block", "whats", "what's", "about", "tell", "need", "build", "made", "from", "into", "this", "that", "be"));

    /** item_info + recipes_for / uses_of for the item the question is about — "" when PrideMind isn't installed or nothing matched */
    static String knowledge(EntityPlayerMP p, String question) {
        try {
            Class<?> tools = Class.forName("com.dogpound.pridemind.api.Tools");
            Class<?> ctxC = Class.forName("com.dogpound.pridemind.api.ToolContext");
            Class<?> lvl = Class.forName("com.dogpound.pridemind.api.Tool$Level");
            Object look = null;
            for (Object o : lvl.getEnumConstants()) if ("LOOK".equals(o.toString())) look = o;
            Object ctx = ctxC.getConstructor(net.minecraft.server.MinecraftServer.class, net.minecraft.world.WorldServer.class, EntityPlayerMP.class,
                    net.minecraft.util.math.BlockPos.class, lvl).newInstance(Router.server(), p.getServerWorld(), p, p.getPosition(), look);
            java.lang.reflect.Method call = tools.getMethod("call", ctxC, String.class, JsonObject.class);
            // the longest run of non-filler words that names an item
            List<String> words = new ArrayList<>();
            for (String w : question.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 _'-]", " ").split("\\s+")) if (!w.isEmpty() && !STOP.contains(w)) words.add(w);
            String id = null;
            outer:
            for (int n = Math.min(3, words.size()); n >= 1; n--)
                for (int i = 0; i + n <= words.size(); i++) {
                    String q = String.join(" ", words.subList(i, i + n));
                    if (q.length() < 3) continue;
                    JsonObject a = new JsonObject(); a.addProperty("text", q); a.addProperty("limit", 30);
                    JsonArray found = new JsonParser().parse((String) call.invoke(null, ctx, "search_items", a)).getAsJsonArray();
                    String best = null; int bestLen = Integer.MAX_VALUE;
                    for (int k = 0; k < found.size(); k++) {
                        JsonObject f = found.get(k).getAsJsonObject();
                        String name = f.get("name").getAsString();
                        if (name.equalsIgnoreCase(q)) { best = f.get("id").getAsString(); break; }
                        if (name.length() < bestLen) { bestLen = name.length(); best = f.get("id").getAsString(); }
                    }
                    if (best != null) { id = best; break outer; }
                }
            if (id == null) return "";
            String low = question.toLowerCase(Locale.ROOT);
            JsonObject a = new JsonObject(); a.addProperty("item", id); a.addProperty("limit", 3);
            StringBuilder b = new StringBuilder("item_info: ").append(call.invoke(null, ctx, "item_info", a)).append('\n');
            if (!low.matches(".*\\b(use|used|uses|for)\\b.*") || low.matches(".*\\b(craft|make|recipe|get)\\b.*"))
                b.append("recipes_for: ").append(call.invoke(null, ctx, "recipes_for", a)).append('\n');
            if (low.matches(".*\\b(use|used|uses|for)\\b.*")) { a.addProperty("limit", 8); b.append("uses_of: ").append(call.invoke(null, ctx, "uses_of", a)).append('\n'); }
            return b.length() > 3500 ? b.substring(0, 3500) : b.toString();
        } catch (Throwable t) { return ""; }
    }

    // ------------------------------------------------------------------ catch-up summary
    static void left(EntityPlayerMP p) { LAST_SEEN.put(p.getUniqueID(), System.currentTimeMillis()); }

    static void catchUp(EntityPlayerMP p) {
        if (!ChatConfig.ai.enabled || !ChatConfig.ai.catchUp) return;
        Long seen = LAST_SEEN.get(p.getUniqueID());
        if (seen == null || System.currentTimeMillis() - seen < ChatConfig.ai.catchUpMinutes * 60000L) return;
        summarize(p, seen);
    }

    static void summarize(EntityPlayerMP p, long since) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (Object[] h : Router.HISTORY) {
            NBTTagCompound meta = (NBTTagCompound) h[0];
            if (meta.getLong("t") < since) continue;
            try { b.append(ITextComponent.Serializer.jsonToComponent((String) h[1]).getUnformattedText()).append('\n'); n++; } catch (Throwable ignored) {}
        }
        if (n < 3) return;
        ask("catchup", "Summarize this Minecraft chat for a player who was away, in 2-3 short friendly sentences. Plain text.", b.toString(), 160, 30000)
                .thenAccept(a -> later(() -> { if (a != null && !a.isEmpty()) Router.bot(Collections.singletonList(p), ChatConfig.server.botName, "While you were away: " + a); }));
    }

    // ------------------------------------------------------------------ requests from a PrideChat client
    static void fromClient(EntityPlayerMP p, NBTTagCompound t, String text) {
        String kind = t.getString("kind");
        if (text.length() > 4000) text = text.substring(0, 4000);
        NBTTagCompound o = new NBTTagCompound();
        o.setString("kind", kind);
        o.setLong("id", t.getLong("id"));
        o.setString("to", t.getString("to"));
        String system, prompt;
        switch (kind) {
            case "translate":
                if (!ChatConfig.ai.translate) return;
                system = "Translate the message into " + t.getString("lang") + ". Reply with only the translation.";
                prompt = text;
                break;
            case "tone":
                if (!ChatConfig.ai.tone) return;
                system = "Does this chat message sound harsh or hurtful? Reply JSON only: {\"harsh\":true/false,\"gentler\":\"<a kinder way to say it>\"}";
                prompt = text;
                break;
            case "autoreply":
                if (!ChatConfig.ai.autoReply) return;
                system = "Write a short, friendly away-message reply to a private message, in the style of these examples of how the player writes:\n"
                        + t.getString("style") + "\nSay you're away and will answer later. One sentence, plain text.";
                prompt = text;
                break;
            case "ask": {
                Collection<EntityPlayerMP> to = Collections.singletonList(p);
                answer(p, text, to);
                return;
            }
            case "catchup": summarize(p, 0); return;
            default: return;
        }
        if (!ChatConfig.ai.enabled) { o.setBoolean("off", true); Net.send(p, Net.S_AI, o, ""); return; }
        ask(kind, system, prompt, 200, 20000).thenAccept(a -> later(() -> Net.send(p, Net.S_AI, o, a == null ? "" : a)));
    }

    private AI() {}
}
