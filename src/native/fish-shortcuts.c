#define UNICODE
#define _UNICODE
#define COBJMACROS
#include <windows.h>
#include <jni.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <shellapi.h>
#include <wincred.h>
#include <objbase.h>
#include <dwmapi.h>

static void failure(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/io/IOException");
    if (type) (*env)->ThrowNew(env,type,message);
}
static WCHAR *text_arg(JNIEnv *env,jstring value,BOOL allow_empty) {
    if (!value) { failure(env,"Missing Windows shortcut value."); return NULL; }
    jsize size=(*env)->GetStringLength(env,value);
    if ((!allow_empty && !size) || size<0 || size>32700) { failure(env,"Windows shortcut value is empty or too long."); return NULL; }
    WCHAR *copy=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,((size_t)size+1)*sizeof(WCHAR));
    if (!copy) { failure(env,"Windows shortcut allocation failed."); return NULL; }
    if (size>0) (*env)->GetStringRegion(env,value,0,size,(jchar *)copy);
    for (jsize n=0;n<size;n++) if (copy[n]<32) {
        HeapFree(GetProcessHeap(),0,copy); failure(env,"Invalid Windows shortcut value."); return NULL;
    }
    return copy;
}
static WCHAR *text(JNIEnv *env,jstring value) { return text_arg(env,value,FALSE); }
static void notify_parent_dir(const WCHAR *file) {
    if (!file || !*file) return;
    size_t len=wcslen(file);
    WCHAR *parent=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,(len+1)*sizeof(WCHAR));
    if (!parent) return;
    wcscpy(parent,file);
    WCHAR *slash=wcsrchr(parent,L'\\');
    if (slash) {
        if (slash==parent+2 && parent[1]==L':') slash[1]=0;
        else *slash=0;
        if (*parent) SHChangeNotify(SHCNE_UPDATEDIR,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,parent,NULL);
    }
    HeapFree(GetProcessHeap(),0,parent);
}
JNIEXPORT jstring JNICALL Java_fish_LayoutShortcuts_executable0(JNIEnv *env,jclass type) {
    (void)type;
    WCHAR path[32768]; DWORD size=GetModuleFileNameW(NULL,path,32768);
    if (!size || size>=32768) { failure(env,"Cannot locate the running Fish executable."); return NULL; }
    return (*env)->NewString(env,(jchar *)path,(jsize)size);
}
JNIEXPORT jstring JNICALL Java_fish_LayoutShortcuts_desktop0(JNIEnv *env,jclass type) {
    (void)type;
    PWSTR path=NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_Desktop,KF_FLAG_CREATE,NULL,&path))
            && FAILED(SHGetKnownFolderPath(&FOLDERID_Desktop,0,NULL,&path))) {
        failure(env,"Cannot locate your Desktop folder. Choose another local folder."); return NULL;
    }
    jstring result=(*env)->NewString(env,(jchar *)path,(jsize)wcslen(path)); CoTaskMemFree(path); return result;
}
JNIEXPORT jstring JNICALL Java_fish_LayoutShortcuts_startMenu0(JNIEnv *env,jclass type) {
    (void)type;
    PWSTR path=NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_Programs,KF_FLAG_CREATE,NULL,&path))
            && FAILED(SHGetKnownFolderPath(&FOLDERID_Programs,0,NULL,&path))) {
        failure(env,"Cannot locate your Start Menu Programs folder."); return NULL;
    }
    jstring result=(*env)->NewString(env,(jchar *)path,(jsize)wcslen(path)); CoTaskMemFree(path); return result;
}
JNIEXPORT jstring JNICALL Java_fish_LayoutShortcuts_startup0(JNIEnv *env,jclass type) {
    (void)type;
    PWSTR path=NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_Startup,KF_FLAG_CREATE,NULL,&path))
            && FAILED(SHGetKnownFolderPath(&FOLDERID_Startup,0,NULL,&path))) {
        failure(env,"Cannot locate your Windows Startup folder."); return NULL;
    }
    jstring result=(*env)->NewString(env,(jchar *)path,(jsize)wcslen(path)); CoTaskMemFree(path); return result;
}
JNIEXPORT jstring JNICALL Java_fish_LayoutShortcuts_commonStartMenu0(JNIEnv *env,jclass type) {
    (void)type;
    PWSTR path=NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_CommonPrograms,0,NULL,&path))) {
        failure(env,"Cannot locate the All Users Start Menu Programs folder."); return NULL;
    }
    jstring result=(*env)->NewString(env,(jchar *)path,(jsize)wcslen(path)); CoTaskMemFree(path); return result;
}
JNIEXPORT jstring JNICALL Java_fish_LayoutShortcuts_commonDesktop0(JNIEnv *env,jclass type) {
    (void)type;
    PWSTR path=NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_PublicDesktop,0,NULL,&path))) {
        failure(env,"Cannot locate the Public Desktop folder."); return NULL;
    }
    jstring result=(*env)->NewString(env,(jchar *)path,(jsize)wcslen(path)); CoTaskMemFree(path); return result;
}
JNIEXPORT void JNICALL Java_fish_LayoutShortcuts_create0(JNIEnv *env,jclass type,jstring destination,jstring target,jstring arguments,jstring directory) {
    (void)type;
    WCHAR *file=text(env,destination),*exe=file ? text(env,target) : NULL,*args=exe ? text_arg(env,arguments,TRUE) : NULL,*cwd=args ? text(env,directory) : NULL;
    if (!cwd) goto done;
    HRESULT init=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    if (FAILED(init) && init!=RPC_E_CHANGED_MODE) { failure(env,"Windows Shell initialization failed."); goto done; }
    DWORD prev_attr=GetFileAttributesW(file);
    BOOL existed_before=(prev_attr!=INVALID_FILE_ATTRIBUTES);
    if (existed_before && (prev_attr & FILE_ATTRIBUTE_READONLY)) {
        SetFileAttributesW(file,prev_attr & ~FILE_ATTRIBUTE_READONLY);
    }
    IShellLinkW *link=NULL; IPersistFile *persist=NULL;
    HRESULT result=CoCreateInstance(&CLSID_ShellLink,NULL,CLSCTX_INPROC_SERVER,&IID_IShellLinkW,(void **)&link);
    if (SUCCEEDED(result)) result=IShellLinkW_SetPath(link,exe);
    if (SUCCEEDED(result)) result=IShellLinkW_SetArguments(link,args);
    if (SUCCEEDED(result)) result=IShellLinkW_SetWorkingDirectory(link,cwd);
    if (SUCCEEDED(result)) result=IShellLinkW_SetIconLocation(link,exe,0);
    if (SUCCEEDED(result)) result=IShellLinkW_SetShowCmd(link,SW_SHOWNORMAL);
    if (SUCCEEDED(result)) result=IShellLinkW_QueryInterface(link,&IID_IPersistFile,(void **)&persist);
    if (SUCCEEDED(result)) {
        for (int attempt=0;attempt<10;attempt++) {
            result=IPersistFile_Save(persist,file,TRUE);
            if (SUCCEEDED(result) || (result!=HRESULT_FROM_WIN32(ERROR_SHARING_VIOLATION) && result!=STG_E_SHAREVIOLATION)) break;
            Sleep(25);
        }
    }
    if (SUCCEEDED(result)) (void)IPersistFile_SaveCompleted(persist,file);
    if (persist) IPersistFile_Release(persist);
    if (link) IShellLinkW_Release(link);
    if (SUCCEEDED(init)) CoUninitialize();
    if (FAILED(result)) {
        if (!existed_before) DeleteFileW(file);
        failure(env,"Windows could not save the layout shortcut. Choose a writable folder.");
    } else {
        SetFileAttributesW(file,FILE_ATTRIBUTE_ARCHIVE);
        SHChangeNotify(existed_before ? SHCNE_UPDATEITEM : SHCNE_CREATE,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,file,NULL);
        if (!existed_before) SHChangeNotify(SHCNE_UPDATEITEM,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,file,NULL);
        notify_parent_dir(file);
    }
done:
    if (file) HeapFree(GetProcessHeap(),0,file);
    if (exe) HeapFree(GetProcessHeap(),0,exe);
    if (args) HeapFree(GetProcessHeap(),0,args);
    if (cwd) HeapFree(GetProcessHeap(),0,cwd);
}
JNIEXPORT void JNICALL Java_fish_LayoutShortcuts_notifyDeleted0(JNIEnv *env,jclass type,jstring shortcut) {
    (void)type;
    WCHAR *file=text(env,shortcut);
    if (!file) return;
    SHChangeNotify(SHCNE_DELETE,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,file,NULL);
    notify_parent_dir(file);
    HeapFree(GetProcessHeap(),0,file);
}
JNIEXPORT jboolean JNICALL Java_fish_LayoutShortcuts_desktopIconsHidden0(JNIEnv *env,jclass type) {
    (void)env; (void)type;
    HKEY key=NULL;
    if (RegOpenKeyExW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Advanced",0,KEY_QUERY_VALUE,&key)!=ERROR_SUCCESS)
        return JNI_FALSE;
    DWORD value=0,reg_type=0,size=sizeof(DWORD);
    LONG rc=RegQueryValueExW(key,L"HideIcons",NULL,&reg_type,(LPBYTE)&value,&size);
    RegCloseKey(key);
    return (rc==ERROR_SUCCESS && reg_type==REG_DWORD && value==1) ? JNI_TRUE : JNI_FALSE;
}
JNIEXPORT jobjectArray JNICALL Java_fish_LayoutShortcuts_inspect0(JNIEnv *env,jclass type,jstring shortcut) {
    (void)type;
    WCHAR *file=text(env,shortcut); if (!file) return NULL;
    HRESULT init=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    if (FAILED(init) && init!=RPC_E_CHANGED_MODE) { HeapFree(GetProcessHeap(),0,file); failure(env,"Windows Shell initialization failed."); return NULL; }
    IShellLinkW *link=NULL; IPersistFile *persist=NULL; WCHAR *values=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,4*32768*sizeof(WCHAR));
    HRESULT result=values ? CoCreateInstance(&CLSID_ShellLink,NULL,CLSCTX_INPROC_SERVER,&IID_IShellLinkW,(void **)&link) : E_OUTOFMEMORY;
    if (SUCCEEDED(result)) result=IShellLinkW_QueryInterface(link,&IID_IPersistFile,(void **)&persist);
    if (SUCCEEDED(result)) {
        for (int attempt=0;attempt<10;attempt++) {
            result=IPersistFile_Load(persist,file,STGM_READ|STGM_SHARE_DENY_NONE);
            if (SUCCEEDED(result) || (result!=HRESULT_FROM_WIN32(ERROR_SHARING_VIOLATION) && result!=STG_E_SHAREVIOLATION)) break;
            Sleep(25);
        }
    }
    if (SUCCEEDED(result)) result=IShellLinkW_GetPath(link,values,32768,NULL,SLGP_RAWPATH);
    if (SUCCEEDED(result)) result=IShellLinkW_GetArguments(link,values+32768,32768);
    if (SUCCEEDED(result)) result=IShellLinkW_GetWorkingDirectory(link,values+65536,32768);
    int icon=0;
    if (SUCCEEDED(result)) result=IShellLinkW_GetIconLocation(link,values+98304,32768,&icon);
    jobjectArray output=NULL;
    if (SUCCEEDED(result)) {
        output=(*env)->NewObjectArray(env,4,(*env)->FindClass(env,"java/lang/String"),NULL);
        for (int n=0;n<4 && output;n++) {
            jstring value=(*env)->NewString(env,(jchar *)(values+n*32768),(jsize)wcslen(values+n*32768));
            (*env)->SetObjectArrayElement(env,output,n,value); (*env)->DeleteLocalRef(env,value);
        }
    } else failure(env,"Cannot inspect the Windows layout shortcut.");
    if (values) HeapFree(GetProcessHeap(),0,values);
    if (persist) IPersistFile_Release(persist);
    if (link) IShellLinkW_Release(link);
    if (SUCCEEDED(init)) CoUninitialize();
    HeapFree(GetProcessHeap(),0,file); return output;
}

