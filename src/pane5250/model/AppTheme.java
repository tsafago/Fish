package pane5250.model;

/** Application surfaces are independent of a profile's host color palette. */
public enum AppTheme {
    Charcoal(0x101213,0x171a1c,0x202629,0x2c363b,0xdde3e5,0xb1c1c9,0x38464d,0x87bbc8,0xffa3a3,0x304a55,true),
    Graphite(0x1c1d20,0x24262a,0x2d3035,0x3b3f46,0xe8e8eb,0xbbc0ce,0x50545e,0xb9b4ed,0xffaaaa,0x49445f,true),
    Midnight(0x0d1520,0x131f2d,0x1b2a3b,0x293d53,0xe0eaf3,0xacc4d6,0x354e64,0x88c9eb,0xffaaaa,0x274b64,true),
    Paper(0xf2f0ea,0xfaf9f5,0xffffff,0xe2e8eb,0x202d35,0x4f5f69,0xb3bec4,0x005b83,0xa51e32,0xc9dfe9,false);

    public final int background,panel,field,hover,text,muted,line,focus,error,selection;
    public final boolean dark;
    AppTheme(int background,int panel,int field,int hover,int text,int muted,int line,int focus,int error,int selection,boolean dark) {
        this.background=background; this.panel=panel; this.field=field; this.hover=hover; this.text=text;
        this.muted=muted; this.line=line; this.focus=focus; this.error=error; this.selection=selection; this.dark=dark;
    }
}
