package pane5250.ui;

import java.awt.Color;
import java.util.*;
import pane5250.model.AppTheme;

public final class HostPalette {
    private static final Map<String,Color[]> CACHE=new HashMap<>();
    private HostPalette() {}
    public static Color[] colors(String name) {
        String key=Ui.theme().name()+"/"+name;
        return CACHE.computeIfAbsent(key,ignored -> build(name));
    }
    private static Color[] build(String name) {
        int[] values=Ui.theme().dark
                ? new int[]{0,0x8daefa,0x9bdb98,0x84d9df,0xf69b9b,0xd8a0db,0xe3d78c,0xe4e5df,
                    0xb5b9bc,0xacc7ff,0xb1edab,0xa0f0ee,0xffb4b4,0xf4b8f2,0xffeca3,0xffffff}
                : new int[]{0,0x244aab,0x286033,0x00646e,0x9e2535,0x753583,0x705600,0x27323a,
                    0x4d5662,0x234c91,0x245b2a,0x00575a,0x9f2332,0x74288a,0x775200,0x141c24};
        boolean dark=Ui.theme().dark;
        switch (name) {
            case "Cool" -> values[2]=dark ? 0x99d4ba : 0x22634b;
            case "Warm" -> values[2]=dark ? 0xbcd993 : 0x465f22;
            case "Amber" -> values[2]=dark ? 0xffba73 : 0x794700;
            case "Ice" -> values[2]=dark ? 0xb7e8fa : 0x185d78;
            case "Orchid" -> values[2]=dark ? 0xd6b6ff : 0x66338d;
            case "High contrast" -> { values[2]=dark ? 0x9cff94 : 0x14501c; values[7]=dark ? 0xffffff : 0x000000; }
            default -> {}
        }
        values[0]=Ui.BACKGROUND.getRGB();
        Color[] colors=new Color[16];
        for (int i=0;i<16;i++) colors[i]=new Color(values[i]);
        return colors;
    }
}
