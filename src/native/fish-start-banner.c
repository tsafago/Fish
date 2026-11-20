#define UNICODE
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>
#include <dwmapi.h>
#include <jni.h>
#include "fish-start-dots.h"
#include "fish-window-fade.h"

extern IMAGE_DOS_HEADER __ImageBase;
#define MODULE ((HMODULE)&__ImageBase)
static const WCHAR banner_class[]=L"Fish.StartBanner";
static const WCHAR starting[]=L"Starting...";
typedef struct {
    HANDLE closed;
    SRWLOCK presentation;
    LONG references;
} Banner;

static void release(Banner *b) {
    if (!InterlockedDecrement(&b->references)) {
        CloseHandle(b->closed);
        HeapFree(GetProcessHeap(),0,b);
    }
}

static const BITMAPINFO *start_art(void) {
    HRSRC res=FindResourceW(MODULE,MAKEINTRESOURCEW(101),RT_BITMAP);
    return res ? (const BITMAPINFO *)LockResource(LoadResource(MODULE,res)) : NULL;
}

static void preferences(HWND hwnd,CoastBanner *coast) {
    HIGHCONTRASTW hc={.cbSize=sizeof(hc)};
    BOOL animate=FALSE;
    coast->plain=SystemParametersInfoW(SPI_GETHIGHCONTRAST,sizeof(hc),&hc,0) && (hc.dwFlags&HCF_HIGHCONTRASTON);
    coast->moving=!coast->plain && coast->frame && SystemParametersInfoW(SPI_GETCLIENTAREAANIMATION,0,&animate,0) && animate;
    if (coast->moving) coast->moving=SetTimer(hwnd,1,48,NULL)!=0;
    else KillTimer(hwnd,1);
    InvalidateRect(hwnd,NULL,FALSE);
}

static LRESULT CALLBACK banner_proc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp) {
    CoastBanner *coast=(CoastBanner *)GetWindowLongPtrW(hwnd,GWLP_USERDATA);
    switch (msg) {
    case WM_NCCREATE:
        SetWindowLongPtrW(hwnd,GWLP_USERDATA,(LONG_PTR)((CREATESTRUCTW *)lp)->lpCreateParams);
        break;
    case WM_CREATE: preferences(hwnd,coast); return 0;
    case WM_TIMER:
        if (!coast->moving) return 0;
        InvalidateRect(hwnd,NULL,FALSE);
        return 0;
    case WM_MOUSEACTIVATE: return MA_NOACTIVATEANDEAT;
    case WM_ERASEBKGND: return 1;
    case WM_PRINTCLIENT:
    case WM_PAINT: {
        PAINTSTRUCT ps;
        RECT r; GetClientRect(hwnd,&r);
        HDC dc=msg==WM_PAINT ? BeginPaint(hwnd,&ps) : (HDC)wp;
        paint_frame(dc,&r,coast,GetTickCount64()-coast->began);
        if (msg==WM_PAINT) EndPaint(hwnd,&ps);
        return 0;
    }
    case WM_SETTINGCHANGE: if (coast) preferences(hwnd,coast); return 0;
    }
    return DefWindowProcW(hwnd,msg,wp,lp);
}

