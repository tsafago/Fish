/* Presentation only. All controls retain their native HWND, keyboard and accessibility roles. */
enum { FISH_X=294,FISH_Y=32,FISH_W=WIDTH-FISH_X,FISH_H=182,FRAME_TIMER=2 };
static HDC art_dc;
static HGDIOBJ art_previous;
static HBITMAP art_bitmap;
static DWORD *art_bits;
typedef struct { DWORD base,filled; } FishPixel;
static FishPixel *fish_pixels;
static int fish_w,fish_h;
static int art_w,art_h;
static double frame_fill;
typedef struct { double shown,goal; ULONGLONG last; } FillMotion;
static FillMotion motion;
static BOOL frame_timer,motion_enabled;
static HWND hot_control;
static BOOL compact_view(void) { return updating || busy || completed || fatal; }
static int logical_height(void) {
    return compact_view() ? (issue || completed ? 282 : 252) : HEIGHT+(expanded ? 92 : 0);
}
static double unit(double value) { return fmax(0,fmin(1,value)); }
static COLORREF mix(COLORREF a,COLORREF b,double amount) {
    double t=unit(amount);
    return RGB((int)(GetRValue(a)+(GetRValue(b)-GetRValue(a))*t+.5),
        (int)(GetGValue(a)+(GetGValue(b)-GetGValue(a))*t+.5),
        (int)(GetBValue(a)+(GetBValue(b)-GetBValue(a))*t+.5));
}
static void reset_art(void) {
    if (fish_pixels) { HeapFree(GetProcessHeap(),0,fish_pixels); fish_pixels=NULL; }
    if (art_dc) {
        SelectObject(art_dc,art_previous); DeleteObject(art_bitmap); DeleteDC(art_dc);
        art_dc=NULL; art_bits=NULL;
    }
}
static double segment_sq(double x,double y,double x0,double y0,double x1,double y1) {
    double dx=x1-x0,dy=y1-y0;
    double t=unit(((x-x0)*dx+(y-y0)*dy)/(dx*dx+dy*dy));
    x-=x0+t*dx; y-=y0+t*dy; return x*x+y*y;
}
static double mark(double x,double y,double ox,double oy,double scale,double pixel_scale) {
    x=(x-ox)/scale; y=(y-oy)/scale;
    if (x<0 || x>34 || y<1 || y>23) return 0;
    /* FishIcon.OUTLINE: two straight crossing arms, round joins and a 2.6-unit stroke. */
    static const double lines[][4]={{32,5.45,13,21},{13,21,2,12},{2,12,13,3},{13,3,32,18.55}};
    double d=1000;
    for (unsigned i=0;i<4;i++) d=fmin(d,segment_sq(x,y,lines[i][0],lines[i][1],lines[i][2],lines[i][3]));
    return unit(.5+(1.3-sqrt(d))*scale*pixel_scale);
}
static COLORREF prism(double t) {
    return t<.53 ? mix(RGB(31,205,225),RGB(93,135,255),t/.53)
        : mix(RGB(93,135,255),RGB(192,102,238),(t-.53)/.47);
}
/* Compose only the moving edge into the cached DIB. The window and the accessibility
   child both present one finished image, never an empty mark followed by a second blit. */
