/* Shared coastline rasterizer, in logical window coordinates. */
enum { FISH_X=366,FISH_Y=8,FISH_W=305,FISH_H=191 };
static HDC art_dc;
static HGDIOBJ art_previous;
static HBITMAP art_bitmap;
static DWORD *art_bits;
/* Cache the sand impression; water and the moving lip are composited over it. */
typedef struct { DWORD sand,sea,shoal,foam,ink,charged; short distance; BYTE along; } CoastPixel;
static CoastPixel *coast;
static double *shore_edges;
static int art_w,art_h;
static double frame_fill;
static double unit(double value) { return fmax(0,fmin(1,value)); }
static COLORREF mix(COLORREF a,COLORREF b,double amount) {
    double t=unit(amount);
    return RGB((int)(GetRValue(a)+(GetRValue(b)-GetRValue(a))*t+.5),
        (int)(GetGValue(a)+(GetGValue(b)-GetGValue(a))*t+.5),
        (int)(GetBValue(a)+(GetBValue(b)-GetBValue(a))*t+.5));
}
/* Light folded into shallow pools. Warp the cells before drawing their rims;
   a broad milky shoulder and a narrower band of light keep them soft, not wireframe.
   This is baked into the sea pigment once, never evaluated by the animation timer. */
static double caustic(double x,double y) {
    static const BYTE sites[][2]={{69,56},{162,131},{112,170},{133,73},
        {149,92},{85,150},{181,115},{104,84},{91,179},{156,62},{71,112},{167,160},
        {184,124},{118,87},{140,156},{88,82}};
    double u=(x+11*sin(.057*y+.018*x)+4*sin(.11*y))/73;
    double v=(y+.12*x+6*sin(.044*x+.020*y))/39;
    int col=(int)floor(u),row=(int)floor(v);
    u-=col; v-=row;
    double first=8,second=8;
    for (int j=-1;j<=1;j++) for (int i=-1;i<=1;i++) {
        const BYTE *p=sites[(((unsigned)(row+j)&3)*4)+((unsigned)(col+i)&3)];
        double dx=i+p[0]/256.-u,dy=j+p[1]/256.-v,d=dx*dx+dy*dy;
        if (d<first) { second=first; first=d; }
        else if (d<second) second=d;
    }
    double rim=second-first,halo=unit(1-rim/.32),line=unit(1-rim/.12);
    double sun=.36+.64*unit(.54+.37*sin(.019*x+.031*y)+.35*cos(.047*y-.008*x));
    return sun*(.24*halo*halo+.36*line*line*(3-2*line));
}
static void reset_art(void) {
    if (coast) { HeapFree(GetProcessHeap(),0,coast); coast=NULL; }
    if (art_dc) {
        SelectObject(art_dc,art_previous); DeleteObject(art_bitmap); DeleteDC(art_dc);
        art_dc=NULL; art_bits=NULL;
    }
}
static short mark_distance(double x,double y,BYTE *along) {
    static const double path[][2]={{659,62},{613,96.2},{611,97},{609,96.8},{607,96},{507,23.1},
        {504.5,21.5},{501.8,20.55},{499.2,20.35},{496.3,20.7},{493.7,21.8},{491.5,23.3},
        {382.1,99.9},{380.2,101.5},{379.3,103.3},{379.2,105.6},{380,107.6},{382.2,109.7},
        {485.7,183.8},{489.5,186.1},{492.6,187},{496,186.9},{499.6,185.8},{502.2,184.1},
        {606.5,110},{609.3,108.5},{611.6,108.3},{614,109.1},{659,139}};
    if (x<FISH_X || x>=FISH_X+FISH_W || y<FISH_Y || y>=FISH_Y+FISH_H) return 32767;
    double best=144,sign=1,run=0,pos=0;
    for (size_t i=1;i<sizeof(path)/sizeof(*path);i++) {
        double dx=path[i][0]-path[i-1][0],dy=path[i][1]-path[i-1][1],len2=dx*dx+dy*dy,len=sqrt(len2);
        double xx=x-path[i-1][0],yy=y-path[i-1][1],t=unit((xx*dx+yy*dy)/len2);
        double ax=xx-t*dx,ay=yy-t*dy,d=ax*ax+ay*ay;
        if (d<best) { best=d; sign=dx*ay-dy*ax<0 ? -1 : 1; pos=run+t*len; }
        run+=len;
    }
    if (best>=144) return 32767;
    *along=(BYTE)((unsigned)(pos*256./116.)&255);
    return (short)lround(sign*sqrt(best)*128);
}
static COLORREF prism(double t) {
    return t<.53 ? mix(RGB(31,205,225),RGB(93,135,255),t/.53)
        : mix(RGB(93,135,255),RGB(192,102,238),(t-.53)/.47);
}
static DWORD dib(COLORREF c) { return RGB(GetBValue(c),GetGValue(c),GetRValue(c)); }
static unsigned coverage(double a) { return (unsigned)(unit(a)*256+.5); }
static DWORD over(DWORD ground,DWORD ink,unsigned a) {
    unsigned b=256-a;
    return ((((ground&0xff00ff)*b+(ink&0xff00ff)*a)>>8)&0xff00ff) |
        ((((ground&0xff00)*b+(ink&0xff00)*a)>>8)&0xff00);
}
/* A pair of fluted cockles printed into the sand, not attached to the tide. */
static COLORREF cockle(COLORREF c,double x,double y,double size,double lean,double ds) {
    if (fabs(x)>size*1.3 || fabs(y)>size*1.3) return c;
    double u=(x+lean*y)/size,v=(y-lean*x)/size,r=hypot(u,v),angle=atan2(u,-v);
    double lip=.92+.05*cos(angle*15),edge=fmin(lip-r,(1.12-fabs(angle))*r)*size;
    COLORREF line=RGB(109,91,170);
    COLORREF nacre=RGB(216,196,248);
    double ribs=unit(.5+ds*(.42-fabs(sin(angle*7.5))*r*size/7.5))*unit((r-.16)*6);
    COLORREF fill=mix(nacre,line,fmax(unit(1-edge*ds),ribs*.80));
    return mix(c,fill,unit(.5+edge*ds));
}
static COLORREF sea_star(COLORREF c,double x,double y,double ds) {
    if (fabs(x)>13 || fabs(y)>13) return c;
    double a=atan2(y,x)+.24,r=hypot(x,y);
    double arm=.5+.5*cos(5*a),edge=4.2+7.7*arm*arm*arm-r;
    COLORREF line=RGB(183,89,99);
    double freckles=unit((cos(8*r)*cos(a*5)-.55)*3)*unit(1-r/9);
    COLORREF ink=mix(RGB(252,159,144),line,fmax(unit(1.3-edge*ds),freckles));
    return mix(c,ink,unit(.5+ds*edge));
}
#include "fish-coast-motion.h"
/* The tide enters from the copy side and travels across the sand. */
static double waterline(double y,double tide,double drift) {
    return coast_line((y+tide)/logical_height())+drift;
}
/* Tiny pointed tails and a single eye keep the shoal legible at 100% DPI. */
static void shore_sprite(DWORD *top,double cx,double cy,double size,BOOL bubble,int facing) {
    double ds=dpi/96.,span=size*2+2;
    int l=max(0,(int)floor((cx-span)*ds)),r=min(art_w,(int)ceil((cx+span)*ds));
    int t=max(0,(int)floor((cy-size-2)*ds)),b=min(art_h,(int)ceil((cy+size+2)*ds));
    DWORD ink=bubble ? 0x87bdbd : 0x6a9099;
    for (int j=t;j<b;j++) for (int i=l;i<r;i++) {
        if ((i+.5)/ds>shore_edges[j]) continue;
        double x=((i+.5)/ds-cx)*facing,y=(j+.5)/ds-cy;
        double edge;
        if (bubble) edge=.62-fabs(hypot(x,y)-size);
        else {
            double body=size*.48-hypot(x*.48,y);
            double tail=fmin(x+size*1.7,fmin(-x-size*.62,(-x-size*.60)*.55-fabs(y)));
            edge=fmax(body,tail);
        }
        DWORD *p=top+(size_t)j*art_w+i;
        *p=over(*p,ink,coverage(unit(.5+ds*edge)*(bubble ? .89 : .82)));
        if (!bubble)
            *p=over(*p,0xe7f4ed,coverage(.5+ds*(.58-hypot(x-size*.52,y+.30))));
    }
}
/* Tapered currents, each with a little of its own slack. */
static void current(double x0,double y0,double width,double rise,double thick,double phase,DWORD ink) {
    double ds=dpi/96.;
    for (int i=max(0,(int)(x0*ds));i<min(art_w,(int)ceil((x0+width)*ds));i++) {
        double u=((i+.5)/ds-x0)/width,s=sin(3.141593*u);
        double mid=y0+rise*(.5-u)+s*(3*sin(u*6.28+phase)),radius=thick*s*s;
        int t=max(0,(int)((mid-radius)*ds)-1),b=min(art_h,(int)((mid+radius)*ds)+2);
        for (int j=t;j<b;j++) {
            if ((i+.5)/ds>shore_edges[j]-3) continue;
            double a=unit(.5+(radius-fabs((j+.5)/ds-mid))*ds)*unit(4*s);
            DWORD *p=art_bits+(size_t)j*art_w+i;
            *p=over(*p,ink,coverage(a*.56));
        }
    }
}
typedef struct { double x,y; } CoastPoint;
/* One brush shape inside the mark. Sample its closed cubic outline, then
   scan-convert two subpixel rows for a smooth lip even at 100% scaling. */