typedef struct {
    DWORD pid;
    BOOL workspace_outline;
    COLORREF divider_color;
    int updated;
} border_update_ctx;

// Before show, the Glass peer exists but may not have a title. Match only
// one hidden stage with the requested ownership on this FX thread.
typedef struct { HWND match; BOOL owned; } stage_search;
static const WCHAR first_frame_property[]=L"Fish.FirstFrame";

// Confine the pinned OpenJFX 25 peer lookup to the Windows bridge. Never
// identify a workspace by its title, position, or whichever HWND has focus.
JNIEXPORT jlong JNICALL Java_fish_ui_PaneWindowHit_handle0(JNIEnv *env,jclass type,jobject stage) {
    (void)type;
    jclass window=(*env)->GetObjectClass(env,stage);
    jmethodID getter=(*env)->GetMethodID(env,window,"getPeer","()Lcom/sun/javafx/tk/TKStage;");
    if (!getter) return 0;
    jobject peer=(*env)->CallObjectMethod(env,stage,getter);
    if (!peer || (*env)->ExceptionCheck(env)) return 0;
    jclass peer_class=(*env)->GetObjectClass(env,peer);
    getter=(*env)->GetMethodID(env,peer_class,"getRawHandle","()J");
    if (!getter) return 0;
    jlong id=(*env)->CallLongMethod(env,peer,getter);
    return GetWindowThreadProcessId((HWND)(intptr_t)id,NULL)==GetCurrentThreadId() ? id : 0;
}

