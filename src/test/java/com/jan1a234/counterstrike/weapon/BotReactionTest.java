package com.jan1a234.counterstrike.weapon;

import static org.junit.jupiter.api.Assertions.*;

import com.jan1a234.counterstrike.bot.BotReaction;

import org.junit.jupiter.api.Test;

class BotReactionTest {
    @Test
    void losingSightRequiresFreshReactionTime() {
        BotReaction reaction = new BotReaction();
        Object enemy = new Object();
        assertFalse(reaction.ready(enemy, true, 2));
        assertTrue(reaction.ready(enemy, true, 2));
        assertFalse(reaction.ready(enemy, false, 2));
        assertFalse(reaction.ready(enemy, true, 2));
        assertTrue(reaction.ready(enemy, true, 2));
    }

    @Test
    void absentOrChangedTargetNeverInheritsReactionTime() {
        BotReaction reaction = new BotReaction();
        Object first = new Object();
        assertFalse(reaction.ready(first, true, 2));
        assertTrue(reaction.ready(first, true, 2));
        assertFalse(reaction.ready(new Object(), true, 2));
        assertFalse(reaction.ready(null, false, 1));
    }
}
