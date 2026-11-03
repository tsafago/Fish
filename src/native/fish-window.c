#define WIN32_LEAN_AND_MEAN
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>
#include <windowsx.h>
#include <dwmapi.h>
#include <stdlib.h>
#include <stdint.h>
#include "jni.h"
#include "jawt.h"
#include "jawt_md.h"

typedef struct {
    WNDPROC previous;
    RECT drag, maximize;
    int width, height;
} FishWindow;

static const WCHAR PROPERTY[] = L"Fish.NativeWindow";
static SRWLOCK state_lock = SRWLOCK_INIT;

static HRESULT flat_frame(HWND window) {
    /* AWT's overlapped styles are needed for snap/resize, not DWM decoration. */
    enum DWMNCRENDERINGPOLICY rendering = DWMNCRP_DISABLED;
    DWORD corners = 1, border = 0xfffffffe; /* DONOTROUND and COLOR_NONE (Windows 11). */
    HRESULT result = DwmSetWindowAttribute(window, DWMWA_NCRENDERING_POLICY, &rendering, sizeof(rendering));
    if (FAILED(result) && result != (HRESULT)0x80263001L) return result; /* Composition disabled. */
    if (SUCCEEDED(result)) {
        result = DwmSetWindowAttribute(window, 33, &corners, sizeof(corners));
        if (FAILED(result) && result != E_INVALIDARG) return result; /* Windows 10 has no corner preference. */
        result = DwmSetWindowAttribute(window, 34, &border, sizeof(border));
        if (FAILED(result) && result != E_INVALIDARG) return result;
    }
    LONG_PTR extended = GetWindowLongPtrW(window, GWL_EXSTYLE);
    SetWindowLongPtrW(window, GWL_EXSTYLE, extended & ~(WS_EX_DLGMODALFRAME | WS_EX_WINDOWEDGE | WS_EX_CLIENTEDGE | WS_EX_STATICEDGE));
    LONG_PTR style = GetClassLongPtrW(window, GCL_STYLE);
    if (style & CS_DROPSHADOW) SetClassLongPtrW(window, GCL_STYLE, style & ~CS_DROPSHADOW);
    return S_OK;
}

static void failure(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), message);
}

static RECT scaled_region(RECT area, RECT client, int width, int height) {
    RECT result = {0};
    if (width > 0 && height > 0) {
        result.left = MulDiv(area.left, client.right, width);
        result.top = MulDiv(area.top, client.bottom, height);
        result.right = MulDiv(area.right, client.right, width);
        result.bottom = MulDiv(area.bottom, client.bottom, height);
    }
    return result;
}

static void client_area(HWND window, RECT *area, BOOL maximized) {
    if (maximized) {
        MONITORINFO monitor = {0};
        monitor.cbSize = sizeof(MONITORINFO);
        if (GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor)) *area = monitor.rcWork;
    }
}

