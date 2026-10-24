package com.jan1a234.counterstrike.bot;

/** Continuous visual contact required before a bot may pull the trigger. */
public final class BotReaction {
    private Object target;
    private int visibleTicks;

    public boolean ready(Object nextTarget, boolean visible, int reactionTicks) {
        if (nextTarget == null || !visible) {
            reset();
            return false;
        }
        if (target != nextTarget) visibleTicks = 0;
        target = nextTarget;
        visibleTicks++;
        return visibleTicks >= reactionTicks;
    }

    public void reset() {
        target = null;
        visibleTicks = 0;
    }
}
