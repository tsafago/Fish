#define UNICODE
#define _UNICODE
#define COBJMACROS
#include <windows.h>
#include <jni.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <objbase.h>

static void failure(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/io/IOException");
    if (type) (*env)->ThrowNew(env,type,message);
}
static WCHAR *text(JNIEnv *env,jstring value) {
    if (!value) { failure(env,"Missing Windows shortcut value."); return NULL; }
    jsize size=(*env)->GetStringLength(env,value);
    if (!size || size>32700) { failure(env,"Windows shortcut value is empty or too long."); return NULL; }
    WCHAR *copy=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,((size_t)size+1)*sizeof(WCHAR));
    if (!copy) { failure(env,"Windows shortcut allocation failed."); return NULL; }
    (*env)->GetStringRegion(env,value,0,size,(jchar *)copy);
    for (jsize n=0;n<size;n++) if (copy[n]<32) {
        HeapFree(GetProcessHeap(),0,copy); failure(env,"Invalid Windows shortcut value."); return NULL;
    }
    return copy;
}
JNIEXPORT jstring JNICALL Java_pane5250_LayoutShortcuts_executable0(JNIEnv *env,jclass type) {
    (void)type;
    WCHAR path[32768]; DWORD size=GetModuleFileNameW(NULL,path,32768);
    if (!size || size>=32768) { failure(env,"Cannot locate the running Fish executable."); return NULL; }
    return (*env)->NewString(env,(jchar *)path,(jsize)size);
}
JNIEXPORT jstring JNICALL Java_pane5250_LayoutShortcuts_desktop0(JNIEnv *env,jclass type) {
    (void)type;
    PWSTR path=NULL;
    if (FAILED(SHGetKnownFolderPath(&FOLDERID_Desktop,0,NULL,&path))) {
        failure(env,"Cannot locate your Desktop folder. Choose another local folder."); return NULL;
    }
    jstring result=(*env)->NewString(env,(jchar *)path,(jsize)wcslen(path)); CoTaskMemFree(path); return result;
}
JNIEXPORT void JNICALL Java_pane5250_LayoutShortcuts_create0(JNIEnv *env,jclass type,jstring destination,jstring target,jstring arguments,jstring directory) {
    (void)type;
    WCHAR *file=text(env,destination),*exe=file ? text(env,target) : NULL,*args=exe ? text(env,arguments) : NULL,*cwd=args ? text(env,directory) : NULL;
    if (!cwd) goto done;
    HRESULT init=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    if (FAILED(init) && init!=RPC_E_CHANGED_MODE) { failure(env,"Windows Shell initialization failed."); goto done; }
    IShellLinkW *link=NULL; IPersistFile *persist=NULL;
    HRESULT result=CoCreateInstance(&CLSID_ShellLink,NULL,CLSCTX_INPROC_SERVER,&IID_IShellLinkW,(void **)&link);
    if (SUCCEEDED(result)) result=IShellLinkW_SetPath(link,exe);
    if (SUCCEEDED(result)) result=IShellLinkW_SetArguments(link,args);
    if (SUCCEEDED(result)) result=IShellLinkW_SetWorkingDirectory(link,cwd);
    if (SUCCEEDED(result)) result=IShellLinkW_SetIconLocation(link,exe,0);
    if (SUCCEEDED(result)) result=IShellLinkW_SetShowCmd(link,SW_SHOWNORMAL);
    if (SUCCEEDED(result)) result=IShellLinkW_QueryInterface(link,&IID_IPersistFile,(void **)&persist);
    if (SUCCEEDED(result)) result=IPersistFile_Save(persist,file,TRUE);
    if (persist) IPersistFile_Release(persist);
    if (link) IShellLinkW_Release(link);
    if (SUCCEEDED(init)) CoUninitialize();
    if (FAILED(result)) failure(env,"Windows could not save the layout shortcut. Choose a writable folder.");
done:
    if (file) HeapFree(GetProcessHeap(),0,file);
    if (exe) HeapFree(GetProcessHeap(),0,exe);
    if (args) HeapFree(GetProcessHeap(),0,args);
    if (cwd) HeapFree(GetProcessHeap(),0,cwd);
}
JNIEXPORT jobjectArray JNICALL Java_pane5250_LayoutShortcuts_inspect0(JNIEnv *env,jclass type,jstring shortcut) {
    (void)type;
    WCHAR *file=text(env,shortcut); if (!file) return NULL;
    HRESULT init=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    if (FAILED(init) && init!=RPC_E_CHANGED_MODE) { HeapFree(GetProcessHeap(),0,file); failure(env,"Windows Shell initialization failed."); return NULL; }
    IShellLinkW *link=NULL; IPersistFile *persist=NULL; WCHAR *values=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,4*32768*sizeof(WCHAR));
    HRESULT result=values ? CoCreateInstance(&CLSID_ShellLink,NULL,CLSCTX_INPROC_SERVER,&IID_IShellLinkW,(void **)&link) : E_OUTOFMEMORY;
    if (SUCCEEDED(result)) result=IShellLinkW_QueryInterface(link,&IID_IPersistFile,(void **)&persist);
    if (SUCCEEDED(result)) result=IPersistFile_Load(persist,file,STGM_READ);
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