JNIEXPORT jlong JNICALL Java_fish_ui_PaneWindowHit_at0(JNIEnv *env,jclass type,jlong source,jintArray point) {
    (void)type;
    HWND from=(HWND)(intptr_t)source;
    if (GetWindowThreadProcessId(from,NULL)!=GetCurrentThreadId() || (*env)->GetArrayLength(env,point)!=2) return 0;
    jint xy[2]; (*env)->GetIntArrayRegion(env,point,0,2,xy);
    POINT p={xy[0],xy[1]};
    if (!ClientToScreen(from,&p) || !MonitorFromPoint(p,MONITOR_DEFAULTTONULL)) return 0;
    HWND under=WindowFromPoint(p),hit=under ? GetAncestor(under,GA_ROOT) : NULL;
    /* A monitor-backed point may have no window at all on an empty desktop. */
    if (!under || under==GetDesktopWindow()) hit=(HWND)(intptr_t)-1;
    else if (!hit) return 0;
    DWORD pid=0;
    if (hit!=(HWND)(intptr_t)-1) GetWindowThreadProcessId(hit,&pid);
    if (hit!=(HWND)(intptr_t)-1 && pid==GetCurrentProcessId()) {
        if (!IsWindowEnabled(hit) || !ScreenToClient(hit,&p)) return 0;
    } else if (hit!=(HWND)(intptr_t)-1) {
        WCHAR cls[128];
        if (!GetClassNameW(hit,cls,128) || wcsncmp(cls,L"GlassWndClass",13)==0) return 0;
        hit=(HWND)(intptr_t)-1;
    }
    xy[0]=p.x; xy[1]=p.y; (*env)->SetIntArrayRegion(env,point,0,2,xy);
    return (jlong)(intptr_t)hit;
}

