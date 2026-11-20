/* Observe only fixture windows on a fresh non-input desktop. Never switch to it. */
#ifndef UNICODE
#define UNICODE 1
#endif
#include <windows.h>
#include <stdio.h>
#include <stdlib.h>
#include "fish-start-dots.h"

static HDESK desktop;
static WCHAR name[64],command[32768];
static HANDLE job;
static const WCHAR *evidence;
static void check(BOOL condition,const char *reason) {
    if (!condition) { fprintf(stderr,"Start banner: %s (Win32 %lu)\n",reason,GetLastError()); exit(1); }
}
static HANDLE launch(const WCHAR *args,const WCHAR *directory) {
    WCHAR line[32768];
    check(swprintf(line,ARRAYSIZE(line),L"%ls %ls%ls%ls%ls",command,args,
        directory ? L" --data-dir \"" : L"",directory ? directory : L"",directory ? L"\"" : L"")>0,"command too long");
    STARTUPINFOW si={.cb=sizeof(si),.lpDesktop=name};
    PROCESS_INFORMATION pi;
    check(CreateProcessW(NULL,line,NULL,NULL,FALSE,CREATE_SUSPENDED|CREATE_NO_WINDOW,NULL,NULL,&si,&pi),"launch");
    if (!AssignProcessToJobObject(job,pi.hProcess)) {
        TerminateProcess(pi.hProcess,1); check(FALSE,"isolate process");
    }
    ResumeThread(pi.hThread); CloseHandle(pi.hThread);
    return pi.hProcess;
}
typedef struct { HWND banner; int workspaces,errors; } Windows;
static BOOL CALLBACK observe(HWND hwnd,LPARAM data) {
    if (!IsWindowVisible(hwnd)) return TRUE;
    Windows *seen=(Windows *)data;
    WCHAR title[160],cls[80];
    GetWindowTextW(hwnd,title,ARRAYSIZE(title)); GetClassNameW(hwnd,cls,ARRAYSIZE(cls));
    if (!wcscmp(cls,L"Fish.StartBanner")) {
        check(!wcscmp(title,L"Starting..."),"missing accessible starting name");
        LONG_PTR ex=GetWindowLongPtrW(hwnd,GWL_EXSTYLE);
        check((ex&WS_EX_NOACTIVATE) && (ex&WS_EX_TOOLWINDOW) && !(ex&WS_EX_TOPMOST),"activating/taskbar/topmost banner");
        GUITHREADINFO gui={.cbSize=sizeof(gui)};
        if (GetGUIThreadInfo(GetWindowThreadProcessId(hwnd,NULL),&gui))
            check(gui.hwndActive!=hwnd && gui.hwndFocus!=hwnd,"banner took focus");
        check(!GetWindow(hwnd,GW_CHILD),"banner has controls");
        seen->banner=hwnd;
    }
    if (!wcscmp(title,L"Fish - local synthetic host")) seen->workspaces++;
    if (!wcscmp(title,L"Workspace could not be opened")) seen->errors++;
    return TRUE;
}
static Windows sample(void) {
    Windows seen={0}; EnumDesktopWindows(desktop,observe,(LPARAM)&seen); return seen;
}
static BOOL CALLBACK report(HWND h,LPARAM ignored) {
    (void)ignored;
    if (IsWindowVisible(h)) { WCHAR title[160]; GetWindowTextW(h,title,ARRAYSIZE(title)); fwprintf(stderr,L"  visible: %ls\n",title); }
    return TRUE;
}
/* Use a synthetic even ground to isolate the moving pixels, without touching
   Windows animation or contrast settings or holding the real splash open. */
