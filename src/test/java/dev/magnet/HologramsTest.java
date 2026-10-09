package dev.magnet;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HologramsTest {

    @Test
    void transparentAndEmptyMeanNoBackground() {
        assertEquals(0, Holograms.parseBackground("transparent").getAlpha());
        assertEquals(0, Holograms.parseBackground("").getAlpha());
        assertEquals(0, Holograms.parseBackground(null).getAlpha());
        assertEquals(0, Holograms.parseBackground("not a colour").getAlpha());
    }

    @Test
    void eightDigitsAreOpacityFirst() {
        Color half = Holograms.parseBackground("#80102030");
        assertEquals(0x80, half.getAlpha());
        assertEquals(0x10, half.getRed());
        assertEquals(0x20, half.getGreen());
        assertEquals(0x30, half.getBlue());
    }

    @Test
    void sixDigitsGetALightBackground() {
        Color light = Holograms.parseBackground("#ff0000");
        assertEquals(0x40, light.getAlpha());
        assertEquals(0xff, light.getRed());
    }
}
