package yhjmew.minecraft.nbteditor;

import android.graphics.Color;

public class MapColorPalette {

    // 基岩版地图颜色表 (ID 0 ~ 61)
    // 数据来源: Minecraft Wiki
    private static final int[] BEDROCK_COLORS = {
            0x000000, // 0: None (Transparent)
            0x7F7C78, // 1: Grass
            0xF7E9A3, // 2: Sand
            0xA7A7A7, // 3: Wool
            0xFF0000, // 4: Fire
            0xA0A0FF, // 5: Ice
            0xA7A7A7, // 6: Metal
            0x007C00, // 7: Plant
            0xFFFFFF, // 8: Snow
            0xA4A8B8, // 9: Clay
            0x976D4D, // 10: Dirt
            0x707070, // 11: Stone
            0x4040FF, // 12: Water
            0x8F7748, // 13: Wood
            0xFFFCF5, // 14: Quartz
            0xD87F33, // 15: Orange
            0xB24CD8, // 16: Magenta
            0x6699D8, // 17: Light Blue
            0xE5E533, // 18: Yellow
            0x7FCC19, // 19: Lime
            0xF27FA5, // 20: Pink
            0x4C4C4C, // 21: Gray
            0x999999, // 22: Light Gray
            0x4C7F99, // 23: Cyan
            0x7F3FB2, // 24: Purple
            0x334CB2, // 25: Blue
            0x664C33, // 26: Brown
            0x667F33, // 27: Green
            0x993333, // 28: Red
            0x191919, // 29: Black
            0xFAEE4D, // 30: Gold
            0x5CDB95, // 31: Diamond
            0x4A80FF, // 32: Lapis
            0x00D93A, // 33: Emerald
            0x815631, // 34: Podzol
            0x700200, // 35: Netherrack
            0xD1B1A1, // 36: White Terracotta
            0x9F5224, // 37: Orange Terracotta
            0x95576C, // 38: Magenta Terracotta
            0x706C8A, // 39: Light Blue Terracotta
            0xBA8524, // 40: Yellow Terracotta
            0x677535, // 41: Lime Terracotta
            0xA04D4E, // 42: Pink Terracotta
            0x392923, // 43: Gray Terracotta
            0x876B62, // 44: Light Gray Terracotta
            0x575C5C, // 45: Cyan Terracotta
            0x7A4958, // 46: Purple Terracotta
            0x4C3E5C, // 47: Blue Terracotta
            0x4C3223, // 48: Brown Terracotta
            0x4C522A, // 49: Green Terracotta
            0x8E3C2E, // 50: Red Terracotta
            0x251610, // 51: Black Terracotta

            // [新增 52-61]
            0xBD3031, // 52: Crimson Nylium (绯红菌岩)
            0x943F61, // 53: Crimson Stem (绯红菌柄)
            0x5C191D, // 54: Crimson Hyphae (绯红菌核)
            0x167E86, // 55: Warped Nylium (诡异菌岩)
            0x3A8E8C, // 56: Warped Stem (诡异菌柄)
            0x562C3E, // 57: Warped Hyphae (诡异菌核)
            0x14B485, // 58: Warped Wart Block (诡异疣块)
            0x646464, // 59: Deepslate (深板岩)
            0xD8AF93, // 60: Raw Iron (粗铁)
            0x7FA796 // 61: Glow Lichen (发光地衣)
    };

    /** 计算最接近的地图颜色 ID (使用 CIE76 或 欧几里得距离) */
    public static byte getBestMatchColor(int pixelColor) {
        if (Color.alpha(pixelColor) < 128) return 0; // 透明

        int r = Color.red(pixelColor);
        int g = Color.green(pixelColor);
        int b = Color.blue(pixelColor);

        int bestIndex = 1;
        double minDistance = Double.MAX_VALUE;

        // 遍历所有 62 种颜色 (从索引1开始，跳过透明)
        for (int i = 1; i < BEDROCK_COLORS.length; i++) {
            int base = BEDROCK_COLORS[i];
            int br = (base >> 16) & 0xFF;
            int bg = (base >> 8) & 0xFF;
            int bb = (base) & 0xFF;

            // 加权欧几里得距离 (人眼视觉权重)
            double distance = Math.pow(r - br, 2) * 0.30 +
                    Math.pow(g - bg, 2) * 0.59 +
                    Math.pow(b - bb, 2) * 0.11;

            if (distance < minDistance) {
                minDistance = distance;
                bestIndex = i;
            }
        }

        return (byte) bestIndex;
    }
}