static void check_pulse(void) {
    enum { W=680,H=216,N=W*H };
    BITMAPINFO *art=calloc(1,sizeof(BITMAPINFOHEADER)+N*4);
    DWORD *frame=malloc(N*4),*first=malloc(N*4),*pixels=NULL;
    check(art && frame && first,"offscreen frame");
    art->bmiHeader=(BITMAPINFOHEADER){.biSize=sizeof(BITMAPINFOHEADER),.biWidth=W,.biHeight=-H,.biPlanes=1,.biBitCount=32};
    DWORD *base=(DWORD *)((BYTE *)art+sizeof(BITMAPINFOHEADER));
    for (int i=0;i<N;i++) frame[i]=base[i]=0x9fe0d4;
    HDC dc=CreateCompatibleDC(NULL);
    HBITMAP image=CreateDIBSection(dc,art,DIB_RGB_COLORS,(void **)&pixels,NULL,0);
    check(dc && image && pixels,"offscreen DIB"); HGDIOBJ old=SelectObject(dc,image);
    CoastBanner coast={.art=art,.frame=frame}; RECT r={0,0,W,H};
    for (int mode=0;mode<3;mode++) {
        coast.moving=mode==0; coast.plain=mode==2;
        paint_frame(dc,&r,&coast,0); GdiFlush(); memcpy(first,pixels,N*4);
        paint_frame(dc,&r,&coast,320); GdiFlush();
        int changed[3]={0};
        for (int i=0;i<N;i++) {
            int x=i%W,y=i/W,k=x<117 ? 0 : x<136 ? 1 : 2;
            BOOL in_well=hypot(x-(107+k*19),y-108)<8;
            DWORD c=pixels[i]&0xffffff;
            if (coast.plain) check(in_well ? c==0 || c==0xffffff : c==0xffffff,"high contrast decoration");
            if (first[i]!=pixels[i]) { check(in_well && coast.moving,"shore, reduced motion or high contrast moved"); changed[k]++; }
        }
        for (int k=0;k<3;k++) {
            int p=W*108+107+k*19;
            check((pixels[p]&0xffffff)!=(coast.plain ? 0xffffff : base[p]),"missing stationary dot");
            if (coast.moving) check(changed[k]>25 && (k ? first[p]>pixels[p] : first[p]<pixels[p]),"missing staggered pulse");
        }
    }
    SelectObject(dc,old); DeleteObject(image); DeleteDC(dc); free(first); free(frame); free(art);
}

static void check_water(void) {
    enum { W=680,H=216,N=W*H };
    DWORD *ground=malloc(N*4),*frame=malloc(N*4);
    check(ground && frame,"water motion buffers");
    for (int i=0;i<N;i++) ground[i]=frame[i]=(i%W)%16*0x071109+0x207080;
    start_water(frame,ground,W,-H,TRUE,1030);
    int changed=0;
    for (int i=0;i<N;i++) if (frame[i]!=ground[i]) {
        check(i%W<coast_line((i/W+.5)/H),"sand or shoreline animated");
        changed++;
    }
    check(changed>W*H/10,"missing water motion");
    start_water(frame,ground,W,-H,FALSE,1030);
    check(!memcmp(frame,ground,N*4),"reduced motion doesn't restore original water");
    free(frame); free(ground);
}