static DWORD WINAPI present(void *param) {
    Banner *b=(Banner *)param;
    HWND hwnd=NULL;
    CoastBanner coast={.began=GetTickCount64()};
    SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    if (WaitForSingleObject(b->closed,0)!=WAIT_TIMEOUT) goto done;
    WNDCLASSW cls={.lpfnWndProc=banner_proc,.hInstance=MODULE,.lpszClassName=banner_class,
        .hCursor=LoadCursorW(NULL,IDC_ARROW)};
    if (!RegisterClassW(&cls) && GetLastError()!=ERROR_CLASS_ALREADY_EXISTS) goto done;
    POINT p={0}; GetCursorPos(&p);
    MONITORINFO m={.cbSize=sizeof(m)};
    if (!GetMonitorInfoW(MonitorFromPoint(p,MONITOR_DEFAULTTONEAREST),&m)) goto done;
    int mw=m.rcWork.right-m.rcWork.left,mh=m.rcWork.bottom-m.rcWork.top;
    coast.art=start_art();
    if (coast.art) {
        size_t bytes=(size_t)coast.art->bmiHeader.biWidth*abs(coast.art->bmiHeader.biHeight)*4;
        coast.frame=HeapAlloc(GetProcessHeap(),0,bytes);
        if (coast.frame) memcpy(coast.frame,(const BYTE *)coast.art+coast.art->bmiHeader.biSize,bytes);
    }
    hwnd=CreateWindowExW(WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE,banner_class,starting,WS_POPUP,
        m.rcWork.left,m.rcWork.top,1,1,NULL,NULL,MODULE,&coast);
    if (!hwnd) goto done;
    int dpi=(int)GetDpiForWindow(hwnd);
    dpi=min(dpi,min(mw*96/FISH_START_WIDTH,mh*96/FISH_START_HEIGHT));
    int w=MulDiv(FISH_START_WIDTH,dpi,96),h=MulDiv(FISH_START_HEIGHT,dpi,96);
    int curve=max(2,MulDiv(18,dpi,96));
    HRGN outline=CreateRoundRectRgn(0,0,w+1,h+1,curve,curve);
    if (outline && !SetWindowRgn(hwnd,outline,FALSE)) DeleteObject(outline);
    /* A ready/failed startup may beat creation of the HWND. Once dismissed,
       even a worker that hasn't run yet must never put a late banner on screen. */
    AcquireSRWLockExclusive(&b->presentation);
    if (WaitForSingleObject(b->closed,0)==WAIT_TIMEOUT)
        SetWindowPos(hwnd,HWND_TOP,m.rcWork.left+(mw-w)/2,m.rcWork.top+(mh-h)/2,w,h,
            SWP_NOACTIVATE|SWP_SHOWWINDOW);
    ReleaseSRWLockExclusive(&b->presentation);
    while (MsgWaitForMultipleObjectsEx(1,&b->closed,INFINITE,QS_ALLINPUT,MWMO_INPUTAVAILABLE)==WAIT_OBJECT_0+1) {
        MSG msg;
        while (PeekMessageW(&msg,NULL,0,0,PM_REMOVE)) {
            if (msg.message==WM_QUIT) goto done;
            DispatchMessageW(&msg);
        }
    }
    ULONGLONG fade=0;
    KillTimer(hwnd,1);
    if (begin_exit_fade(hwnd,coast.moving,&fade)) {
        MSG msg;
        while (GetMessageW(&msg,NULL,0,0)>0) {
            if (msg.hwnd==hwnd && msg.message==WM_TIMER && msg.wParam==EXIT_FADE_TIMER) {
                if (!coast.moving || exit_fade_finished(hwnd,fade)) break;
            } else DispatchMessageW(&msg);
        }
    }
done:
    if (hwnd) DestroyWindow(hwnd);
    if (coast.frame) HeapFree(GetProcessHeap(),0,coast.frame);
    release(b);
    return 0;
}

JNIEXPORT jlong JNICALL Java_fish_ui_StartupBanner_open0(JNIEnv *env,jclass type) {
    (void)env; (void)type;
    Banner *b=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,sizeof(*b));
    if (!b) return 0;
    b->closed=CreateEventW(NULL,TRUE,FALSE,NULL);
    if (!b->closed) { HeapFree(GetProcessHeap(),0,b); return 0; }
    b->references=2;
    HANDLE worker=CreateThread(NULL,0,present,b,0,NULL);
    if (!worker) { release(b); release(b); return 0; }
    CloseHandle(worker);
    return (jlong)(intptr_t)b;
}

JNIEXPORT void JNICALL Java_fish_ui_StartupBanner_close0(JNIEnv *env,jclass type,jlong handle) {
    (void)env; (void)type;
    Banner *b=(Banner *)(intptr_t)handle;
    AcquireSRWLockExclusive(&b->presentation);
    SetEvent(b->closed);
    ReleaseSRWLockExclusive(&b->presentation);
    release(b);
}
