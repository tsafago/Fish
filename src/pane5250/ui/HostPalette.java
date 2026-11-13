package pane5250.ui;

import java.awt.Color;
import java.util.*;
import pane5250.model.ColorRole;

public final class HostPalette {
    private static final Map<String,Color[]> CACHE=new HashMap<>();
    private HostPalette() {}
    public static Color[] colors(String name) {
        if (Ui.customTheme()) {
            String key="custom/"+Ui.colorTheme().hashCode();
            return CACHE.computeIfAbsent(key,ignored -> {
                Color[] colors=new Color[16];
                for (int i=0;i<16;i++) colors[i]=Ui.color(ColorRole.valueOf("HOST_"+i));
                return colors;
            });
        }
        String key=Ui.theme().name()+"/"+name;
        return CACHE.computeIfAbsent(key,ignored -> build(name));
    }
    public static void clear() { CACHE.clear(); }
    private static Color[] build(String name) {
        int[] values=Ui.theme().dark
                ? new int[]{0,0x8daefa,0x9bdb98,0x84d9df,0xf69b9b,0xd8a0db,0xe3d78c,0xe4e5df,
                    0xb5b9bc,0xacc7ff,0xb1edab,0xa0f0ee,0xffb4b4,0xf4b8f2,0xffeca3,0xffffff}
                : new int[]{0,0x244aab,0x286033,0x00646e,0x9e2535,0x753583,0x705600,0x27323a,
                    0x4d5662,0x234c91,0x245b2a,0x00575a,0x9f2332,0x74288a,0x775200,0x141c24};
        boolean dark=Ui.theme().dark;
        switch (name) {
            case "Azure vivid" -> values=dark
                    ? new int[]{0,0x93b8ff,0x73d4ff,0x83e3dc,0xff9c9c,0xe1b0ff,0xffd174,0xecf4ff,
                        0xb4c4d6,0xb8d1ff,0xb0e8ff,0xb7f5ed,0xffc1c1,0xf0ceff,0xffe8b0,0xffffff}
                    : new int[]{0,0x003cb8,0x005eb8,0x006761,0xb0223e,0x8525a1,0x795500,0x253249,
                        0x4c6077,0x002b84,0x004782,0x004d48,0x82152b,0x611975,0x574000,0x131e30};
            case "Crimson vivid" -> values=dark
                    ? new int[]{0,0xaeb8ff,0xff97bb,0x83dce4,0xffb18f,0xeab0ff,0xffd889,0xffedf4,
                        0xcab6c4,0xd0d5ff,0xffc4d8,0xb5f3f7,0xffd2bd,0xf3d0ff,0xffe9b6,0xffffff}
                    : new int[]{0,0x4236a8,0xb01750,0x006773,0xa83b12,0x852796,0x795500,0x402434,
                        0x705066,0x30277b,0x820a38,0x004d56,0x7c2a0a,0x621c70,0x574000,0x28131f};
            case "Cool" -> values[2]=dark ? 0x99d4ba : 0x22634b;
            case "Warm" -> values[2]=dark ? 0xbcd993 : 0x465f22;
            case "Amber" -> values[2]=dark ? 0xffba73 : 0x794700;
            case "Ice" -> values[2]=dark ? 0xb7e8fa : 0x185d78;
            case "Orchid" -> values[2]=dark ? 0xd6b6ff : 0x66338d;
            case "High contrast" -> { values[2]=dark ? 0x9cff94 : 0x14501c; values[7]=dark ? 0xffffff : 0x000000; }
            case "Mint" -> { values[2]=dark ? 0x82e6bd : 0x126146; values[10]=dark ? 0xb5ffdc : 0x004b32; }
            case "Rose" -> { values[2]=dark ? 0xf3b5ce : 0x86334e; values[10]=dark ? 0xffd5e5 : 0x6d1b3c; }
            case "OLED green" -> { values[2]=dark ? 0x48f88c : 0x00692f; values[10]=dark ? 0xafffca : 0x004a20; }
            case "OLED amber" -> { values[2]=dark ? 0xffbd4a : 0x754600; values[10]=dark ? 0xffe0a1 : 0x5c3500; }
            case "IBM vivid" -> {
                if (dark) values=new int[]{0,0x6699ff,0x00ff66,0x00ffff,0xff7070,0xff80ff,0xffff00,0xffffff,
                        0xb0b0b0,0xa9c3ff,0x99ff99,0xa0ffff,0xffb0b0,0xffbfff,0xffffb0,0xffffff};
                else values=new int[]{0,0x0036ad,0x006326,0x005e68,0xb00022,0x8c167f,0x705600,0x151515,
                        0x484848,0x00236e,0x004a19,0x00464e,0x800016,0x640c5b,0x513d00,0x000000};
            }
            default -> {}
        }
        values[0]=Ui.BACKGROUND.getRGB();
        Color[] colors=new Color[16];
        for (int i=0;i<16;i++) colors[i]=new Color(values[i]);
        return colors;
    }
}
