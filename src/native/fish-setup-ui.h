/* Presentation only. All controls retain their native HWND, keyboard and accessibility roles. */
enum { FISH_X=366,FISH_Y=8,FISH_W=305,FISH_H=191,FRAME_TIMER=2 };
static HDC art_dc;
static HGDIOBJ art_previous;
static HBITMAP art_bitmap;
static DWORD *art_bits;
/* Cache the sand impression; water and the moving lip are composited over it. */
typedef struct { DWORD sand,sea,shoal,foam,ink,charged; short distance; BYTE along; } CoastPixel;
static CoastPixel *coast;
static double *shore_edges;
static int art_w,art_h;
static double frame_fill,shore_phase;
static ULONGLONG frame_time;
typedef struct { double shown,goal; ULONGLONG last; } FillMotion;
static FillMotion motion;
static BOOL frame_timer,motion_enabled;
static BOOL finishing;
static ULONGLONG finish_start,submerged_at;
static double finish_from;
static HWND hot_control;
static BOOL compact_view(void) { return updating || busy || completed || fatal; }
static int logical_height(void) {
    return HEIGHT+(!compact_view() && expanded ? 92 : 0);
}
static double unit(double value) { return fmax(0,fmin(1,value)); }
static COLORREF mix(COLORREF a,COLORREF b,double amount) {
    double t=unit(amount);
    return RGB((int)(GetRValue(a)+(GetRValue(b)-GetRValue(a))*t+.5),
        (int)(GetGValue(a)+(GetGValue(b)-GetGValue(a))*t+.5),
        (int)(GetBValue(a)+(GetBValue(b)-GetBValue(a))*t+.5));
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
    COLORREF line=RGB(107,116,174);
    COLORREF nacre=RGB(230,226,240);
    double ribs=unit(.5+ds*(.42-fabs(sin(angle*7.5))*r*size/7.5))*unit((r-.16)*6);
    COLORREF fill=mix(nacre,line,fmax(unit(1-edge*ds),ribs*.80));
    return mix(c,fill,unit(.5+edge*ds));
}
static COLORREF sea_star(COLORREF c,double x,double y,double ds) {
    if (fabs(x)>13 || fabs(y)>13) return c;
    double a=atan2(y,x)+.24,r=hypot(x,y);
    double arm=.5+.5*cos(5*a),edge=4.2+7.7*arm*arm*arm-r;
    COLORREF line=RGB(185,112,106);
    double freckles=unit((cos(8*r)*cos(a*5)-.55)*3)*unit(1-r/9);
    COLORREF ink=mix(RGB(239,197,186),line,fmax(unit(1.3-edge*ds),freckles));
    return mix(c,ink,unit(.5+ds*edge));
}
/* The tide enters from the copy side and travels across the sand. */
static double waterline(double y,double tide,double drift) {
    static const double knots[]={201,229,237,254,258,246,231,234,253,283,295,298,320,336,339,353,385};
    double v=unit((y+tide)/logical_height())*16;
    int i=min(15,(int)v); double t=v-i,p=knots[max(0,i-1)],a=knots[i],b=knots[i+1],q=knots[min(16,i+2)];
    return a+.5*t*((b-p)+t*((2*p-5*a+4*b-q)+t*(3*a-3*b+q-p)))+drift;
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
static BOOL ensure_art(int w,int h) {
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
            sand=mix(RGB(254,248,245),RGB(241,219,214),.12+.58*v+.21*u);
            sea=mix(RGB(191,216,213),RGB(204,207,222),
                unit(v*.81+.25*u+.07*sin(y/19+u*5)));
            shoal=mix(sand,sea,.51);
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
            COLORREF charged=contrast ? text : mix(hue,RGB(225,242,237),.60);
            if (issue && !contrast) ink=charged=mix(background,ink,.42);
            coast[(size_t)j*w+i]=(CoastPixel){dib(sand),dib(sea),dib(shoal),dib(foam),
                dib(ink),dib(charged),distance,along};
        }
      }
    }
    compose_shore(motion.shown,shore_phase);
    return TRUE;
}
/* Parent and child paints sample exactly the same frame at window-relative coordinates.
   In particular, WM_SETTEXT may draw a STATIC outside the parent's WM_PAINT. */
