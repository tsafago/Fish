#define WIN32_LEAN_AND_MEAN
#include <windows.h>
#include "jni.h"

static HWND observed;
static WNDPROC previous;
static volatile LONG erases, paints, sizes;

static LRESULT CALLBACK observe(HWND window, UINT message, WPARAM wparam, LPARAM lparam) {
    if (message == WM_ERASEBKGND) InterlockedIncrement(&erases);
    if (message == WM_PAINT) InterlockedIncrement(&paints);
    if (message == WM_SIZE) InterlockedIncrement(&sizes);
    return CallWindowProcW(previous, window, message, wparam, lparam);
}
JNIEXPORT void JNICALL Java_pane5250_ui_RefinementDesktop_observe0(JNIEnv *env, jclass type, jlong handle) {
    (void)type;
    observed = (HWND)(intptr_t)handle;
    erases = paints = sizes = 0;
    SetLastError(0);
    previous = (WNDPROC)SetWindowLongPtrW(observed, GWLP_WNDPROC, (LONG_PTR)observe);
    if (!previous && GetLastError())
        (*env)->ThrowNew(env, (*env)->FindClass(env, "java/lang/IllegalStateException"), "Cannot attach resize message probe.");
}
JNIEXPORT jintArray JNICALL Java_pane5250_ui_RefinementDesktop_counts0(JNIEnv *env, jclass type) {
    (void)type;
    jint values[] = {erases, paints, sizes};
    jintArray result = (*env)->NewIntArray(env, 3);
    if (result) (*env)->SetIntArrayRegion(env, result, 0, 3, values);
    return result;
}
JNIEXPORT void JNICALL Java_pane5250_ui_RefinementDesktop_stop0(JNIEnv *env, jclass type) {
    (void)env; (void)type;
    if (observed && IsWindow(observed)) SetWindowLongPtrW(observed, GWLP_WNDPROC, (LONG_PTR)previous);
    observed = NULL;
}