JNIEXPORT void JNICALL Java_fish_ui_PaneWindowHit_place0(JNIEnv *env,jclass type,jlong id,jint x,jint y) {
    (void)env; (void)type;
    HWND window=(HWND)(intptr_t)id;
    if (GetWindowThreadProcessId(window,NULL)!=GetCurrentThreadId()) return;
    POINT p={x,y};
    MONITORINFO m={.cbSize=sizeof(m)};
    if (!GetMonitorInfoW(MonitorFromPoint(p,MONITOR_DEFAULTTONEAREST),&m)) return;
    // Moving between monitors can synchronously change the HWND's DPI and size.
    for (int i=0;i<2;i++) {
        RECT r;
        if (!GetWindowRect(window,&r)) return;
        int width=min(r.right-r.left,m.rcWork.right-m.rcWork.left);
        int height=min(r.bottom-r.top,m.rcWork.bottom-m.rcWork.top);
        int left=max(m.rcWork.left,min(x-24,m.rcWork.right-width));
        int top=max(m.rcWork.top,min(y-12,m.rcWork.bottom-height));
        if (r.left==left && r.top==top && r.right==left+width && r.bottom==top+height) break;
        SetWindowPos(window,NULL,left,top,width,height,SWP_NOZORDER|SWP_NOACTIVATE);
    }
}

static BOOL CALLBACK find_new_stage(HWND hwnd,LPARAM data) {
    stage_search *search=(stage_search *)data;
    WCHAR name[96];
    static const WCHAR prefix[]=L"GlassWndClass-GlassWindowClass-";
    if (IsWindowVisible(hwnd) || (GetWindow(hwnd,GW_OWNER)!=NULL)!=search->owned
            || (GetWindowLongPtrW(hwnd,GWL_EXSTYLE)&WS_EX_TOOLWINDOW)
            || !GetClassNameW(hwnd,name,96) || wcsncmp(name,prefix,ARRAYSIZE(prefix)-1)!=0) return TRUE;
    if (search->match) { search->match=NULL; return FALSE; }
    search->match=hwnd; return TRUE;
}