static void ground(HDC dc,int w,int h,POINT origin) {
    if (!ensure_art(px(WIDTH),px(logical_height()))) {
        RECT r={0,0,w,h}; SetDCBrushColor(dc,background); FillRect(dc,&r,GetStockObject(DC_BRUSH)); return;
    }
    BitBlt(dc,0,0,w,h,art_dc,origin.x,origin.y,SRCCOPY);
}
static void control_ground(HWND control,HDC dc) {
    RECT rect; GetClientRect(control,&rect); POINT p={0,0}; MapWindowPoints(control,window,&p,1);
    ground(dc,rect.right,rect.bottom,p);
}
static void stop_frames(void) {
    if (frame_timer) { KillTimer(window,FRAME_TIMER); frame_timer=FALSE; }
}
static void sync_frames(void) {
    if (!window || !motion_enabled || contrast || issue || (completed && !finishing) || !IsWindowVisible(window) || IsIconic(window)) {
        stop_frames(); return;
    }
    if (!frame_timer) {
        frame_time=GetTickCount64();
        frame_timer=SetTimer(window,FRAME_TIMER,32,NULL)!=0;
    }
}
static void motion_step(FillMotion *m,ULONGLONG now,BOOL animate) {
    double gap=m->goal-m->shown;
    if (gap<=0) return;
    m->shown+=animate ? gap*(-expm1(-(double)(now-m->last)/440.)) : gap;
    if (m->goal-m->shown<.0005) m->shown=m->goal;
    m->last=now;
}
static void advance_frame(BOOL animate) {
    ULONGLONG now=GetTickCount64();
    if (finishing) {
        double t=animate ? unit((double)(now-finish_start)/660.) : 1;
        motion.shown=finish_from+(1-finish_from)*(t*t*(3-2*t));
        if (t==1) motion.shown=1;
    } else motion_step(&motion,now,animate);
    if (animate) {
        /* Nine seconds per swell. A stalled or restored window doesn't fast-forward the sea. */
        shore_phase+=fmin(80.,(double)(now-frame_time))*.00070;
        frame_time=now;
    }
    compose_shore(motion.shown,shore_phase);
    /* Each tide tick presents one completed composition including the native
       controls. Don't schedule a second background/label paint for every child. */
    RedrawWindow(window,NULL,NULL,RDW_INVALIDATE|RDW_NOCHILDREN);
    if (finishing && motion.shown==1 && !submerged_at) {
        RedrawWindow(window,NULL,NULL,RDW_UPDATENOW|RDW_NOCHILDREN);
        submerged_at=GetTickCount64();
    }
}
static void fill_toward(double goal) {
    if (goal<=motion.goal) return;
    if (motion.shown==motion.goal) motion.last=GetTickCount64();
    motion.goal=goal;
    sync_frames();
    if (!frame_timer) advance_frame(FALSE);
}
static void apply_motion_preference(BOOL enabled) {
    motion_enabled=enabled;
    if (!motion_enabled || contrast) {
        shore_phase=0;
        advance_frame(FALSE);
    }
    sync_frames();
}
static void motion_preference(void) {
    BOOL enabled=FALSE;
    apply_motion_preference(SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION,0,&enabled,0) && enabled);
}
static void set_progress(int value) {
    BOOL was_measured=progress_value>=0;
    progress_value=value;
    LONG_PTR style=GetWindowLongPtrW(progress_bar,GWL_STYLE);
    if ((value<0)!=((style&PBS_MARQUEE)!=0))
        SetWindowLongPtrW(progress_bar,GWL_STYLE,value<0 ? style|PBS_MARQUEE : style&~(LONG_PTR)PBS_MARQUEE);
    if (value>=0) SendMessageW(progress_bar,PBM_SETPOS,(WPARAM)value,0);
    else if (!(style&PBS_MARQUEE)) SendMessageW(progress_bar,PBM_SETPOS,0,0);
    /* Some MSAA clients still read PBM_GETPOS while marquee is set. Don't let
       the last byte count stand in for unknown MSI or verification progress. */
    if (accessible_properties) {
        if (value<0) IAccPropServices_SetHwndPropStr(accessible_properties,progress_bar,OBJID_CLIENT,CHILDID_SELF,
            PROPID_ACC_VALUE,L"In progress");
        else if (!was_measured) IAccPropServices_ClearHwndProps(accessible_properties,progress_bar,OBJID_CLIENT,CHILDID_SELF,
            &PROPID_ACC_VALUE,1);
    }
}
static void pill(HDC dc,RECT r,int radius,COLORREF fill,COLORREF border) {
    HGDIOBJ p=SelectObject(dc,GetStockObject(DC_PEN)),b=SelectObject(dc,GetStockObject(DC_BRUSH));
    SetDCPenColor(dc,border); SetDCBrushColor(dc,fill);
    RoundRect(dc,r.left,r.top,r.right,r.bottom,px(radius*2),px(radius*2));
    SelectObject(dc,b); SelectObject(dc,p);
}
static void stroke(HDC dc,COLORREF ink,const POINT *points,unsigned n) {
    HPEN pen=CreatePen(PS_SOLID,max(1,px(2)),ink);
    HGDIOBJ old=SelectObject(dc,pen); Polyline(dc,points,(int)n); SelectObject(dc,old); DeleteObject(pen);
}
static void paint_button(HWND control,HDC dc) {
    RECT r; GetClientRect(control,&r); control_ground(control,dc);
    BOOL hot=control==hot_control,enabled=IsWindowEnabled(control);
    BOOL pushed=!!(SendMessageW(control,BM_GETSTATE,0,0)&BST_PUSHED);
    COLORREF ink=enabled ? text : muted;
    WCHAR name[96]; GetWindowTextW(control,name,96);
    int cx=r.right/2,cy=r.bottom/2;
    if (control==minimize || control==cancel) {
        if (hot && enabled) {
            COLORREF fill=control==cancel ? RGB(176,41,57) : mix(panel,muted,.22);
            pill(dc,r,0,fill,fill); if (control==cancel) ink=RGB(255,255,255);
        }
        POINT pts[2]={{cx-px(5),cy},{cx+px(5),cy}};
        if (control==cancel) { pts[0].y-=px(5); pts[1].y+=px(5); }
        stroke(dc,ink,pts,2);
        if (control==cancel) { pts[0].y=cy+px(5); pts[1].y=cy-px(5); stroke(dc,ink,pts,2); }
    } else {
        RECT tx=r; UINT align=DT_CENTER;
        if (control==install || control==change) {
            COLORREF fill=control==change ? panel : RGB(248,246,255);
            fill=contrast ? panel : mix(fill,accent,pushed ? .24 : hot ? .14 : 0);
            ink=contrast ? text : control==install ? RGB(52,47,85) : ink;
            pill(dc,r,6,fill,contrast ? text : control==install ? mix(accent,fill,.48) : divider);
            if (control==install && !busy && !issue && !completed) {
                tx.right-=px(16);
                int x=r.right-px(18);
                POINT shaft[2]={{x-px(5),cy},{x+px(5),cy}},head[3]={{x,cy-px(5)},{x+px(5),cy},{x,cy+px(5)}};
                stroke(dc,ink,shaft,2); stroke(dc,ink,head,3);
            }
        } else {
            align=DT_LEFT; ink=enabled && (hot||pushed) ? accent : enabled ? text : muted;
            SIZE extent={0}; SelectObject(dc,body_font); GetTextExtentPoint32W(dc,name,(int)wcslen(name),&extent);
            int x=extent.cx+px(10); POINT chev[3]={{x,cy-px(4)},{x+px(4),cy},{x,cy+px(4)}};
            if (expanded) { chev[0]=(POINT){x-px(2),cy-px(3)}; chev[1]=(POINT){x+px(3),cy+px(2)};
                chev[2]=(POINT){x+px(8),cy-px(3)}; }
            stroke(dc,ink,chev,3);
        }
        SelectObject(dc,control==install ? medium_font : body_font);
        coast_text(dc,name,tx,align|DT_VCENTER|DT_SINGLELINE,ink);
    }
    if (GetFocus()==control && !(SendMessageW(control,WM_QUERYUISTATE,0,0)&UISF_HIDEFOCUS)) {
        InflateRect(&r,-px(3),-px(3)); DrawFocusRect(dc,&r);
    }
}
static void paint_readout(HWND control,HDC dc) {
    RECT r; GetClientRect(control,&r); control_ground(control,dc);
    if (control==description) {
        WCHAR value[512]; GetWindowTextW(control,value,512);
        SelectObject(dc,body_font); coast_text(dc,value,r,DT_LEFT|DT_WORDBREAK|DT_NOPREFIX,text);
    }
}
static LRESULT CALLBACK skin_proc(HWND h,UINT m,WPARAM wp,LPARAM lp,UINT_PTR id,DWORD_PTR extra) {
    (void)extra;
    if (m==WM_ERASEBKGND) return 1;
    if (m==WM_PAINT || m==WM_PRINTCLIENT) {
        PAINTSTRUCT ps; HDC dc=m==WM_PAINT ? BeginPaint(h,&ps) : (HDC)wp;
        if (id==2) paint_button(h,dc); else paint_readout(h,dc);
        if (m==WM_PAINT) EndPaint(h,&ps);
        return 0;
    }
    if (id==2 && m==WM_MOUSEMOVE && hot_control!=h) {
        TRACKMOUSEEVENT track={sizeof(track),TME_LEAVE,h,0}; TrackMouseEvent(&track);
        hot_control=h; InvalidateRect(h,NULL,FALSE);
    }
    if (m==WM_MOUSELEAVE && hot_control==h) { hot_control=NULL; InvalidateRect(h,NULL,FALSE); }
    if (m==WM_NCDESTROY) RemoveWindowSubclass(h,skin_proc,id);
    return DefSubclassProc(h,m,wp,lp);
}
static HFONT face(int size,int weight) {
    return CreateFontW(-px(size),0,0,0,weight,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
}
static void fonts(void) {
    DeleteObject(body_font); DeleteObject(title_font); DeleteObject(medium_font);
    body_font=face(13,FW_NORMAL); title_font=face(27,FW_SEMIBOLD); medium_font=face(13,FW_SEMIBOLD);
}
static UINT layout_dpi(const RECT *work,UINT display_dpi) {
    UINT fit_x=(work->right-work->left)*96U/WIDTH;
    UINT fit_y=(work->bottom-work->top)*96U/(HEIGHT+92);
    return max(1U,min(display_dpi,min(fit_x,fit_y)));
}
static void place(HWND h,int x,int y,int w,int height,BOOL visible) {
    SetWindowPos(h,NULL,px(x),px(y),px(w),px(height),
        SWP_NOZORDER|SWP_NOACTIVATE|(visible ? SWP_SHOWWINDOW : SWP_HIDEWINDOW));
}
static void position(void) {
    BOOL config=!updating && !issue && !completed && !fatal,details=config && expanded && !busy;
    int h=logical_height();
    place(minimize,WIDTH-68,0,32,33,TRUE); place(cancel,WIDTH-36,0,31,33,TRUE);
    EnableWindow(cancel,!busy);
    place(options,26,131,150,28,config);
    place(folder,26,183,397,24,details); place(change,432,181,82,28,details);
    place(all_users,26,219,440,26,details);
    place(open_app,26,h-46,216,28,config);
    place(install,WIDTH-105,h-42,94,28,(config || issue || completed) && !finishing && !reboot && !fatal);
    EnableWindow(open_app,!busy); EnableWindow(options,!busy); EnableWindow(install,!busy);
    if (busy && config) SetWindowTextW(install,L"Installing\u2026");
    place(description,26,100,issue ? WIDTH-52 : 285,issue ? 60 : 27,TRUE);
    /* The fish itself is the native progress element, not a second track below it. */
    place(progress_bar,FISH_X,FISH_Y,FISH_W,FISH_H,compact_view() && !issue && (!completed || finishing));
    SetWindowPos(progress_bar,HWND_BOTTOM,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE);
    for (HWND child=GetWindow(window,GW_CHILD);child;child=GetWindow(child,GW_HWNDNEXT))
        SendMessageW(child,WM_SETFONT,(WPARAM)(child==install ? medium_font : body_font),TRUE);
    SendMessageW(folder,EM_SETMARGINS,EC_LEFTMARGIN|EC_RIGHTMARGIN,MAKELPARAM(px(8),px(6)));
    SetWindowTextW(description,!config ? status :
        system_wide_mode ? L"For everyone on this PC" : L"For your Windows account");
}
static void layout(void) {
    if (!window) return;
    RECT r; GetWindowRect(window,&r);
    int w=px(WIDTH),h=px(logical_height());
    if (r.right-r.left!=w || r.bottom-r.top!=h) {
        MONITORINFO mi={.cbSize=sizeof(mi)};
        GetMonitorInfoW(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&mi);
        SetWindowPos(window,NULL,max(mi.rcWork.left,min(r.left,mi.rcWork.right-w)),
            max(mi.rcWork.top,min(r.top,mi.rcWork.bottom-h)),w,h,SWP_NOZORDER|SWP_NOACTIVATE);
    }
    position();
    sync_frames();
    RedrawWindow(window,NULL,NULL,RDW_INVALIDATE|RDW_ALLCHILDREN);
}
static void surface(HDC dc,int w,int h,BOOL children) {
    ground(dc,w,h,(POINT){0,0});
    label(dc,L"Fish",23,9,90,18,text,medium_font,DT_LEFT|DT_SINGLELINE);
    label(dc,completed ? (updating ? L"Fish updated" : L"Fish installed") : issue && updating ? L"Update paused" : fatal ? L"Setup paused" :
        updating ? L"Updating Fish" : L"Install Fish",
        24,42,317,34,text,title_font,DT_LEFT|DT_SINGLELINE);
    label(dc,FISH_VERSION,26,79,220,20,text,body_font,DT_LEFT|DT_SINGLELINE);
    if (expanded && !compact_view()) label(dc,L"Install folder",26,162,210,21,text,body_font,DT_LEFT|DT_SINGLELINE);
    if (children) {
        HWND child=GetWindow(window,GW_CHILD);
        for (HWND c=child ? GetWindow(child,GW_HWNDLAST) : NULL;c;c=GetWindow(c,GW_HWNDPREV)) {
            if (!(GetWindowLongPtrW(c,GWL_STYLE)&WS_VISIBLE)) continue;
            POINT p={0,0}; MapWindowPoints(c,window,&p,1);
            int save=SaveDC(dc); SetViewportOrgEx(dc,p.x,p.y,NULL);
            RECT child; GetClientRect(c,&child); IntersectClipRect(dc,0,0,child.right,child.bottom);
            SendMessageW(c,WM_PRINTCLIENT,(WPARAM)dc,PRF_CLIENT|PRF_ERASEBKGND);
            RestoreDC(dc,save);
        }
    }
}
