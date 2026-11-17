package fish.ui;

import java.awt.Color;
import java.util.*;
import fish.model.ColorRole;
import fish.model.ColorTheme;
import fish.model.Profile;

public final class HostPalette {
    private static final Map<String,Color[]> CACHE=new HashMap<>();
    private HostPalette() {}

    public static Color[] colors(String name) {
        if (Profile.isCustomColor(name)) {
            int bg=Ui.BACKGROUND.getRGB()&0xffffff;
            boolean dark=Ui.dark();
            String key=(dark ? "dark:" : "light:")+Integer.toHexString(bg)+"/"+name.toUpperCase(Locale.ROOT);
            return CACHE.computeIfAbsent(key,ignored -> fromPrimaryColor(Profile.parseCustomColor(name),dark,Ui.BACKGROUND));
        }
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

    public static int primaryPreviewRgb(String theme) {
        Color[] palette=colors(theme);
        return palette[2].getRGB()&0xffffff;
    }

    public static Color[] fromPrimaryColor(int primaryRgb,boolean dark) {
        Color defaultBg=dark ? new Color(0x171a1f) : new Color(0xf7f5f0);
        Color bg=Ui.BACKGROUND!=null && Ui.dark()==dark ? Ui.BACKGROUND : defaultBg;
        return fromPrimaryColor(primaryRgb,dark,bg);
    }

    public static Color[] fromPrimaryColor(int primaryRgb,boolean dark,Color background) {
        int bgRgb=(background!=null ? background.getRGB() : (dark ? 0x171a1f : 0xf7f5f0))&0xffffff;
        int[] values=baselineValues(dark);
        double[] hsl=rgbToHsl(primaryRgb);
        double hue=hsl[0],sat=hsl[1];
        if (sat<0.10) {
            values[2]=solveContrastColor(hue,sat,dark ? 8.2 : 5.8,dark ? 10.4 : 7.4,dark ? 0.80 : 0.26,bgRgb,dark);
            values[10]=solveContrastColor(hue,sat*0.6,dark ? 11.4 : 7.8,dark ? 14.8 : 10.6,dark ? 0.92 : 0.16,bgRgb,dark);
        } else {
            double pSat=Math.max(0.52,Math.min(0.86,sat));
            double lSat=Math.max(0.58,Math.min(0.88,sat));
            values[2]=solveContrastColor(hue,dark ? pSat : lSat,dark ? 7.4 : 5.4,dark ? 10.0 : 7.0,dark ? 0.72 : 0.30,bgRgb,dark);
            double baseContrast=contrastRatio(values[2],bgRgb);
            if (dark) {
                double hiMin=Math.min(13.5,baseContrast+1.9);
                values[10]=solveContrastColor(hue,Math.max(0.42,pSat*0.86),hiMin,Math.min(15.6,hiMin+2.2),0.85,bgRgb,true);
            } else {
                double hiMin=Math.min(10.5,baseContrast+1.4);
                values[10]=solveContrastColor(hue,Math.min(0.90,lSat*1.04),hiMin,Math.min(13.2,hiMin+2.0),0.20,bgRgb,false);
            }

            double errHue=0.0;
            if (hueDistance(hue,0.0)<42.0) {
                errHue=(hue>12.0 && hue<180.0) ? 336.0 : 40.0;
                synthesizePair(values,4,12,errHue,0.84,0.78,bgRgb,dark);
            }

            double hdrHue=221.0;
            if (hueDistance(hue,221.0)<42.0) {
                hdrHue=pickFarthestHue(new double[]{268.0,188.0,200.0},hue,errHue);
                synthesizePair(values,1,9,hdrHue,0.76,0.74,bgRgb,dark);
            }

            double cyaHue=184.0;
            if (hueDistance(hue,184.0)<40.0 || hueDistance(hdrHue,184.0)<28.0) {
                cyaHue=pickFarthestHue(new double[]{145.0,212.0,162.0},hue,hdrHue,errHue);
                synthesizePair(values,3,11,cyaHue,0.66,0.78,bgRgb,dark);
            }

            double yelHue=52.0;
            if (hueDistance(hue,52.0)<40.0 || hueDistance(errHue,52.0)<28.0) {
                yelHue=pickFarthestHue(new double[]{90.0,24.0,76.0},hue,errHue,cyaHue);
                synthesizePair(values,6,14,yelHue,0.72,0.82,bgRgb,dark);
            }

            if (hueDistance(hue,297.0)<40.0 || hueDistance(hdrHue,297.0)<28.0 || hueDistance(errHue,297.0)<28.0) {
                double magHue=pickFarthestHue(new double[]{258.0,328.0,278.0},hue,hdrHue,errHue);
                synthesizePair(values,5,13,magHue,0.64,0.66,bgRgb,dark);
            }

            values[7]=ColorTheme.mix(values[7],values[2],0.06)&0xffffff;
            values[8]=ColorTheme.mix(values[8],values[2],0.08)&0xffffff;
        }
        values[0]=bgRgb;
        Color[] colors=new Color[16];
        for (int i=0;i<16;i++) colors[i]=new Color(values[i]&0xffffff);
        return colors;
    }

    private static void synthesizePair(int[] values,int normalSlot,int brightSlot,double hue,double darkSat,double lightSat,int bgRgb,boolean dark) {
        values[normalSlot]=solveContrastColor(hue,dark ? darkSat : lightSat,dark ? 6.4 : 4.8,dark ? 9.2 : 6.6,dark ? 0.74 : 0.32,bgRgb,dark);
        double nc=contrastRatio(values[normalSlot],bgRgb);
        values[brightSlot]=dark
                ? solveContrastColor(hue,darkSat*0.88,Math.min(13.0,nc+1.8),15.2,0.84,bgRgb,true)
                : solveContrastColor(hue,lightSat,Math.min(9.8,nc+1.2),12.2,0.22,bgRgb,false);
    }

    private static double pickFarthestHue(double[] candidates,double... avoid) {
        double best=candidates[0],bestMin=-1;
        for (double cand:candidates) {
            double minDist=360.0;
            for (double a:avoid) minDist=Math.min(minDist,hueDistance(cand,a));
            if (minDist>bestMin) { bestMin=minDist; best=cand; }
        }
        return best;
    }

    public static double hueDistance(double a,double b) {
        double diff=Math.abs(((a%360.0)+360.0)%360.0-(((b%360.0)+360.0)%360.0));
        return diff>180.0 ? 360.0-diff : diff;
    }

    public static double hueOfRgb(int rgb) {
        return rgbToHsl(rgb)[0];
    }

    public static double contrastRatio(int rgbA,int rgbB) {
        double la=relativeLuminance(rgbA),lb=relativeLuminance(rgbB);
        return (Math.max(la,lb)+0.05)/(Math.min(la,lb)+0.05);
    }

    private static double relativeLuminance(int rgb) {
        double r=linearize(((rgb>>16)&255)/255.0);
        double g=linearize(((rgb>>8)&255)/255.0);
        double b=linearize((rgb&255)/255.0);
        return 0.2126*r+0.7152*g+0.0722*b;
    }

    private static double linearize(double c) {
        return c<=0.03928 ? c/12.92 : Math.pow((c+0.055)/1.055,2.4);
    }

    private static int solveContrastColor(double hue,double sat,double minContrast,double maxContrast,double preferredLight,int bgRgb,boolean dark) {
        int pref=hslToRgb(hue,sat,preferredLight);
        double c=contrastRatio(pref,bgRgb);
        if (c>=minContrast && c<=maxContrast) return pref;
        double target=c<minContrast
                ? minContrast+0.35*(maxContrast-minContrast)
                : maxContrast-0.25*(maxContrast-minContrast);
        double lo,hi;
        if (dark) {
            lo=c<minContrast ? preferredLight : 0.18;
            hi=c<minContrast ? 0.96 : preferredLight;
        } else {
            lo=c<minContrast ? 0.06 : preferredLight;
            hi=c<minContrast ? preferredLight : 0.68;
        }
        for (int i=0;i<24;i++) {
            double mid=0.5*(lo+hi);
            double mc=contrastRatio(hslToRgb(hue,sat,mid),bgRgb);
            if (dark) {
                if (mc<target) lo=mid; else hi=mid;
            } else {
                if (mc<target) hi=mid; else lo=mid;
            }
        }
        return hslToRgb(hue,sat,0.5*(lo+hi));
    }

    private static double[] rgbToHsl(int rgb) {
        double r=((rgb>>16)&255)/255.0,g=((rgb>>8)&255)/255.0,b=(rgb&255)/255.0;
        double max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));
        double l=0.5*(max+min),d=max-min;
        if (d<1e-7) return new double[]{0.0,0.0,l};
        double s=l>0.5 ? d/(2.0-max-min) : d/(max+min);
        double h;
        if (max==r) h=((g-b)/d+(g<b ? 6.0 : 0.0))*60.0;
        else if (max==g) h=((b-r)/d+2.0)*60.0;
        else h=((r-g)/d+4.0)*60.0;
        return new double[]{h>=360.0 ? h-360.0 : h,s,l};
    }

    private static int hslToRgb(double hueDeg,double sat,double light) {
        double s=Math.max(0.0,Math.min(1.0,sat));
        double l=Math.max(0.0,Math.min(1.0,light));
        if (s<1e-6) {
            int gray=(int)Math.round(l*255.0);
            return (gray<<16)|(gray<<8)|gray;
        }
        double h=(((hueDeg%360.0)+360.0)%360.0)/360.0;
        double q=l<0.5 ? l*(1.0+s) : l+s-l*s;
        double p=2.0*l-q;
        int r=(int)Math.round(255.0*hueToChannel(p,q,h+1.0/3.0));
        int g=(int)Math.round(255.0*hueToChannel(p,q,h));
        int b=(int)Math.round(255.0*hueToChannel(p,q,h-1.0/3.0));
        return (Math.max(0,Math.min(255,r))<<16)|(Math.max(0,Math.min(255,g))<<8)|Math.max(0,Math.min(255,b));
    }

    private static double hueToChannel(double p,double q,double t) {
        if (t<0.0) t+=1.0;
        if (t>1.0) t-=1.0;
        if (t<1.0/6.0) return p+(q-p)*6.0*t;
        if (t<0.5) return q;
        if (t<2.0/3.0) return p+(q-p)*(2.0/3.0-t)*6.0;
        return p;
    }

    private static int[] baselineValues(boolean dark) {
        return dark
                ? new int[]{0,0x8daefa,0x9bdb98,0x84d9df,0xf69b9b,0xd8a0db,0xe3d78c,0xe4e5df,
                    0xb5b9bc,0xacc7ff,0xb1edab,0xa0f0ee,0xffb4b4,0xf4b8f2,0xffeca3,0xffffff}
                : new int[]{0,0x244aab,0x286033,0x00646e,0x9e2535,0x753583,0x705600,0x27323a,
                    0x4d5662,0x234c91,0x245b2a,0x00575a,0x9f2332,0x74288a,0x775200,0x141c24};
    }

    private static Color[] build(String name) {
        boolean dark=Ui.theme().dark;
        int[] values=baselineValues(dark);
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
