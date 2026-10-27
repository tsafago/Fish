#define WIN32_LEAN_AND_MEAN
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>
#include <windowsx.h>
#include <stdlib.h>
#include <stdint.h>
#include "jni.h"
#include "jawt.h"
#include "jawt_md.h"

typedef struct {
    WNDPROC previous;
    RECT drag, maximize;
} FishWindow;

static const WCHAR PROPERTY[] = L"Fish.NativeWindow";
static SRWLOCK state_lock = SRWLOCK_INIT;

static void failure(JNIEnv *env, const char *message) {
    (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), message);
}

static LRESULT CALLBACK fish_proc(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    FishWindow snapshot = {0};
    AcquireSRWLockShared(&state_lock);
    FishWindow *state = (FishWindow *)GetPropW(window, PROPERTY);
    if (state) snapshot = *state;
    ReleaseSRWLockShared(&state_lock);
    if (!snapshot.previous) return DefWindowProcW(window, message, wparam, lparam);
    switch (message) {
        case WM_NCCALCSIZE:
            if (wparam) {
                if (IsZoomed(window)) {
                    MONITORINFO monitor = {0};
                    monitor.cbSize = sizeof(MONITORINFO);
                    if (GetMonitorInfoW(MonitorFromWindow(window, MONITOR_DEFAULTTONEAREST), &monitor))
                        ((NCCALCSIZE_PARAMS *)lparam)->rgrc[0] = monitor.rcWork;
                }
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
            if (PtInRect(&snapshot.maximize, point)) return HTMAXBUTTON;
            if (PtInRect(&snapshot.drag, point)) return HTCAPTION;
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

JNIEXPORT jlong JNICALL Java_pane5250_ui_NativeWindow_install0(JNIEnv *env, jclass type, jobject component) {
    (void)type;
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
    RECT client; GetClientRect(window, &client);
    AcquireSRWLockExclusive(&state_lock);
    FishWindow *state = (FishWindow *)GetPropW(window, PROPERTY);
    if (state) {
        RECT *areas[2] = {&state->drag, &state->maximize};
        for (int i = 0; i < 2; i++) {
            areas[i]->left = MulDiv(values[i*4], client.right, width);
            areas[i]->top = MulDiv(values[i*4+1], client.bottom, height);
            areas[i]->right = MulDiv(values[i*4]+values[i*4+2], client.right, width);
            areas[i]->bottom = MulDiv(values[i*4+1]+values[i*4+3], client.bottom, height);
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
