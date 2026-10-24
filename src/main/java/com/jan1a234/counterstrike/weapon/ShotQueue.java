package com.jan1a234.counterstrike.weapon;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Server-thread deferred work; callbacks may cancel other queued shots. */
final class ShotQueue<T> {
    private final List<T> entries = new ArrayList<>();

    void add(T entry) {
        entries.add(entry);
    }

    void clear() {
        entries.clear();
    }

    void removeIf(Predicate<T> predicate) {
        entries.removeIf(predicate);
    }

    int size() {
        return entries.size();
    }

    void tick(Predicate<T> due, Consumer<T> fire) {
        // Remove one entry before invoking callbacks. A callback can invalidate
        // any other entry, so a detached due-list alone is not sufficient.
        for (T entry : new ArrayList<>(entries)) {
            if (due.test(entry) && entries.remove(entry)) {
                fire.accept(entry);
            }
        }
    }
}
