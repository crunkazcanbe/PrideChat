package com.dogpound.pridechat.api;

import net.minecraftforge.fml.common.eventhandler.Cancelable;
import net.minecraftforge.fml.common.eventhandler.Event;

/**
 * CLIENT side, Forge event bus (roadmap #37): a speech bubble is about to appear above `name`. Change the text or the
 * style (rounded, square, mc, comic, speech, thought, dark, transparent, glass, pixel, holo, minimal) or volume
 * (0 tiny, 1 normal, 2 big, 3 giant), or cancel it — the chat line still shows.
 */
@Cancelable
public class BubbleEvent extends Event {
    public final String name;
    public final long messageId;
    public String text, style;
    public int volume;

    public BubbleEvent(String name, String text, String style, int volume, long messageId) {
        this.name = name; this.text = text; this.style = style; this.volume = volume; this.messageId = messageId;
    }
}