static LRESULT CALLBACK fish_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    FishWindow snapshot = {0};
    AcquireSRWLockShared(&state_lock);
    FishWindow *state = (FishWindow *)GetPropW(window, PROPERTY);
    if (state) snapshot = *state;
    ReleaseSRWLockShared(&state_lock);
    if (!snapshot.previous) return DefWindowProcW(window, message, wparam, lparam);
    switch (message) {
        case WM_STYLECHANGING:
            if ((int)wparam == GWL_EXSTYLE)
                ((STYLESTRUCT *)lparam)->styleNew &= ~(WS_EX_DLGMODALFRAME | WS_EX_WINDOWEDGE | WS_EX_CLIENTEDGE | WS_EX_STATICEDGE);
            break;
        case WM_SETICON:
        case WM_SETTEXT: {
            /* AWT refreshes icons on monitor changes. These default handlers draw
               a classic caption directly, without sending WM_NCPAINT. */
            LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
            BOOL visible = (style & WS_VISIBLE) != 0;
            if (visible) SetWindowLongPtrW(window, GWL_STYLE, style & ~WS_VISIBLE);
            LRESULT result = CallWindowProcW(snapshot.previous, window, message, wparam, lparam);
            if (visible && IsWindow(window))
                SetWindowLongPtrW(window, GWL_STYLE, GetWindowLongPtrW(window, GWL_STYLE) | WS_VISIBLE);
            return result;
        }
        case WM_NCCALCSIZE:
            if (wparam) {
                client_area(window,&((NCCALCSIZE_PARAMS *)lparam)->rgrc[0],IsZoomed(window));
                return 0;
            }
            return 0;
        case WM_NCHITTEST: {
            POINT point = {GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam)};
            RECT client;
            ScreenToClient(window, &point);
            GetClientRect(window, &client);
            LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
            if (!IsZoomed(window) && (style & WS_THICKFRAME)) {
                int border = MulDiv(5, GetDpiForWindow(window), 96);
                BOOL left = point.x < border, right = point.x >= client.right - border;
                BOOL top = point.y < border, bottom = point.y >= client.bottom - border;
                if (top) return left ? HTTOPLEFT : right ? HTTOPRIGHT : HTTOP;
                if (bottom) return left ? HTBOTTOMLEFT : right ? HTBOTTOMRIGHT : HTBOTTOM;
                if (left) return HTLEFT;
                if (right) return HTRIGHT;
            }
            RECT maximize = scaled_region(snapshot.maximize, client, snapshot.width, snapshot.height);
            RECT drag = scaled_region(snapshot.drag, client, snapshot.width, snapshot.height);
            if (PtInRect(&maximize, point)) return HTMAXBUTTON;
            if (PtInRect(&drag, point)) return HTCAPTION;
            return HTCLIENT;
        }
        case WM_NCLBUTTONDOWN:
            if (wparam == HTMAXBUTTON) return 0;
            if (wparam == HTCAPTION || (wparam >= HTLEFT && wparam <= HTBOTTOMRIGHT))
                return DefWindowProcW(window, message, wparam, lparam);
            break;
        case WM_NCLBUTTONUP:
            if (wparam == HTMAXBUTTON) {
                PostMessageW(window, WM_SYSCOMMAND, IsZoomed(window) ? SC_RESTORE : SC_MAXIMIZE, 0);
                return 0;
            }
            break;
        case WM_NCRBUTTONUP:
            if (wparam == HTCAPTION) {
                HMENU menu = GetSystemMenu(window, FALSE);
                UINT command = TrackPopupMenu(menu, TPM_RETURNCMD | TPM_RIGHTBUTTON,
                        GET_X_LPARAM(lparam), GET_Y_LPARAM(lparam), 0, window, NULL);
                if (command) PostMessageW(window, WM_SYSCOMMAND, command, 0);
                return 0;
            }
            break;
        case WM_NCPAINT: return 0;
        case WM_DWMCOMPOSITIONCHANGED:
            if (FAILED(flat_frame(window))) OutputDebugStringW(L"Fish: failed to restore flat DWM chrome after composition change.\n");
            break;
        /* DefWindowProc would repaint a classic caption behind an owned dialog. */
        case WM_NCACTIVATE: return TRUE;
        case WM_NCDESTROY:
            AcquireSRWLockExclusive(&state_lock);
            state = (FishWindow *)RemovePropW(window, PROPERTY);
            free(state);
            ReleaseSRWLockExclusive(&state_lock);
            break;
    }
    return CallWindowProcW(snapshot.previous, window, message, wparam, lparam);
}

static HWND handle_for(JNIEnv *env, jobject component) {
    JAWT awt = {0};
    awt.version = JAWT_VERSION_1_4;
    if (!JAWT_GetAWT(env, &awt)) { failure(env, "Windows drawing surface is unavailable."); return 0; }
    JAWT_DrawingSurface *surface = awt.GetDrawingSurface(env, component);
    if (!surface) { failure(env, "The Fish window has no native drawing surface."); return 0; }
    jint locked = surface->Lock(surface);
    if (locked & JAWT_LOCK_ERROR) {
        awt.FreeDrawingSurface(surface); failure(env, "The native window surface could not be locked."); return 0;
    }
    JAWT_DrawingSurfaceInfo *info = surface->GetDrawingSurfaceInfo(surface);
    HWND window = info ? ((JAWT_Win32DrawingSurfaceInfo *)info->platformInfo)->hwnd : NULL;
    if (info) surface->FreeDrawingSurfaceInfo(info);
    surface->Unlock(surface); awt.FreeDrawingSurface(surface);
    if (!window) { failure(env, "Fish has no Windows handle."); return 0; }
    return window;
}

JNIEXPORT jlong JNICALL Java_pane5250_ui_NativeWindow_install0(JNIEnv *env, jclass type, jobject component) {
    (void)type;
    HWND window = handle_for(env, component);
    if (!window) return 0;
    if (GetPropW(window, PROPERTY)) return (jlong)(intptr_t)window;
    FishWindow *state = (FishWindow *)calloc(1, sizeof(FishWindow));
    if (!state) { failure(env, "Cannot allocate Windows integration state."); return 0; }
    state->previous = (WNDPROC)GetWindowLongPtrW(window, GWLP_WNDPROC);
    if (!SetPropW(window, PROPERTY, state)) {
        free(state); failure(env, "Cannot attach Windows integration state."); return 0;
    }
    SetLastError(0);
    if (!SetWindowLongPtrW(window, GWLP_WNDPROC, (LONG_PTR)fish_proc) && GetLastError()) {
        RemovePropW(window, PROPERTY); free(state); failure(env, "Cannot install the Windows frame handler."); return 0;
    }
    LONG_PTR style = GetWindowLongPtrW(window, GWL_STYLE);
    SetWindowLongPtrW(window, GWL_STYLE, (style & ~WS_POPUP) | WS_OVERLAPPEDWINDOW);
    if (FAILED(flat_frame(window))) {
        failure(env, "Windows could not disable native frame decoration."); return 0;
    }
    SetWindowPos(window, NULL, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE | SWP_FRAMECHANGED);
    return (jlong)(intptr_t)window;
}

