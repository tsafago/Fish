#ifndef FISH_USER_LAUNCH_H
#define FISH_USER_LAUNCH_H
#include <exdisp.h>
#include <shldisp.h>
#include <shlguid.h>

static BOOL fish_elevation(HANDLE process,BOOL *elevated) {
    HANDLE token;
    if (!OpenProcessToken(process,TOKEN_QUERY,&token)) return FALSE;
    TOKEN_ELEVATION info; DWORD bytes;
    BOOL ok=GetTokenInformation(token,TokenElevation,&info,sizeof(info),&bytes);
    if (ok) *elevated=info.TokenIsElevated!=0;
    CloseHandle(token);
    return ok;
}

/* Ask the desktop's shell to open the installed app, not the administrator's
   linked token: over-the-shoulder UAC can use an entirely different account. */
static HRESULT fish_open_from_desktop(const WCHAR *path,const WCHAR *arguments,const WCHAR *directory) {
    IShellWindows *windows=NULL; IDispatch *desktop=NULL,*item=NULL,*application=NULL;
    IServiceProvider *services=NULL; IShellBrowser *browser=NULL; IShellView *view=NULL;
    IShellFolderViewDual *folder=NULL; IShellDispatch2 *shell=NULL;
    VARIANT location={.vt=VT_I4,.lVal=CSIDL_DESKTOP},empty={0}; LONG unused=0;
    HRESULT hr=CoCreateInstance(&CLSID_ShellWindows,NULL,CLSCTX_LOCAL_SERVER,&IID_IShellWindows,(void **)&windows);
    if (FAILED(hr)) goto done;
    hr=IShellWindows_FindWindowSW(windows,&location,&empty,SWC_DESKTOP,&unused,SWFO_NEEDDISPATCH,&desktop);
    if (hr!=S_OK || !desktop) {hr=HRESULT_FROM_WIN32(ERROR_NO_USER_SESSION_KEY); goto done;}
    hr=IDispatch_QueryInterface(desktop,&IID_IServiceProvider,(void **)&services); if (FAILED(hr)) goto done;
    hr=IServiceProvider_QueryService(services,&SID_STopLevelBrowser,&IID_IShellBrowser,(void **)&browser); if (FAILED(hr)) goto done;
    HWND hwnd=NULL; DWORD pid=0,shell_pid=0;
    hr=IShellBrowser_GetWindow(browser,&hwnd); if (FAILED(hr)) goto done;
    if (!GetWindowThreadProcessId(hwnd,&pid) || !GetWindowThreadProcessId(GetShellWindow(),&shell_pid) || pid!=shell_pid) {
        hr=E_ACCESSDENIED; goto done;
    }
    HANDLE process=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid);
    BOOL elevated=TRUE,ok=process && fish_elevation(process,&elevated) && !elevated;
    if (process) CloseHandle(process);
    if (!ok) {hr=E_ACCESSDENIED; goto done;}
    hr=IShellBrowser_QueryActiveShellView(browser,&view); if (FAILED(hr)) goto done;
    hr=IShellView_GetItemObject(view,SVGIO_BACKGROUND,&IID_IDispatch,(void **)&item); if (FAILED(hr)) goto done;
    hr=IDispatch_QueryInterface(item,&IID_IShellFolderViewDual,(void **)&folder); if (FAILED(hr)) goto done;
    hr=IShellFolderViewDual_get_Application(folder,&application); if (FAILED(hr)) goto done;
    hr=IDispatch_QueryInterface(application,&IID_IShellDispatch2,(void **)&shell); if (FAILED(hr)) goto done;
    BSTR file=SysAllocString(path);
    VARIANT args={.vt=VT_BSTR,.bstrVal=SysAllocString(arguments)},cwd={.vt=VT_BSTR,.bstrVal=SysAllocString(directory)};
    VARIANT show={.vt=VT_I4,.lVal=SW_SHOWNORMAL};
    hr=file && args.bstrVal && cwd.bstrVal ? IShellDispatch2_ShellExecute(shell,file,args,cwd,empty,show) : E_OUTOFMEMORY;
    SysFreeString(file); VariantClear(&args); VariantClear(&cwd);
done:
    if (shell) IShellDispatch2_Release(shell);
    if (application) IDispatch_Release(application);
    if (folder) IShellFolderViewDual_Release(folder);
    if (item) IDispatch_Release(item);
    if (view) IShellView_Release(view);
    if (browser) IShellBrowser_Release(browser);
    if (services) IServiceProvider_Release(services);
    if (desktop) IDispatch_Release(desktop);
    if (windows) IShellWindows_Release(windows);
    return hr;
}
#endif
