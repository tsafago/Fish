/* The opening waterline crosses x=253 at mid-height. */
#include "fish-coast-motion.h"
#include "fish-start-size.h"
enum { DOT_SPACING=19, DOT_BOX_LEFT=98, DOT_BOX_RIGHT=155, DOT_BOX_TOP=99, DOT_BOX_BOTTOM=117 };
static double dot_unit(double n) { return fmin(1,fmax(0,n)); }
static DWORD dot_blend(DWORD a,DWORD b,double t) {
    unsigned n=(unsigned)(256*dot_unit(t)+.5),m=256-n;
    return ((((a&0xff00ff)*m+(b&0xff00ff)*n)>>8)&0xff00ff) |
        ((((a&0xff00)*m+(b&0xff00)*n)>>8)&0xff00);
}
typedef struct {
    const BITMAPINFO *art;
    DWORD *frame;
    ULONGLONG began;
    BOOL plain,moving;
} CoastBanner;
static void start_water(DWORD *dst,const DWORD *ground,int w,int signed_h,BOOL moving,ULONGLONG ms) {
    int h=abs(signed_h);
    double sx=(double)w/FISH_START_WIDTH,phase=6.283185307*(ms%4160)/4160.;
    for (int y=0;y<h;y++) {
        double normalized=(y+.5)/h,line=coast_line(normalized);
        int edge=min(w,(int)(line*sx));
        size_t row=(size_t)(signed_h<0 ? y : h-y-1)*w;
        double bend=1.6*sx*sin(phase+normalized*13)+.6*sx*sin(phase*2-normalized*18);
        for (int x=0;x<edge;x++) {
            double at=x+(moving ? bend*dot_unit((edge-x)/sx/26) : 0);
            at=fmax(0,at);
            int sample=min(edge-1,(int)at);
            dst[row+x]=dot_blend(ground[row+sample],ground[row+min(edge-1,sample+1)],at-sample);
        }
    }
}
/* Both images are DIBs. Always read the original to avoid accumulating pigment. */
static void start_dots(DWORD *dst,int w,int signed_h,BOOL moving,ULONGLONG ms) {
    int h=abs(signed_h);
    double sx=(double)w/FISH_START_WIDTH,sy=(double)h/FISH_START_HEIGHT;
    for (int k=-1;k<=1;k++) {
        double cx=126.5+k*DOT_SPACING,cy=FISH_START_HEIGHT*.5,rad=5.8;
        double beat=moving ? .5+.5*cos(6.283185307*((ms%1920)/1920.-(k+1)*.15)) : .50;
        DWORD well=dot_blend(0x508994,0x346878,beat);
        for (int y=(int)((cy-8)*sy);y<(int)ceil((cy+8)*sy);y++) {
            double dy=(y+.5)/sy-cy;
            size_t row=(size_t)(signed_h<0 ? y : h-y-1)*w;
            for (int x=(int)((cx-8)*sx);x<(int)ceil((cx+8)*sx);x++) {
                double dx=(x+.5)/sx-cx,d=hypot(dx,dy);
                double rim=dot_unit(.5+(rad+.85-d)*sx);
                if (!rim) continue;
                DWORD base=dst[row+x];
                DWORD lip=dot_blend(0x779ca5,0xe0fff0,dot_unit(.5+(dy+.25*dx)/rad));
                DWORD c=dot_blend(base,lip,rim);
                double shade=dot_unit((hypot(dx-.2,dy-1.6)-rad+2.2)/2.2);
                c=dot_blend(c,dot_blend(well,0x315e70,.7*shade),.5+(rad-d)*sx);
                dst[row+x]=c;
            }
        }
    }
}
static void paint_frame(HDC dc,const RECT *r,const CoastBanner *coast,ULONGLONG elapsed) {
    int w=r->right-r->left,h=r->bottom-r->top;
    if (!coast->plain && coast->frame) {
        const BITMAPINFO *art=coast->art;
        const DWORD *ground=(const DWORD *)((const BYTE *)art+art->bmiHeader.biSize);
        start_water(coast->frame,ground,art->bmiHeader.biWidth,art->bmiHeader.biHeight,coast->moving,elapsed);
        start_dots(coast->frame,art->bmiHeader.biWidth,art->bmiHeader.biHeight,coast->moving,elapsed);
        SetStretchBltMode(dc,HALFTONE);
        SetBrushOrgEx(dc,0,0,NULL);
        StretchDIBits(dc,0,0,w,h,0,0,art->bmiHeader.biWidth,abs(art->bmiHeader.biHeight),
            coast->frame,art,DIB_RGB_COLORS,SRCCOPY);
        return;
    }
    FillRect(dc,r,GetStockObject(WHITE_BRUSH));
    HGDIOBJ pen=SelectObject(dc,GetStockObject(NULL_PEN)),brush=SelectObject(dc,GetStockObject(BLACK_BRUSH));
    for (int k=-1;k<=1;k++) {
        int cx=MulDiv(253+2*k*DOT_SPACING,w,2*FISH_START_WIDTH),rad=max(1,MulDiv(6,w,FISH_START_WIDTH));
        Ellipse(dc,cx-rad,h/2-rad,cx+rad,h/2+rad);
    }
    SelectObject(dc,brush); SelectObject(dc,pen);
}