JNIEXPORT jlong JNICALL Java_fish_ui_WindowBorder_installStartupBrush0(JNIEnv *env,jclass type,jint rgb,jboolean owned) {
    (void)env; (void)type;
    stage_search search={NULL,owned ? TRUE : FALSE};
    EnumThreadWindows(GetCurrentThreadId(),find_new_stage,(LPARAM)&search);
    HWND window=search.match;
    // Glass registers a separate class per HWND. Never replace a toolkit brush.
    if (!window || GetClassLongPtrW(window,GCLP_HBRBACKGROUND)) return 0;
    HBRUSH brush=CreateSolidBrush(RGB((rgb>>16)&255,(rgb>>8)&255,rgb&255));
    if (!brush || !SetPropW(window,first_frame_property,brush)) {
        if (brush) DeleteObject(brush);
        return 0;
    }
    SetLastError(0);
    if (!SetClassLongPtrW(window,GCLP_HBRBACKGROUND,(LONG_PTR)brush) && GetLastError()) {
        RemovePropW(window,first_frame_property);
        DeleteObject(brush);
        return 0;
    }
    return (jlong)(intptr_t)window;
}

JNIEXPORT void JNICALL Java_fish_ui_WindowBorder_removeStartupBrush0(JNIEnv *env,jclass type,jlong handle) {
    (void)env; (void)type;
    HWND window=(HWND)(intptr_t)handle;
    if (GetWindowThreadProcessId(window,NULL)!=GetCurrentThreadId()) return;
    HBRUSH brush=(HBRUSH)GetPropW(window,first_frame_property);
    if (!brush) return;
    if ((HBRUSH)GetClassLongPtrW(window,GCLP_HBRBACKGROUND)==brush
            && !SetClassLongPtrW(window,GCLP_HBRBACKGROUND,0)) return;
    RemovePropW(window,first_frame_property);
    DeleteObject(brush);
}

#ifndef DWMWA_BORDER_COLOR
#define DWMWA_BORDER_COLOR 34
#endif
#ifndef DWMWA_COLOR_NONE
#define DWMWA_COLOR_NONE 0xFFFFFFFEu
#endif

static BOOL CALLBACK apply_dwm_border_proc(HWND hwnd,LPARAM lparam) {
    border_update_ctx *ctx=(border_update_ctx *)lparam;
    DWORD pid=0;
    GetWindowThreadProcessId(hwnd,&pid);
    if (pid!=ctx->pid || !IsWindowVisible(hwnd)) return TRUE;
    WCHAR cls[128];
    int len=GetClassNameW(hwnd,cls,128);
    if (len<=0 || wcsncmp(cls,L"GlassWndClass",13)!=0) return TRUE;
    LONG_PTR ex=GetWindowLongPtrW(hwnd,GWL_EXSTYLE);
    if ((ex & WS_EX_TOOLWINDOW)!=0) return TRUE;
    BOOL is_owned=GetWindow(hwnd,GW_OWNER)!=NULL;
    BOOL show_border=is_owned ? !IsZoomed(hwnd) : (ctx->workspace_outline && !IsZoomed(hwnd));
    COLORREF value=show_border ? ctx->divider_color : (COLORREF)DWMWA_COLOR_NONE;
    HRESULT hr=DwmSetWindowAttribute(hwnd,DWMWA_BORDER_COLOR,&value,sizeof(value));
    if (SUCCEEDED(hr)) ctx->updated++;
    return TRUE;
}

