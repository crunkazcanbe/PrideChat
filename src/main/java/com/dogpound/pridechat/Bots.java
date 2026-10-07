package com.dogpound.pridechat;

import net.minecraft.entity.player.EntityPlayerMP;

import java.util.*;
import java.util.regex.Pattern;

/** Auto-responders from the server config (roadmap #36) and "@Pride …" questions for the AI helper. */
final class Bots {
    private static final Map<String, Pattern> CACHE = new HashMap<>();

    static void heard(Router.Msg m) {
        if (m.sender == null || m.shadow) return;
        List<EntityPlayerMP> audience = new ArrayList<>();
        List<UUID> ids = Router.RECIPIENTS.get(m.id);
        if (ids != null) for (UUID u : ids) { EntityPlayerMP r = Router.server().getPlayerList().getPlayerByUUID(u); if (r != null) audience.add(r); }
        if (audience.isEmpty()) audience.add(m.sender);
        String tag = "@" + ChatConfig.server.botName.toLowerCase(Locale.ROOT);
        String low = m.text.toLowerCase(Locale.ROOT);
        if (low.contains(tag)) {
            AI.answer(m.sender, m.text.replaceAll("(?i)" + Pattern.quote(tag), "").trim(), audience);
            return;
        }
        for (String line : ChatConfig.server.bots) {
            String[] p = line.split("\\s*\\|\\s*", 2);
            if (p.length < 2) continue;
            Pattern re = CACHE.computeIfAbsent(p[0].trim(), k -> { try { return Pattern.compile(k); } catch (Exception e) { return null; } });
            if (re == null || !re.matcher(m.text).find()) continue;
            String reply = p[1].replace("{name}", m.sender.getName());
            Router.server().addScheduledTask(() -> {
                if (reply.startsWith("/")) Router.server().getCommandManager().executeCommand(Router.server(), reply.substring(1));
                else Router.bot(audience, ChatConfig.server.botName, reply);
            });
            return;
        }
    }

    private Bots() {}
}