static void little_breaker(double phase,double strength,DWORD ink) {
    static const CoastPoint shape[]={{311,119},
        {332,128},{343,96},{364,96},{375,95},{386,101},{389,111},
        {385,106},{382,104},{379,104},{380,110},{376,110},{373,105},
        {373,110},{368,107},{366,104},{345,97},{334,133},{311,119}};
    enum { N=(sizeof(shape)/sizeof(*shape)-1)/3*8 };
    CoastPoint poly[N+1]; poly[0]=shape[0];
    for (int i=0;i<N;i++) {
        const CoastPoint *p=shape+(i/8)*3; double t=(1+i%8)/8.,v=1-t;
        poly[i+1]=(CoastPoint){v*v*v*p[0].x+3*v*v*t*p[1].x+3*v*t*t*p[2].x+t*t*t*p[3].x,
            v*v*v*p[0].y+3*v*v*t*p[1].y+3*v*t*t*p[2].y+t*t*t*p[3].y};
    }
    for (int i=0;i<=N;i++) { poly[i].x=380+(poly[i].x-311)*1.30; poly[i].y=104+(poly[i].y-119)*.98; }
    double ds=dpi/96.,dx=1.3*sin(phase+.3),dy=1.9*sin(phase+.7);
    for (int j=px(76);j<min(art_h,px(118));j++) for (int half=0;half<2;half++) {
        double y=(j+.25+.5*half)/ds-dy,cross[N]; int n=0;
        for (int k=0;k<N;k++) {
            CoastPoint a=poly[k],b=poly[k+1];
            if ((a.y>y)==(b.y>y)) continue;
            double x=(a.x+(b.x-a.x)*(y-a.y)/(b.y-a.y)+dx)*ds;
            int pos=n++; while (pos>0 && cross[pos-1]>x) { cross[pos]=cross[pos-1]; pos--; }
            cross[pos]=x;
        }
        for (int k=1;k<n;k+=2) for (int i=max(0,(int)cross[k-1]);i<min(art_w,(int)ceil(cross[k]));i++) {
            if ((i+.5)/ds>shore_edges[j]-3) continue;
            double a=unit(fmin(cross[k],i+1.)-fmax(cross[k-1],i));
            DWORD *p=art_bits+(size_t)j*art_w+i; *p=over(*p,ink,coverage(a*.70*strength));
        }
    }
    current(479+dx,107+dy,97,-13,1.9*strength,phase*.24,ink);
}
static void compose_shore(double fill,double phase) {
    if (!coast) return;
    double ds=dpi/96.,drift=(4+4*fill)*sin(phase*.75),tide=4.8*sin(phase);
    double swell[256];
    for (int k=0;k<256;k++) swell[k]=motion_enabled && !contrast ? .68*sin(k*6.2831853/256.-phase*1.8) : 0;
    DWORD *top=art_bits;
    GdiFlush();
    for (int j=0;j<art_h;j++) {
        double y=(j+.5)/ds,base=waterline(y,tide,drift);
        double crest=base+(WIDTH+28-base)*fill;
        shore_edges[j]=crest;
        double scallop=4.4+1.7*cos((y+tide)*.36)+1.2*cos((y+tide)*.73);
        double back=12+3.4*sin((y+tide*.7)/9),wet=-4.5-2.6*cos((y+tide)/16);
        const CoastPixel *row=coast+(size_t)j*art_w;
        DWORD *out=top+(size_t)j*art_w;
        for (int i=0;i<art_w;i++) {
            double x=(i+.5)/ds,d=crest-x;
            DWORD sand=row[i].sand,sea=row[i].sea,shoal=row[i].shoal;
            const CoastPixel *p=row+i;
            if (p->distance!=32767) {
                double u=unit(((1.48*fill)-(x-379.)/280.)/.48);
                u=u*u*(3-2*u);
                double radius=1.15+8.1*u,sd=p->distance/128.-u*swell[p->along],edge=radius-fabs(sd);
                double lip=unit(.5+(1.15-fabs(sd-radius-.75))*ds);
                unsigned inside=coverage(.5+edge*ds);
                DWORD groove=over(p->ink,p->charged,coverage(.74*u));
                DWORD bevel=contrast ? p->ink : 0xfff9ec;
                sand=over(over(sand,bevel,coverage(.68*lip)),groove,inside);
                sea=over(sea,over(groove,sea,contrast ? 0 : 55),inside);
                shoal=over(shoal,groove,inside);
            }
            if (d<wet-1) out[i]=sand;
            else if (d>back+1) out[i]=sea;
            else {
                DWORD c=over(sand,shoal,coverage(.5+(d-wet)*ds));
                c=over(c,sea,coverage(.5+(d-back)*ds));
                out[i]=over(c,row[i].foam,coverage(.85*fmin(.5+(d+1.4)*ds,.5+(scallop-d)*ds)));
            }
        }
    }
    if (!contrast) {
        static const double streams[][5]={{217,.11,53,8,.95},{285,.18,58,14,1.35},{449,.18,64,10,1.1},
            {229,.44,59,12,1.5},{431,.52,105,14,1.3},{279,.67,45,9,.9},{371,.72,74,9,1.2},
            {478,.81,68,11,.96},{337,.91,56,10,1.3}};
        double h=logical_height(); DWORD nacre=0xfffcf5;
        for (size_t k=0;k<sizeof(streams)/sizeof(streams[0]);k++) {
            const double *s=streams[k];
            current((WIDTH-s[0]*1.26-s[2])+sin(phase+k)+52*fill,h*s[1]+1.4*sin(phase+k*.7),s[2],s[3],s[4],phase*.5+k,nacre);
        }
        double crest=unit((fill-.12)/.40);
        if (crest>0) little_breaker(phase,crest,0xfffcf4);
        shore_sprite(top,173+324*fill+9*sin(phase*.5),h*.158+2*cos(phase*.5),8.2,FALSE,1);
        shore_sprite(top,153+324*fill+8*sin(phase*.5+.7),h*.204+1.7*cos(phase*.5+.7),6.4,FALSE,1);
        shore_sprite(top,531+5*sin(phase*.5+2),h*.88+1.8*cos(phase*.5+2),4.8,FALSE,1);
        shore_sprite(top,547+4*sin(phase*.5+1),h*.85+1.5*cos(phase*.5+1),5.2,FALSE,1);
        shore_sprite(top,664,52+.5*sin(phase),2.3,TRUE,1);
        shore_sprite(top,660,132+.5*cos(phase),1.85,TRUE,1);
        static const double suds[][3]={{.042,1.6,9},{.11,2.2,12},{.21,1.3,7},{.29,2.6,10},{.31,1.2,17},
            {.47,1.5,9},{.51,2.6,13},{.63,1.7,8},{.72,2.25,12},{.86,2.5,10},{.88,1.24,15},{.96,2.1,11}};
        for (size_t k=0;k<sizeof(suds)/sizeof(suds[0]);k++) {
            double y=suds[k][0]*h-tide+.6*sin(phase+k);
            double edge=shore_edges[min(art_h-1,max(0,(int)(y*ds)))];
            shore_sprite(top,edge-suds[k][2]-2*sin(phase+k),y,suds[k][1]*(1+.14*sin(phase+k)),TRUE,1);
        }
    }
    frame_fill=fill;
}
static BOOL prepare_coast(int w,int h,double fill,double phase) {
    if (art_dc && art_w==w && art_h==h) return TRUE;
    reset_art();
    if (w<=0 || h<=0) return FALSE;
    BITMAPINFO info={0};
    info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER); info.bmiHeader.biWidth=w; info.bmiHeader.biHeight=-h;
    info.bmiHeader.biPlanes=1; info.bmiHeader.biBitCount=32;
    DWORD *bits=NULL;
    art_dc=CreateCompatibleDC(NULL);
    art_bitmap=CreateDIBSection(art_dc,&info,DIB_RGB_COLORS,(void **)&bits,NULL,0);
    if (!art_dc || !art_bitmap) {
        if (art_bitmap) DeleteObject(art_bitmap);
        if (art_dc) DeleteDC(art_dc);
        art_dc=NULL; return FALSE;
    }
    art_previous=SelectObject(art_dc,art_bitmap); art_w=w; art_h=h;
    art_bits=bits; frame_fill=0;
    coast=HeapAlloc(GetProcessHeap(),0,(size_t)w*h*sizeof(*coast)+(size_t)h*sizeof(*shore_edges));
    shore_edges=coast ? (double *)(coast+(size_t)w*h) : NULL;
    double ds=dpi/96.;
    for (int j=0;j<h;j++) {
      double base=waterline((j+.5)/ds,0,0);
      for (int i=0;i<w;i++) {
        double x=(i+.5)/ds,y=(j+.5)/ds,u=x/WIDTH,v=(j+.5)/h;
        COLORREF sand=background,sea=background,shoal=background,foam=background;
        if (!contrast) {
            /* One sheet of water over sand. The copy side gets quieter ink, never
               a panel or a cut in the shoreline, even when options expand. */
            sand=mix(RGB(255,230,187),RGB(249,183,193),.12+.58*v+.21*u);
            sea=mix(RGB(151,230,211),RGB(168,196,243),
                unit(v*.81+.25*u+.07*sin(y/19+u*5)));
            shoal=mix(sand,sea,.51);
            sea=mix(sea,RGB(253,252,238),caustic(x,y));
            foam=RGB(255,254,248);
            sand=cockle(sand,x-333,y-49,13,.05,ds);
            sand=cockle(sand,x-430,y-logical_height()*.91,12,-.55,ds);
            sand=sea_star(sand,x-571,y-logical_height()*.88,ds);
            if (x>base+9 && x<base+24) {
                double sx=fmod(y+31,31)-15.5,sy=x-base-16+3*sin(y*.16);
                double grit=unit(1.05-hypot(sx,sy))* .39+unit(.86-hypot(sx-3,sy+2))*.3;
                sand=mix(sand,RGB(148,130,133),grit);
            }
        }
        bits[(size_t)j*w+i]=dib(x<base ? sea : sand);
        if (coast) {
            BYTE along=0; short distance=mark_distance(x,y,&along);
            COLORREF hue=prism((x-380)/325+(y-22)/510);
            COLORREF rim=RGB(255,255,249);
            double tail=unit((x-649)/10)*unit(.68+.55*sin(.62*y+.34*x));
            COLORREF ink=contrast ? text : mix(RGB(27,128,134),rim,tail);
            COLORREF charged=contrast ? text : mix(hue,RGB(221,248,236),.45);
            if (issue && !contrast) ink=charged=mix(background,ink,.42);
            coast[(size_t)j*w+i]=(CoastPixel){dib(sand),dib(sea),dib(shoal),dib(foam),
                dib(ink),dib(charged),distance,along};
        }
      }
    }
    compose_shore(fill,phase);
    return TRUE;
}