static void compose_fill(double fill) {
    if (!fish_pixels || fill==frame_fill) return;
    const int feather=max(1,px(6));
    double edge=fill*(fish_w+feather),old=frame_fill*(fish_w+feather);
    int from=max(0,(int)floor(fmin(old,edge))-feather),to=min(fish_w,(int)ceil(fmax(old,edge)));
    DWORD *top=art_bits+(size_t)px(FISH_Y)*art_w+px(FISH_X);
    GdiFlush();
    for (int x=from;x<to;x++) {
        double t=unit((edge-x-.5)/feather);
        for (int y=0;y<fish_h;y++) {
            FishPixel p=fish_pixels[(size_t)y*fish_w+x];
            top[(size_t)y*art_w+x]=
                p.base==p.filled || t<=0 ? p.base : t>=1 ? p.filled : mix(p.base,p.filled,t);
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
    int fx=px(FISH_X),fy=px(FISH_Y);
    fish_w=w-fx; fish_h=min(px(FISH_H),h-fy);
    if (compact_view() && !issue && fish_w>0 && fish_h>0)
        fish_pixels=HeapAlloc(GetProcessHeap(),0,(size_t)fish_w*fish_h*sizeof(*fish_pixels));
    double ds=dpi/96.,gradient_h=compact_view() ? 252. : HEIGHT;
    for (int j=0;j<h;j++) for (int i=0;i<w;i++) {
        double x=(i+.5)/ds,y=(j+.5)/ds,u=x/WIDTH,v=y/gradient_h;
        COLORREF c=background;
        COLORREF charge=c;
        if (!contrast) {
            double dx=(u-.75)*1.8,dy=(v+.06)*1.8;
            c=mix(c,light ? RGB(193,246,253) : RGB(53,58,66),unit(1-dx*dx-dy*dy)*unit(u*1.6));
            dx=(u-1.02)*1.65; dy=(v-.39)*1.85;
            c=mix(c,light ? RGB(211,210,255) : RGB(61,62,70),unit(1-dx*dx-dy*dy)*unit(u*1.2)*.86);
            dx=(u-.99)*2.1; dy=(v-.8)*2.12;
            c=mix(c,light ? RGB(255,227,209) : RGB(46,45,49),unit(1-dx*dx-dy*dy)*.94);
            double a=mark(x,y,FISH_X,20,8.35,ds);
            charge=c;
            if (a) {
                COLORREF hue=prism((x-310)/350+(y-35)/430);
                charge=mix(c,light ? hue : mix(hue,RGB(227,235,255),.58),a*.84);
                c=mix(c,light ? hue : RGB(173,178,190),a*(light ? .46 : .28));
            }
        }
        if (contrast && compact_view()) {
            double a=mark(x,y,FISH_X,20,8.35,ds);
            charge=mix(background,text,a); c=mix(background,text,a*.48);
        }
        bits[(size_t)j*w+i]=RGB(GetBValue(c),GetGValue(c),GetRValue(c));
        if (fish_pixels && i>=fx && j>=fy && j<fy+fish_h)
            fish_pixels[(size_t)(j-fy)*fish_w+i-fx]=(FishPixel){bits[(size_t)j*w+i],RGB(GetBValue(charge),GetGValue(charge),GetRValue(charge))};
    }
    compose_fill(motion.shown);
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
static void motion_step(FillMotion *m,ULONGLONG now,BOOL animate) {
    double gap=m->goal-m->shown;
    if (gap<=0) return;
    m->shown+=animate ? gap*(-expm1(-(double)(now-m->last)/150.)) : gap;
    if (m->goal-m->shown<.0005) m->shown=m->goal;
    m->last=now;
}
static void advance_frame(BOOL animate) {
    double old=motion.shown;
    motion_step(&motion,GetTickCount64(),animate);
    if (old!=motion.shown) {
        compose_fill(motion.shown);
        int pad=px(6),span=px(WIDTH)-px(FISH_X)+pad;
        RECT edge={max(0,(int)(old*span)-pad),0,(int)ceil(motion.shown*span),px(FISH_H)};
        InvalidateRect(progress_bar,&edge,FALSE);
    }
    if (motion.shown==motion.goal) stop_frames();
}
static void fill_toward(double goal) {
    if (goal<=motion.goal) return;
    motion.goal=goal;
    if (!frame_timer) {
        motion.last=GetTickCount64();
        if (motion_enabled && !IsIconic(window)) frame_timer=SetTimer(window,FRAME_TIMER,16,NULL)!=0;
    }
    if (!frame_timer) advance_frame(FALSE);
}
static void motion_preference(void) {
    BOOL enabled=FALSE;
    motion_enabled=SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION,0,&enabled,0) && enabled;
    if (!motion_enabled && frame_timer) advance_frame(FALSE);
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
            COLORREF fill=control==change ? panel : light ? RGB(248,246,255) : RGB(47,52,66);
            fill=contrast ? GetSysColor(COLOR_BTNFACE) : mix(fill,accent,pushed ? .24 : hot ? .14 : 0);
            ink=contrast ? GetSysColor(COLOR_BTNTEXT) : control==install ? (light ? RGB(52,47,85) : RGB(242,244,255)) : ink;
            pill(dc,r,6,fill,contrast ? text : control==install ? mix(accent,fill,.48) : divider);
            if (control==install && !issue && !completed) {
                tx.right-=px(16);
                int x=r.right-px(18);
                POINT shaft[2]={{x-px(5),cy},{x+px(5),cy}},head[3]={{x,cy-px(5)},{x+px(5),cy},{x,cy+px(5)}};
                stroke(dc,ink,shaft,2); stroke(dc,ink,head,3);
            }
        } else {
            align=DT_LEFT; ink=(hot||pushed) ? accent : muted;
            SIZE extent={0}; SelectObject(dc,body_font); GetTextExtentPoint32W(dc,name,(int)wcslen(name),&extent);
            int x=extent.cx+px(10); POINT chev[3]={{x,cy-px(4)},{x+px(4),cy},{x,cy+px(4)}};
            if (expanded) { chev[0]=(POINT){x-px(2),cy-px(3)}; chev[1]=(POINT){x+px(3),cy+px(2)};
                chev[2]=(POINT){x+px(8),cy-px(3)}; }
            stroke(dc,ink,chev,3);
        }
        SelectObject(dc,control==install ? medium_font : body_font);
        SetBkMode(dc,TRANSPARENT); SetTextColor(dc,ink);
        DrawTextW(dc,name,-1,&tx,align|DT_VCENTER|DT_SINGLELINE);
    }
    if (GetFocus()==control && !(SendMessageW(control,WM_QUERYUISTATE,0,0)&UISF_HIDEFOCUS)) {
        InflateRect(&r,-px(3),-px(3)); DrawFocusRect(dc,&r);
    }
}
static void paint_readout(HWND control,HDC dc) {
    RECT r; GetClientRect(control,&r); control_ground(control,dc);
    if (control==description) {
        WCHAR value[512]; GetWindowTextW(control,value,512);
        SelectObject(dc,body_font); SetBkMode(dc,TRANSPARENT); SetTextColor(dc,issue ? text : muted);
        DrawTextW(dc,value,-1,&r,DT_LEFT|DT_WORDBREAK|DT_NOPREFIX);
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
    body_font=face(12,FW_NORMAL); title_font=face(27,FW_MEDIUM); medium_font=face(13,FW_MEDIUM);
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
    BOOL config=!compact_view(),details=config && expanded;
    int h=logical_height();
    place(minimize,WIDTH-76,0,38,32,TRUE); place(cancel,WIDTH-38,0,38,32,TRUE);
    EnableWindow(cancel,!busy);
    place(options,26,210,150,28,config);
    place(folder,26,269,397,24,details); place(change,432,266,82,28,details);
    place(all_users,26,304,440,26,details);
    place(open_app,26,h-54,216,28,config);
    place(install,WIDTH-114,h-54,88,28,(config || issue || completed) && !reboot && !fatal);
    place(description,26,144,issue ? WIDTH-52 : 266,49,TRUE);
    /* The fish itself is the native progress element, not a second track below it. */
    place(progress_bar,FISH_X,FISH_Y,FISH_W,FISH_H,compact_view() && !issue && !completed);
    for (HWND child=GetWindow(window,GW_CHILD);child;child=GetWindow(child,GW_HWNDNEXT))
        SendMessageW(child,WM_SETFONT,(WPARAM)(child==install ? medium_font : body_font),TRUE);
    SendMessageW(folder,EM_SETMARGINS,EC_LEFTMARGIN|EC_RIGHTMARGIN,MAKELPARAM(px(8),px(6)));
    SetWindowTextW(description,compact_view() || issue ? status :
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
    RedrawWindow(window,NULL,NULL,RDW_INVALIDATE|RDW_ALLCHILDREN);
}
static void surface(HDC dc,int w,int h,BOOL children) {
    ground(dc,w,h,(POINT){0,0});
    label(dc,L"Fish",26,8,90,24,text,medium_font,DT_LEFT|DT_SINGLELINE);
    label(dc,completed ? L"Fish installed" : issue && updating ? L"Update paused" : fatal ? L"Setup paused" :
        updating ? L"Updating Fish" : busy ? L"Installing Fish" : L"Install Fish",
        24,65,295,44,text,title_font,DT_LEFT|DT_SINGLELINE);
    label(dc,FISH_VERSION,26,110,220,24,muted,medium_font,DT_LEFT|DT_SINGLELINE);
    if (expanded && !compact_view()) label(dc,L"Install folder",26,247,210,21,muted,body_font,DT_LEFT|DT_SINGLELINE);
    if (children) {
        for (HWND c=GetWindow(window,GW_CHILD);c;c=GetWindow(c,GW_HWNDNEXT)) {
            if (!(GetWindowLongPtrW(c,GWL_STYLE)&WS_VISIBLE)) continue;
            POINT p={0,0}; MapWindowPoints(c,window,&p,1);
            int save=SaveDC(dc); SetViewportOrgEx(dc,p.x,p.y,NULL);
            RECT child; GetClientRect(c,&child); IntersectClipRect(dc,0,0,child.right,child.bottom);
            SendMessageW(c,WM_PRINTCLIENT,(WPARAM)dc,PRF_CLIENT|PRF_ERASEBKGND);
            RestoreDC(dc,save);
        }
    }
}
