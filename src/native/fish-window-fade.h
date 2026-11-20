enum { EXIT_FADE_TIMER=14, EXIT_FADE_MS=125 };

static BOOL begin_exit_fade(HWND hwnd,BOOL animate,ULONGLONG *began) {
    if (!animate || !IsWindowVisible(hwnd) || IsIconic(hwnd)) return FALSE;
    LONG_PTR style=GetWindowLongPtrW(hwnd,GWL_EXSTYLE);
    SetWindowLongPtrW(hwnd,GWL_EXSTYLE,style|WS_EX_LAYERED|WS_EX_TRANSPARENT);
    if (!SetLayeredWindowAttributes(hwnd,0,255,LWA_ALPHA) || !SetTimer(hwnd,EXIT_FADE_TIMER,15,NULL)) return FALSE;
    *began=GetTickCount64();
    RedrawWindow(hwnd,NULL,NULL,RDW_INVALIDATE|RDW_UPDATENOW|RDW_ALLCHILDREN);
    return TRUE;
}

static BOOL exit_fade_finished(HWND hwnd,ULONGLONG began) {
    ULONGLONG elapsed=GetTickCount64()-began;
    if (elapsed>=EXIT_FADE_MS) return TRUE;
    double left=1.-(double)elapsed/EXIT_FADE_MS;
    return !SetLayeredWindowAttributes(hwnd,0,(BYTE)(255*left*left),LWA_ALPHA);
}