JNIEXPORT jboolean JNICALL Java_fish_ui_WindowBorder_syncProcessWindows0(JNIEnv *env,jclass type,jboolean workspace_outline,jint rgb) {
    (void)env; (void)type;
    border_update_ctx ctx;
    ctx.pid=GetCurrentProcessId();
    ctx.workspace_outline=workspace_outline ? TRUE : FALSE;
    DWORD r=(DWORD)((rgb>>16)&0xff),g=(DWORD)((rgb>>8)&0xff),b=(DWORD)(rgb&0xff);
    ctx.divider_color=(COLORREF)(r | (g<<8) | (b<<16));
    ctx.updated=0;
    EnumWindows(apply_dwm_border_proc,(LPARAM)&ctx);
    return ctx.updated>0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_fish_security_KioskMode_readRegistry0(JNIEnv *env,jclass type) {
    (void)env; (void)type;
    HKEY key=NULL;
    if (RegOpenKeyExW(HKEY_LOCAL_MACHINE,L"SOFTWARE\\Fish",0,KEY_QUERY_VALUE|KEY_WOW64_64KEY,&key)!=ERROR_SUCCESS)
        return JNI_FALSE;
    DWORD value=0,reg_type=0,size=sizeof(DWORD);
    LONG rc=RegQueryValueExW(key,L"KioskMode",NULL,&reg_type,(LPBYTE)&value,&size);
    RegCloseKey(key);
    return (rc==ERROR_SUCCESS && reg_type==REG_DWORD && value!=0) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_fish_security_KioskMode_writeRegistry0(JNIEnv *env,jclass type,jboolean enabled) {
    (void)type;
    HKEY key=NULL;
    LONG rc=RegCreateKeyExW(HKEY_LOCAL_MACHINE,L"SOFTWARE\\Fish",0,NULL,REG_OPTION_NON_VOLATILE,
            KEY_SET_VALUE|KEY_WOW64_64KEY,NULL,&key,NULL);
    if (rc!=ERROR_SUCCESS) {
        failure(env,rc==ERROR_ACCESS_DENIED
                ? "Administrator privileges are required to change Kiosk mode."
                : "Windows could not open HKLM\\SOFTWARE\\Fish.");
        return;
    }
    DWORD value=enabled ? 1u : 0u;
    rc=RegSetValueExW(key,L"KioskMode",0,REG_DWORD,(const BYTE *)&value,sizeof(DWORD));
    RegCloseKey(key);
    if (rc!=ERROR_SUCCESS) failure(env,"Windows could not save the Kiosk mode setting.");
}

JNIEXPORT jboolean JNICALL Java_fish_security_KioskMode_isUserAdmin0(JNIEnv *env,jclass type) {
    (void)env; (void)type;
    return IsUserAnAdmin() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_fish_security_KioskMode_runElevatedWait0(JNIEnv *env,jclass type,jstring executable,jstring arguments,jstring directory,jboolean show_window) {
    (void)type;
    WCHAR *exe=text(env,executable);
    WCHAR *args=exe ? text_arg(env,arguments,TRUE) : NULL;
    WCHAR *cwd=args ? text_arg(env,directory,TRUE) : NULL;
    jint status=-1;
    if (!cwd) goto done;
    SHELLEXECUTEINFOW sei;
    ZeroMemory(&sei,sizeof(sei));
    sei.cbSize=sizeof(sei);
    sei.fMask=SEE_MASK_NOCLOSEPROCESS;
    sei.lpVerb=L"runas";
    sei.lpFile=exe;
    sei.lpParameters=args;
    sei.lpDirectory=cwd[0] ? cwd : NULL;
    sei.nShow=show_window ? SW_SHOWNORMAL : SW_HIDE;
    if (!ShellExecuteExW(&sei)) {
        DWORD err=GetLastError();
        if (err==ERROR_CANCELLED) { status=-1223; goto done; }
        failure(env,"Windows could not launch the elevated Administrator command.");
        goto done;
    }
    if (sei.hProcess) {
        WaitForSingleObject(sei.hProcess,INFINITE);
        DWORD exit_code=1;
        if (GetExitCodeProcess(sei.hProcess,&exit_code)) status=(jint)exit_code;
        CloseHandle(sei.hProcess);
    } else {
        status=0;
    }
done:
    if (exe) HeapFree(GetProcessHeap(),0,exe);
    if (args) HeapFree(GetProcessHeap(),0,args);
    if (cwd) HeapFree(GetProcessHeap(),0,cwd);
    return status;
}

JNIEXPORT jint JNICALL Java_fish_security_KioskMode_deleteAllFishCredentials0(JNIEnv *env,jclass type) {
    (void)env; (void)type;
    DWORD count=0;
    PCREDENTIALW *creds=NULL;
    if (!CredEnumerateW(L"Fish/*",0,&count,&creds)) return 0;
    jint removed=0;
    for (DWORD i=0;i<count;i++) {
        if (creds[i] && creds[i]->TargetName) {
            if (CredDeleteW(creds[i]->TargetName,creds[i]->Type,0)) removed++;
        }
    }
    CredFree(creds);
    return removed;
}


