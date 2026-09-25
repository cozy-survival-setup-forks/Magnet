package dev.magnet;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MessagesTest {

    @Test
    void oldCodesBecomeTags() {
        assertEquals("<#ff8800>Hi", Messages.convertLegacy("&#ff8800Hi"));
        assertEquals("<gray>a<red>b<bold>c<reset>", Messages.convertLegacy("&7a&cb&lc&r"));
    }
}