/* Print the fixture HWND only, never the desktop. */
static void check_frame(HWND hwnd) {
    RECT r; check(GetClientRect(hwnd,&r),"banner bounds");
    int w=r.right,h=r.bottom;
    check(w>0 && h>0 && abs(w*216-h*680)<680,"coast aspect");
    HRGN shape=CreateRectRgn(0,0,0,0);
    check(shape && GetWindowRgn(hwnd,shape)!=ERROR && !PtInRegion(shape,0,0) && PtInRegion(shape,w/2,h/2),"rounded splash region");
    DeleteObject(shape);
    BITMAPINFO info={.bmiHeader={.biSize=sizeof(BITMAPINFOHEADER),.biWidth=w,.biHeight=-h,
        .biPlanes=1,.biBitCount=32}};
    DWORD *bits=NULL;
    HDC dc=CreateCompatibleDC(NULL);
    HBITMAP image=CreateDIBSection(dc,&info,DIB_RGB_COLORS,(void **)&bits,NULL,0);
    check(dc && image && bits,"capture surface");
    HGDIOBJ old=SelectObject(dc,image);
    check(PrintWindow(hwnd,dc,PW_CLIENTONLY),"print banner"); GdiFlush();
    int glyphs=0,lo=255,hi=0,sand=0,aqua=0;
    for (int j=0;j<h;j++) for (int i=0;i<w;i++) {
        int x=i*680/w,y=j*216/h; DWORD c=bits[(size_t)j*w+i]; int red=(c>>16)&255;
        if (x>=24 && x<150 && y>=42 && y<=78 && red<105) glyphs++;
        if (x>=22 && x<88 && y>=88 && y<158) {
            lo=min(lo,red); hi=max(hi,red); if ((int)((c>>8)&255)-red>=32) aqua++;
        }
        if (x>585 && x<640 && y>145 && y<172 && red>(int)(c&255)+40) sand++;
    }
    HIGHCONTRASTW hc={.cbSize=sizeof(hc)};
    BOOL plain=SystemParametersInfoW(SPI_GETHIGHCONTRAST,sizeof(hc),&hc,0) && (hc.dwFlags&HCF_HIGHCONTRASTON);
    check(glyphs==0 && (plain ? hi==255 && lo==255 && sand==0 : hi-lo>=10 && aqua>20 && sand>20),
        "visible header or missing pastel water/sand");
    int runs=0; BOOL prior=FALSE;
    for (int i=MulDiv(90,w,680);i<MulDiv(164,w,680);i++) {
        BOOL ink=((bits[(size_t)(h/2)*w+i]>>16)&255)<110;
        if (ink && !prior) runs++;
        prior=ink;
    }
    check(runs==3,"expected three inset dots in the center of the water");
    if (evidence) {
        DWORD bytes=(DWORD)w*h*4;
        BITMAPFILEHEADER f={.bfType=0x4d42,.bfOffBits=sizeof(f)+sizeof(info.bmiHeader),.bfSize=sizeof(f)+sizeof(info.bmiHeader)+bytes};
        FILE *out=_wfopen(evidence,L"wb");
        check(out && fwrite(&f,sizeof(f),1,out) && fwrite(&info.bmiHeader,sizeof(info.bmiHeader),1,out) &&
            fwrite(bits,bytes,1,out) && fclose(out)==0,"write evidence");
    }
    SelectObject(dc,old); DeleteObject(image); DeleteDC(dc);
}
static void no_banner_until_exit(HANDLE process,DWORD expected) {
    ULONGLONG until=GetTickCount64()+30000;
    do {
        check(!sample().banner,"unexpected banner on CLI/forwarding path");
        if (WaitForSingleObject(process,4)==WAIT_OBJECT_0) {
            DWORD code; check(GetExitCodeProcess(process,&code) && code==expected,"CLI/forwarding exit"); CloseHandle(process); return;
        }
    } while (GetTickCount64()<until);
    check(FALSE,"CLI/forwarding timeout");
}
static void transition(BOOL recovery) {
    BOOL appeared=FALSE;
    ULONGLONG until=GetTickCount64()+45000;
    do {
        Windows w=sample();
        if (w.banner && !appeared) {
            if (!recovery) check_frame(w.banner);
            appeared=TRUE;
        }
        if ((recovery ? w.errors : w.workspaces) && !w.banner) {
            if (!recovery) check(appeared,"never observed the cold-start banner");
            return;
        }
        Sleep(4);
    } while (GetTickCount64()<until);
    fprintf(stderr,"%s timed out; observed banner=%d\n",recovery ? "Recovery" : "Workspace",appeared);
    EnumDesktopWindows(desktop,report,0);
    check(FALSE,"cold start did not yield to the expected window");
}
int wmain(int argc,WCHAR **argv) {
    if ((argc!=4 && argc!=5) || swprintf(command,ARRAYSIZE(command),L"\"%ls\"",argv[1])<=0) return 2;
    evidence=argc==5 ? argv[4] : NULL;
    swprintf(name,ARRAYSIZE(name),L"FishBannerTest-%lu-%llu",GetCurrentProcessId(),GetTickCount64());
    HDESK original=GetThreadDesktop(GetCurrentThreadId());
    desktop=CreateDesktopW(name,NULL,NULL,0,GENERIC_ALL,NULL);
    check(desktop && SetThreadDesktop(desktop),"create inactive desktop");
    SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    check_pulse();
    check_water();
    job=CreateJobObjectW(NULL,NULL);
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limits={0};
    limits.BasicLimitInformation.LimitFlags=JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    check(job && SetInformationJobObject(job,JobObjectExtendedLimitInformation,&limits,sizeof(limits)),"fixture job");
    no_banner_until_exit(launch(L"--help",NULL),0);
    no_banner_until_exit(launch(L"--config schema",NULL),0);
    no_banner_until_exit(launch(L"--not-a-fish-option",NULL),2);
    HANDLE demo=launch(L"--demo --diagnose-startup-notice",argv[2]); transition(FALSE);
    no_banner_until_exit(launch(L"--demo",argv[2]),0);
    check(sample().workspaces==2,"broker did not open a second demo workspace");
    HANDLE bad=launch(L"",argv[3]); transition(TRUE);
    CloseHandle(job); WaitForSingleObject(demo,5000); WaitForSingleObject(bad,5000);
    CloseHandle(demo); CloseHandle(bad);
    SetThreadDesktop(original); CloseDesktop(desktop);
    puts("Start banner passed: cold launch, focus/style, workspace and recovery dismissal, broker, CLI.");
    return 0;
}