JNIEXPORT jboolean JNICALL Java_pane5250_ui_NativeWindow_regions0(JNIEnv *env, jclass type, jlong handle,
        jint width, jint height, jintArray rectangles) {
    (void)type;
    jint values[8];
    if ((*env)->GetArrayLength(env, rectangles) != 8 || width <= 0 || height <= 0) return JNI_FALSE;
    (*env)->GetIntArrayRegion(env, rectangles, 0, 8, values);
    if ((*env)->ExceptionCheck(env)) return JNI_FALSE;
    HWND window = (HWND)(intptr_t)handle;
    AcquireSRWLockExclusive(&state_lock);
    FishWindow *state = (FishWindow *)GetPropW(window, PROPERTY);
    if (state) {
        state->width = width; state->height = height;
        RECT *areas[2] = {&state->drag, &state->maximize};
        for (int i = 0; i < 2; i++) {
            areas[i]->left = values[i*4];
            areas[i]->top = values[i*4+1];
            areas[i]->right = values[i*4]+values[i*4+2];
            areas[i]->bottom = values[i*4+1]+values[i*4+3];
        }
    }
    ReleaseSRWLockExclusive(&state_lock);
    return state ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT void JNICALL Java_pane5250_ui_NativeWindow_move0(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    HWND window = (HWND)(intptr_t)handle;
    ReleaseCapture();
    PostMessageW(window, WM_NCLBUTTONDOWN, HTCAPTION, GetMessagePos());
}
JNIEXPORT void JNICALL Java_pane5250_ui_NativeWindow_maximize0(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    HWND window = (HWND)(intptr_t)handle;
    PostMessageW(window, WM_SYSCOMMAND, IsZoomed(window) ? SC_RESTORE : SC_MAXIMIZE, 0);
}
JNIEXPORT jint JNICALL Java_pane5250_ui_NativeWindow_hitTest0(JNIEnv *env, jclass type, jlong handle, jint x, jint y) {
    (void)env; (void)type;
    HWND window = (HWND)(intptr_t)handle;
    RECT client; GetClientRect(window, &client);
    POINT point = {x, y}; ClientToScreen(window, &point);
    return (jint)SendMessageW(window, WM_NCHITTEST, 0, MAKELPARAM(point.x, point.y));
}
JNIEXPORT jlong JNICALL Java_pane5250_ui_NativeWindow_style0(JNIEnv *env, jclass type, jlong handle) {
    (void)env; (void)type;
    return (jlong)GetWindowLongPtrW((HWND)(intptr_t)handle, GWL_STYLE);
}
JNIEXPORT void JNICALL Java_pane5250_ui_NativeWindow_flat0(JNIEnv *env, jclass type, jobject component) {
    (void)type;
    HWND window = handle_for(env, component);
    if (window && FAILED(flat_frame(window))) failure(env, "Windows could not disable popup/dialog frame decoration.");
}
JNIEXPORT jintArray JNICALL Java_pane5250_ui_NativeWindow_geometry0(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    HWND window = (HWND)(intptr_t)handle;
    RECT outer, client;
    if (!GetWindowRect(window, &outer) || !GetClientRect(window, &client)) {
        failure(env, "Cannot read native window bounds."); return NULL;
    }
    DWORD corners = 0;
    BOOL rendering = FALSE;
    DwmGetWindowAttribute(window, 33, &corners, sizeof(corners));
    DwmGetWindowAttribute(window, DWMWA_NCRENDERING_ENABLED, &rendering, sizeof(rendering));
    jint values[] = {outer.right-outer.left,outer.bottom-outer.top,client.right,client.bottom,
        (jint)corners,(jint)GetWindowLongPtrW(window,GWL_EXSTYLE),(jint)rendering};
    jintArray result = (*env)->NewIntArray(env, 7);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 7, values);
    return result;
}
JNIEXPORT jintArray JNICALL Java_pane5250_ui_NativeWindow_frameBounds0(JNIEnv *env, jclass type, jlong handle, jboolean maximized) {
    (void)type;
    HWND window = (HWND)(intptr_t)handle;
    RECT outer;
    MONITORINFO monitor = {0}; monitor.cbSize = sizeof(MONITORINFO);
    if (!GetWindowRect(window,&outer) || !GetMonitorInfoW(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&monitor)) {
        failure(env,"Cannot read native frame/work-area bounds."); return NULL;
    }
    RECT client = outer; client_area(window,&client,maximized);
    jint values[] = {outer.left,outer.top,outer.right,outer.bottom,client.left,client.top,client.right,client.bottom,
        monitor.rcWork.left,monitor.rcWork.top,monitor.rcWork.right,monitor.rcWork.bottom};
    jintArray result = (*env)->NewIntArray(env,12);
    if (result) (*env)->SetIntArrayRegion(env,result,0,12,values);
    return result;
}
