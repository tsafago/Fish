/* Presentation only. All controls retain their native HWND, keyboard and accessibility roles. */
enum { FRAME_TIMER=2 };
static ULONGLONG frame_time;
static double shore_phase;
typedef struct { double shown,goal; ULONGLONG last; } FillMotion;
static FillMotion motion;
static BOOL frame_timer,motion_enabled;
static BOOL finishing;
static ULONGLONG finish_start,submerged_at;
static double finish_from;
static HWND hot_control;
static BOOL compact_view(void) { return updating || busy || completed || fatal; }
static BOOL location_controls(void) {
    return !compact_view() && (!issue || (!registered_installation && !*quarantine_path));
}
static int logical_height(void) {
    return HEIGHT+(location_controls() && expanded ? 92 : 0);
}
#include "fish-coast.h"
#include "fish-start-size.h"
_Static_assert(WIDTH==FISH_START_WIDTH && HEIGHT==FISH_START_HEIGHT,"Opening art must use the setup canvas");
static BOOL ensure_art(int w,int h) { return prepare_coast(w,h,motion.shown,shore_phase); }
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
    BOOL config=!updating && !issue && !completed && !fatal;
    BOOL editable=location_controls(),details=editable && expanded;
    int h=logical_height();
    place(minimize,WIDTH-68,0,32,33,TRUE); place(cancel,WIDTH-36,0,31,33,TRUE);
    EnableWindow(cancel,!busy);
    place(options,26,issue ? h-42 : 131,150,28,config || editable);
    place(folder,26,183,397,24,details); place(change,432,181,82,28,details);
    place(all_users,26,219,440,26,details);
    place(open_app,26,h-46,216,28,config);
    BOOL action=(config || can_retry || offer_quarantine || completed) && !finishing && !reboot && !fatal;
    place(install,WIDTH-105,h-42,94,28,action);
    place(details_button,WIDTH-(action ? 192 : 105),h-42,80,28,issue && !busy);
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
    if (expanded && location_controls()) label(dc,L"Install folder",26,162,210,21,text,body_font,DT_LEFT|DT_SINGLELINE);
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
