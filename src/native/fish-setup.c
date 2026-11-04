#ifndef UNICODE
#define UNICODE 1
#endif
#define _UNICODE
#define COBJMACROS
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0A00
#endif
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <msi.h>
#include <tlhelp32.h>
#include <dwmapi.h>
#include <commctrl.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include "fish-setup-common.h"
#include "fish-setup-version.h"

/* Restrained Fish chrome; the real Windows Installer transaction remains per-user. */
#define WIDTH 620
#define HEIGHT 420
#define RESULT (WM_APP+1)
#define PHASE (WM_APP+2)
#define INSTALL 101
#define CANCEL 102
#define CHANGE 103
#define OPEN_APP 104
static COLORREF background=RGB(17,21,25),panel=RGB(25,31,37),text=RGB(229,234,240),
    muted=RGB(154,167,180),accent=RGB(123,188,203),divider=RGB(53,66,79);
static unsigned option_paints=0;
typedef struct { char magic[16]; ULONGLONG length; BYTE hash[32]; } Footer;
_Static_assert(sizeof(Footer)==56,"Payload footer layout");
static HWND window,folder,change,install,cancel,open_app;
static HFONT body_font,title_font;
static HBRUSH field_brush;
static UINT dpi=96;
static BOOL busy=FALSE,completed=FALSE,reboot=FALSE,updating=FALSE,registered_installation=FALSE;
static WCHAR destination[32768],data_directory[32768],status[512]=L"Ready to install for your Windows account.";
static DWORD parent_pid=0;
static HANDLE parent_process=NULL;
static HDC paint_dc=NULL;
static HBITMAP paint_image=NULL;
static HGDIOBJ paint_original=NULL;
static int paint_width=0,paint_height=0;
static ULONGLONG waiting_since;
static int pulse=0;
static int px(int value) { return MulDiv(value,(int)dpi,96); }

static void colors(BOOL force_system) {
    HIGHCONTRASTW high={0}; high.cbSize=sizeof(high);
    BOOL system=force_system || (SystemParametersInfoW(SPI_GETHIGHCONTRAST,sizeof(high),&high,0) && (high.dwFlags&HCF_HIGHCONTRASTON));
    background=system ? GetSysColor(COLOR_WINDOW) : RGB(17,21,25);
    panel=system ? GetSysColor(COLOR_WINDOW) : RGB(25,31,37);
    text=system ? GetSysColor(COLOR_WINDOWTEXT) : RGB(229,234,240);
    muted=system ? text : RGB(154,167,180);
    accent=system ? text : RGB(123,188,203);
    divider=system ? text : RGB(53,66,79);
    if (field_brush) DeleteObject(field_brush);
    field_brush=CreateSolidBrush(panel);
}
static void paint_option(HWND control,HDC dc) {
    RECT rect; GetClientRect(control,&rect);
    BOOL enabled=IsWindowEnabled(control),checked=SendMessageW(control,BM_GETCHECK,0,0)==BST_CHECKED;
    UINT state=(UINT)SendMessageW(control,BM_GETSTATE,0,0);
    HBRUSH brush=CreateSolidBrush(background); FillRect(dc,&rect,brush); DeleteObject(brush);
    RECT box={px(1),max(1,(rect.bottom-px(15))/2),px(16),max(1,(rect.bottom-px(15))/2)+px(15)};
    brush=CreateSolidBrush(panel); FillRect(dc,&box,brush); DeleteObject(brush);
    brush=CreateSolidBrush(enabled ? accent : muted); FrameRect(dc,&box,brush); DeleteObject(brush);
    if (checked) {
        HPEN pen=CreatePen(PS_SOLID,max(1,px(2)),enabled ? text : muted); HGDIOBJ old=SelectObject(dc,pen);
        MoveToEx(dc,box.left+px(3),box.top+px(7),NULL);
        LineTo(dc,box.left+px(6),box.top+px(10)); LineTo(dc,box.left+px(12),box.top+px(4));
        SelectObject(dc,old); DeleteObject(pen);
    }
    WCHAR value[160]; GetWindowTextW(control,value,160);
    RECT label=rect; label.left=px(25); label.right-=px(2);
    SelectObject(dc,body_font); SetBkMode(dc,TRANSPARENT); SetTextColor(dc,enabled ? text : muted);
    DrawTextW(dc,value,-1,&label,DT_LEFT|DT_VCENTER|DT_SINGLELINE);
    if ((state&BST_FOCUS) && !(SendMessageW(control,WM_QUERYUISTATE,0,0)&UISF_HIDEFOCUS)) DrawFocusRect(dc,&label);
    option_paints++;
}
static LRESULT CALLBACK option_procedure(HWND control,UINT message,WPARAM wp,LPARAM lp,UINT_PTR id,DWORD_PTR data) {
    (void)id; (void)data;
    if (message==WM_PAINT) {
        PAINTSTRUCT paint; HDC dc=BeginPaint(control,&paint); paint_option(control,dc); EndPaint(control,&paint); return 0;
    }
    if (message==WM_PRINTCLIENT) { paint_option(control,(HDC)wp); return 0; }
    if (message==WM_ERASEBKGND) return 1;
    if (message==WM_NCDESTROY) RemoveWindowSubclass(control,option_procedure,1);
    /* The native BUTTON still owns check state, Space, mnemonics, focus and UI Automation. */
    return DefSubclassProc(control,message,wp,lp);
}
static void label(HDC dc,const WCHAR *value,int x,int y,int width,int height,COLORREF color,HFONT font,UINT flags) {
    RECT rect={px(x),px(y),px(x+width),px(y+height)};
    SetTextColor(dc,color); SetBkMode(dc,TRANSPARENT); SelectObject(dc,font);
    DrawTextW(dc,value,-1,&rect,flags);
}
static void surface(HDC dc,int width,int height,BOOL preview) {
    RECT all={0,0,width,height}; HBRUSH brush=CreateSolidBrush(background); FillRect(dc,&all,brush); DeleteObject(brush);
    label(dc,L"Fish",28,22,300,42,text,title_font,DT_LEFT|DT_SINGLELINE);
    label(dc,L"5250 workspace  /  " FISH_VERSION,29,67,550,22,muted,body_font,DT_LEFT|DT_SINGLELINE);
    label(dc,L"For your Windows account. No administrator access needed.",29,106,555,24,text,body_font,DT_LEFT|DT_SINGLELINE);
    label(dc,L"Install to",29,147,550,22,muted,body_font,DT_LEFT|DT_SINGLELINE);
    label(dc,L"Profiles, passwords and preferences stay as they are.",29,250,560,24,muted,body_font,DT_LEFT|DT_SINGLELINE);
    label(dc,L"Back up workspace.xml before upgrading: older Fish cannot read format 13.",29,277,560,36,muted,body_font,DT_LEFT|DT_WORDBREAK);
    label(dc,status,29,321,560,45,text,body_font,DT_LEFT|DT_WORDBREAK);
    HPEN pen=CreatePen(PS_SOLID,1,divider); HGDIOBJ old=SelectObject(dc,pen);
    MoveToEx(dc,px(0),height-px(54),NULL); LineTo(dc,width,height-px(54)); SelectObject(dc,old); DeleteObject(pen);
    if (busy) {
        RECT track={px(29),px(309),px(590),px(311)};
        brush=CreateSolidBrush(divider); FillRect(dc,&track,brush); DeleteObject(brush);
        track.left+=px((pulse%45)*10); track.right=min(px(590),track.left+px(100));
        brush=CreateSolidBrush(accent); FillRect(dc,&track,brush); DeleteObject(brush);
    }
    if (preview) {
        RECT input={px(29),px(175),px(489),px(207)};
        brush=CreateSolidBrush(panel); FillRect(dc,&input,brush); DeleteObject(brush);
        label(dc,L"%LOCALAPPDATA%\\Fish",38,181,440,22,text,body_font,DT_LEFT|DT_SINGLELINE);
        label(dc,L"Change...",501,181,90,25,accent,body_font,DT_CENTER|DT_SINGLELINE);
        if (open_app) {
            POINT previous; SetViewportOrgEx(dc,px(29),px(217),&previous);
            SendMessageW(open_app,WM_PRINTCLIENT,(WPARAM)dc,PRF_CLIENT);
            SetViewportOrgEx(dc,previous.x,previous.y,NULL);
        }
        label(dc,L"Cancel",392,382,95,24,text,body_font,DT_CENTER|DT_SINGLELINE);
        label(dc,L"Install",495,382,95,24,accent,body_font,DT_CENTER|DT_SINGLELINE);
    }
}
static void fonts(void) {
    if (body_font) DeleteObject(body_font);
    if (title_font) DeleteObject(title_font);
    body_font=CreateFontW(-px(13),0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
    title_font=CreateFontW(-px(30),0,0,0,FW_SEMIBOLD,FALSE,FALSE,FALSE,DEFAULT_CHARSET,OUT_DEFAULT_PRECIS,
        CLIP_DEFAULT_PRECIS,CLEARTYPE_QUALITY,DEFAULT_PITCH,L"Segoe UI");
}
static void position(void) {
    MoveWindow(folder,px(29),px(175),px(460),px(32),TRUE);
    MoveWindow(change,px(497),px(175),px(94),px(32),TRUE);
    MoveWindow(open_app,px(29),px(217),px(540),px(27),TRUE);
    MoveWindow(cancel,px(389),px(374),px(98),px(34),TRUE);
    MoveWindow(install,px(495),px(374),px(96),px(34),TRUE);
    for (HWND child=GetWindow(window,GW_CHILD);child;child=GetWindow(child,GW_HWNDNEXT))
        SendMessageW(child,WM_SETFONT,(WPARAM)body_font,TRUE);
}
static BOOL payload(HANDLE input,Footer *footer,ULONGLONG *offset) {
    LARGE_INTEGER size,position; DWORD got;
    if (!GetFileSizeEx(input,&size) || size.QuadPart<(LONGLONG)sizeof(*footer)) return FALSE;
    position.QuadPart=size.QuadPart-sizeof(*footer);
    if (!SetFilePointerEx(input,position,NULL,FILE_BEGIN) || !ReadFile(input,footer,sizeof(*footer),&got,NULL)
            || got!=sizeof(*footer) || memcmp(footer->magic,"FishMsiPayload1",16)
            || footer->length<1024 || footer->length>128ULL*1024*1024
            || footer->length>(ULONGLONG)(size.QuadPart-sizeof(*footer))) return FALSE;
    *offset=(ULONGLONG)size.QuadPart-sizeof(*footer)-footer->length;
    BYTE actual[32];
    return fish_hash(input,*offset,footer->length,actual) && !memcmp(actual,footer->hash,32);
}
static BOOL extract(WCHAR *directory,WCHAR *msi,HANDLE *verified) {
    WCHAR self[32768],temp[MAX_PATH],guid_text[40]; GUID guid;
    DWORD length=GetModuleFileNameW(NULL,self,32768);
    if (!length || length>=32768 || !GetTempPathW(MAX_PATH,temp) || FAILED(CoCreateGuid(&guid))) return FALSE;
    StringFromGUID2(&guid,guid_text,40);
    swprintf(directory,32768,L"%lsFish-Setup-%ls",temp,guid_text);
    if (!CreateDirectoryW(directory,NULL)) return FALSE;
    swprintf(msi,32768,L"%ls\\Fish-" FISH_VERSION L".msi",directory);
    HANDLE input=CreateFileW(self,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
    if (input==INVALID_HANDLE_VALUE) return FALSE;
    Footer footer; ULONGLONG offset; BOOL success=payload(input,&footer,&offset);
    HANDLE output=INVALID_HANDLE_VALUE;
    if (success) output=CreateFileW(msi,GENERIC_WRITE,0,NULL,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,NULL);
    success=success && output!=INVALID_HANDLE_VALUE;
    if (success) {
        LARGE_INTEGER position; position.QuadPart=(LONGLONG)offset;
        success=SetFilePointerEx(input,position,NULL,FILE_BEGIN);
        BYTE buffer[65536]; ULONGLONG remaining=footer.length;
        while (success && remaining) {
            DWORD got=0,wrote=0,want=(DWORD)min(remaining,sizeof(buffer));
            success=ReadFile(input,buffer,want,&got,NULL) && got && WriteFile(output,buffer,got,&wrote,NULL) && got==wrote;
            remaining-=got;
        }
        if (success) success=FlushFileBuffers(output);
    }
    if (output!=INVALID_HANDLE_VALUE) CloseHandle(output);
    if (success) {
        output=CreateFileW(msi,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,NULL);
        BY_HANDLE_FILE_INFORMATION info; LARGE_INTEGER size; BYTE actual[32];
        success=output!=INVALID_HANDLE_VALUE && GetFileInformationByHandle(output,&info)
            && !(info.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT) && GetFileSizeEx(output,&size)
            && (ULONGLONG)size.QuadPart==footer.length && fish_hash(output,0,footer.length,actual)
            && !memcmp(actual,footer.hash,32);
        if (success) *verified=output;
        else if (output!=INVALID_HANDLE_VALUE) CloseHandle(output);
    }
    CloseHandle(input); return success;
}
static BOOL running_target(const WCHAR *target) {
    HANDLE snapshot=CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS,0);
    if (snapshot==INVALID_HANDLE_VALUE) return TRUE;
    PROCESSENTRY32W entry={0}; entry.dwSize=sizeof(entry);
    BOOL found=FALSE;
    if (Process32FirstW(snapshot,&entry)) do {
        if (_wcsicmp(entry.szExeFile,L"Fish.exe")) continue;
        HANDLE process=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,entry.th32ProcessID);
        if (process) {
            WCHAR path[32768]; DWORD size=32768;
            if (!QueryFullProcessImageNameW(process,0,path,&size) || !_wcsicmp(path,target)) found=TRUE;
            CloseHandle(process);
        } else found=TRUE;
    } while (!found && Process32NextW(snapshot,&entry));
    CloseHandle(snapshot); return found;
}
static BOOL directory_ok(const WCHAR *path) {
    if (wcslen(path)<4 || wcslen(path)>32700 || path[1]!=L':' || path[2]!=L'\\') return FALSE;
    WCHAR search[32768],fish[32768];
    swprintf(search,32768,L"%ls\\*",path); swprintf(fish,32768,L"%ls\\Fish.exe",path);
    if (GetFileAttributesW(fish)!=INVALID_FILE_ATTRIBUTES) return TRUE;
    WIN32_FIND_DATAW entry;
    HANDLE found=FindFirstFileW(search,&entry);
    if (found==INVALID_HANDLE_VALUE) return GetLastError()==ERROR_FILE_NOT_FOUND || GetLastError()==ERROR_PATH_NOT_FOUND;
    BOOL empty=TRUE;
    do { if (wcscmp(entry.cFileName,L".") && wcscmp(entry.cFileName,L"..")) { empty=FALSE; break; } } while (FindNextFileW(found,&entry));
    FindClose(found); return empty;
}
static void normalize_destination(void) {
    size_t size=wcslen(destination);
    while (size>3 && destination[size-1]==L'\\') destination[--size]=0;
}
static BOOL unused_location(const WCHAR *base) {
    if (wcslen(base)>32600) return FALSE;
    for (int attempt=0;attempt<32;attempt++) {
        if (!attempt) swprintf(destination,32768,L"%ls\\Fish",base);
        else if (attempt==1) swprintf(destination,32768,L"%ls\\Fish-installed",base);
        else swprintf(destination,32768,L"%ls\\Fish-installed-%d",base,attempt);
        if (GetFileAttributesW(destination)==INVALID_FILE_ATTRIBUTES) {
            DWORD error=GetLastError();
            if (error==ERROR_FILE_NOT_FOUND || error==ERROR_PATH_NOT_FOUND) return TRUE;
            return FALSE;
        }
    }
    return FALSE;
}
static BOOL location_test(void) {
    WCHAR temp[MAX_PATH],root[MAX_PATH],first[MAX_PATH],second[MAX_PATH],guid_text[40]; GUID guid;
    if (!GetTempPathW(MAX_PATH,temp) || FAILED(CoCreateGuid(&guid))) return FALSE;
    StringFromGUID2(&guid,guid_text,40);
    if (wcslen(temp)+wcslen(guid_text)+30>=MAX_PATH) return FALSE;
    swprintf(root,MAX_PATH,L"%lsFish-setup-fixture-%ls",temp,guid_text);
    if (!CreateDirectoryW(root,NULL)) return FALSE;
    swprintf(first,MAX_PATH,L"%ls\\Fish",root); swprintf(second,MAX_PATH,L"%ls\\Fish-installed",root);
    BOOL valid=CreateDirectoryW(first,NULL) && CreateDirectoryW(second,NULL) && unused_location(root);
    if (valid) {
        WCHAR expected[MAX_PATH]; swprintf(expected,MAX_PATH,L"%ls\\Fish-installed-2",root);
        valid=!wcscmp(destination,expected);
    }
    RemoveDirectoryW(second); RemoveDirectoryW(first); RemoveDirectoryW(root); return valid;
}
static BOOL version_ok(const WCHAR *path) {
    DWORD ignored=0,size=GetFileVersionInfoSizeW(path,&ignored);
    void *info=size ? HeapAlloc(GetProcessHeap(),0,size) : NULL;
    if (!info) return FALSE;
    VS_FIXEDFILEINFO *fixed=NULL; UINT length=0;
    BOOL result=GetFileVersionInfoW(path,0,size,info) && VerQueryValueW(info,L"\\",(void **)&fixed,&length) && length>=sizeof(*fixed);
    if (result) {
        WCHAR value[40]; swprintf(value,40,L"%u.%u.%u",HIWORD(fixed->dwProductVersionMS),LOWORD(fixed->dwProductVersionMS),HIWORD(fixed->dwProductVersionLS));
        result=!wcscmp(value,FISH_VERSION);
    }
    HeapFree(GetProcessHeap(),0,info); return result;
}
static DWORD WINAPI install_worker(void *unused) {
    (void)unused;
    WCHAR *memory=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,4*32768*sizeof(WCHAR));
    if (!memory) { PostMessageW(window,RESULT,ERROR_OUTOFMEMORY,0); return 0; }
    WCHAR *directory=memory,*msi=memory+32768,*command=memory+65536,*target=memory+98304;
    swprintf(target,32768,L"%ls\\Fish.exe",destination);
    DWORD result=ERROR_INSTALL_FAILURE; HANDLE verified=INVALID_HANDLE_VALUE;
    if (running_target(target)) result=ERROR_INSTALL_ALREADY_RUNNING;
    else if (!directory_ok(destination)) result=ERROR_DIR_NOT_EMPTY;
    else if (extract(directory,msi,&verified)) {
        PostMessageW(window,PHASE,1,0);
        WCHAR system[MAX_PATH]; UINT count=GetSystemDirectoryW(system,MAX_PATH);
        if (count && count<MAX_PATH) {
            WCHAR program[MAX_PATH]; swprintf(program,MAX_PATH,L"%ls\\msiexec.exe",system);
            BOOL valid=fish_quote(command,32768,program);
            if (valid) wcscat(command,L" /i ");
            valid=valid && fish_quote(command,32768,msi);
            if (valid && wcslen(command)+100<32768) wcscat(command,L" /qn /norestart MSIRESTARTMANAGERCONTROL=Disable INSTALLDIR=");
            else valid=FALSE;
            valid=valid && fish_quote(command,32768,destination);
            STARTUPINFOW startup={0}; startup.cb=sizeof(startup); PROCESS_INFORMATION process={0};
            if (valid && CreateProcessW(program,command,NULL,NULL,FALSE,CREATE_NO_WINDOW,NULL,NULL,&startup,&process)) {
                if (WaitForSingleObject(process.hProcess,INFINITE)!=WAIT_OBJECT_0 || !GetExitCodeProcess(process.hProcess,&result))
                    result=GetLastError();
                CloseHandle(process.hThread); CloseHandle(process.hProcess);
                if (result==ERROR_SUCCESS && !version_ok(target)) result=ERROR_PRODUCT_VERSION;
            } else result=valid ? GetLastError() : ERROR_INVALID_PARAMETER;
        }
    }
    if (verified!=INVALID_HANDLE_VALUE) CloseHandle(verified);
    if (*msi) DeleteFileW(msi);
    if (*directory) RemoveDirectoryW(directory);
    HeapFree(GetProcessHeap(),0,memory);
    PostMessageW(window,RESULT,result,0); return 0;
}
static void start_install(void) {
    if (busy) return;
    GetWindowTextW(folder,destination,32768);
    normalize_destination();
    busy=TRUE;
    wcscpy(status,L"Verifying and preparing the bundled installer...");
    EnableWindow(install,FALSE); EnableWindow(cancel,FALSE); EnableWindow(change,FALSE); EnableWindow(open_app,FALSE);
    HANDLE worker=CreateThread(NULL,0,install_worker,NULL,0,NULL);
    if (worker) CloseHandle(worker);
    else { busy=FALSE; wcscpy(status,L"Setup could not start. Close and try again."); EnableWindow(cancel,TRUE); EnableWindow(install,TRUE); }
    InvalidateRect(window,NULL,FALSE);
}
static void launch_app(void) {
    WCHAR *command=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,32768*sizeof(WCHAR));
    WCHAR *path=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,32768*sizeof(WCHAR));
    if (!command || !path) goto failed;
    swprintf(path,32768,L"%ls\\Fish.exe",destination);
    BOOL valid=version_ok(path) && fish_quote(command,32768,path);
    if (*data_directory && valid) {
        valid=wcslen(command)+13<32768;
        if (valid) { wcscat(command,L" --data-dir "); valid=fish_quote(command,32768,data_directory); }
    }
    STARTUPINFOW startup={0}; startup.cb=sizeof(startup); PROCESS_INFORMATION process={0};
    if (valid && CreateProcessW(path,command,NULL,NULL,FALSE,0,NULL,destination,&startup,&process)) {
        CloseHandle(process.hThread); CloseHandle(process.hProcess);
        HeapFree(GetProcessHeap(),0,command); HeapFree(GetProcessHeap(),0,path); return;
    }
failed:
    if (command) HeapFree(GetProcessHeap(),0,command);
    if (path) HeapFree(GetProcessHeap(),0,path);
    wcscpy(status,L"Installed, but Fish could not open. Use its Start menu shortcut.");
}
static void browse(void) {
    IFileDialog *picker=NULL;
    if (SUCCEEDED(CoCreateInstance(&CLSID_FileOpenDialog,NULL,CLSCTX_INPROC_SERVER,&IID_IFileDialog,(void **)&picker))) {
        IFileDialog_SetOptions(picker,FOS_PICKFOLDERS|FOS_FORCEFILESYSTEM|FOS_PATHMUSTEXIST);
        IFileDialog_SetTitle(picker,L"Choose an empty folder or the existing Fish installation");
        if (SUCCEEDED(IFileDialog_Show(picker,window))) {
            IShellItem *item=NULL;
            if (SUCCEEDED(IFileDialog_GetResult(picker,&item))) {
                PWSTR path=NULL;
                if (SUCCEEDED(IShellItem_GetDisplayName(item,SIGDN_FILESYSPATH,&path))) {
                    if (directory_ok(path)) {
                        wcscpy(destination,path); normalize_destination(); SetWindowTextW(folder,destination);
                    }
                    else MessageBoxW(window,L"Choose an empty local folder or an existing Fish installation.",L"Install location",MB_OK|MB_ICONINFORMATION);
                    CoTaskMemFree(path);
                }
                IShellItem_Release(item);
            }
        }
        IFileDialog_Release(picker);
    }
}
static void installed_location(void) {
    PWSTR local=NULL;
    if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_LocalAppData,0,NULL,&local))) {
        swprintf(destination,32768,L"%ls\\Fish",local); CoTaskMemFree(local);
    }
    WCHAR product[39]; DWORD index=0;
    while (MsiEnumRelatedProductsW(L"{55B0674D-E226-46CB-852F-6BEDDD35A74B}",0,index++,product)==ERROR_SUCCESS) {
        DWORD length=32767; WCHAR location[32768];
        if (MsiGetProductInfoW(product,INSTALLPROPERTY_INSTALLLOCATION,location,&length)==ERROR_SUCCESS && *location) {
            wcscpy(destination,location); registered_installation=TRUE; break;
        }
    }
    if (updating && !registered_installation) {
        if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_LocalAppData,0,NULL,&local))) {
            if (!unused_location(local)) *destination=0;
            CoTaskMemFree(local);
        } else *destination=0;
    }
    normalize_destination();
}
static LRESULT CALLBACK procedure(HWND hwnd,UINT message,WPARAM wp,LPARAM lp) {
    switch (message) {
        case WM_CREATE: {
            folder=CreateWindowExW(0,L"EDIT",destination,WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL|ES_READONLY,0,0,0,0,hwnd,NULL,NULL,NULL);
            change=CreateWindowW(L"BUTTON",L"Change...",WS_CHILD|WS_VISIBLE|WS_TABSTOP|BS_OWNERDRAW,0,0,0,0,hwnd,(HMENU)CHANGE,NULL,NULL);
            open_app=CreateWindowW(L"BUTTON",L"Open Fish when setup finishes",WS_CHILD|WS_VISIBLE|WS_TABSTOP|BS_AUTOCHECKBOX,0,0,0,0,hwnd,(HMENU)OPEN_APP,NULL,NULL);
            if (!open_app || !SetWindowSubclass(open_app,option_procedure,1,0)) return -1;
            SendMessageW(open_app,BM_SETCHECK,BST_CHECKED,0);
            cancel=CreateWindowW(L"BUTTON",L"Cancel",WS_CHILD|WS_VISIBLE|WS_TABSTOP|BS_OWNERDRAW,0,0,0,0,hwnd,(HMENU)CANCEL,NULL,NULL);
            install=CreateWindowW(L"BUTTON",L"Install",WS_CHILD|WS_VISIBLE|WS_TABSTOP|BS_OWNERDRAW,0,0,0,0,hwnd,(HMENU)INSTALL,NULL,NULL);
            SetTimer(hwnd,1,90,NULL); return 0;
        }
        case WM_CTLCOLORSTATIC:
        case WM_CTLCOLOREDIT: SetTextColor((HDC)wp,text); SetBkColor((HDC)wp,panel); return (LRESULT)field_brush;
        case WM_SETTINGCHANGE:
        case WM_THEMECHANGED:
            colors(FALSE); RedrawWindow(hwnd,NULL,NULL,RDW_INVALIDATE|RDW_ALLCHILDREN); return 0;
        case WM_DRAWITEM: {
            DRAWITEMSTRUCT *item=(DRAWITEMSTRUCT *)lp;
            BOOL focus=(item->itemState&ODS_FOCUS)!=0,disabled=(item->itemState&ODS_DISABLED)!=0;
            HBRUSH brush=CreateSolidBrush((item->itemState&ODS_SELECTED) ? divider : panel);
            FillRect(item->hDC,&item->rcItem,brush); DeleteObject(brush);
            if (focus) { brush=CreateSolidBrush(accent); FrameRect(item->hDC,&item->rcItem,brush); DeleteObject(brush); }
            WCHAR value[80]; GetWindowTextW(item->hwndItem,value,80);
            SetTextColor(item->hDC,disabled ? muted : item->CtlID==INSTALL ? accent : text); SetBkMode(item->hDC,TRANSPARENT);
            SelectObject(item->hDC,body_font); DrawTextW(item->hDC,value,-1,&item->rcItem,DT_CENTER|DT_VCENTER|DT_SINGLELINE); return TRUE;
        }
        case WM_ERASEBKGND: return 1;
        case WM_PAINT: {
            PAINTSTRUCT paint; HDC dc=BeginPaint(hwnd,&paint); RECT rect; GetClientRect(hwnd,&rect);
            if (!paint_dc) paint_dc=CreateCompatibleDC(dc);
            if (paint_dc && (paint_width!=rect.right || paint_height!=rect.bottom)) {
                if (paint_image) { SelectObject(paint_dc,paint_original); DeleteObject(paint_image); paint_image=NULL; }
                paint_image=CreateCompatibleBitmap(dc,rect.right,rect.bottom);
                if (paint_image) {
                    paint_original=SelectObject(paint_dc,paint_image); paint_width=rect.right; paint_height=rect.bottom;
                }
            }
            surface(paint_image ? paint_dc : dc,rect.right,rect.bottom,FALSE);
            if (paint_image) BitBlt(dc,0,0,rect.right,rect.bottom,paint_dc,0,0,SRCCOPY);
            EndPaint(hwnd,&paint); return 0;
        }
        case WM_DPICHANGED: {
            dpi=HIWORD(wp); fonts(); RECT *rect=(RECT *)lp;
            SetWindowPos(hwnd,NULL,rect->left,rect->top,rect->right-rect->left,rect->bottom-rect->top,SWP_NOZORDER|SWP_NOACTIVATE);
            position(); return 0;
        }
        case WM_TIMER:
            if (parent_process) {
                DWORD wait=WaitForSingleObject(parent_process,0);
                if (wait==WAIT_OBJECT_0) {
                    CloseHandle(parent_process); parent_process=NULL; start_install();
                } else if (wait==WAIT_FAILED || GetTickCount64()-waiting_since>60000) {
                    CloseHandle(parent_process); parent_process=NULL;
                    wcscpy(status,L"Fish did not close. No files changed. Close it and press Install to retry."); EnableWindow(install,TRUE);
                }
            }
            if (busy) { pulse++; InvalidateRect(hwnd,NULL,FALSE); }
            return 0;
        case PHASE: wcscpy(status,L"Installing Fish and its private runtime..."); InvalidateRect(hwnd,NULL,FALSE); return 0;
        case RESULT:
            busy=FALSE; EnableWindow(cancel,TRUE);
            if (wp==ERROR_SUCCESS || wp==ERROR_SUCCESS_REBOOT_REQUIRED) {
                completed=TRUE; reboot=wp==ERROR_SUCCESS_REBOOT_REQUIRED;
                wcscpy(status,reboot ? L"Installed. Restart Windows before opening Fish." : L"Fish " FISH_VERSION L" is installed.");
                SetWindowTextW(cancel,L"Close"); SetWindowTextW(install,L"Open Fish"); EnableWindow(install,!reboot);
                if (!reboot && SendMessageW(open_app,BM_GETCHECK,0,0)==BST_CHECKED) launch_app();
            } else {
                if (wp==ERROR_INSTALL_ALREADY_RUNNING) wcscpy(status,L"Fish is running, or its install path could not be checked. Close that copy and Retry. No process was stopped.");
                else if (wp==ERROR_DIR_NOT_EMPTY) wcscpy(status,L"Choose an empty local folder or an existing Fish installation.");
                else swprintf(status,512,L"Setup failed (Windows code %lu). Nothing is reported updated. Close other installers and Retry.",(unsigned long)wp);
                SetWindowTextW(install,L"Retry"); EnableWindow(install,TRUE); EnableWindow(change,TRUE); EnableWindow(open_app,TRUE);
            }
            InvalidateRect(hwnd,NULL,FALSE); return 0;
        case WM_COMMAND:
            switch (LOWORD(wp)) {
                case INSTALL: if (completed) launch_app(); else start_install(); break;
                case CANCEL: SendMessageW(hwnd,WM_CLOSE,0,0); break;
                case CHANGE: browse(); break;
            }
            return 0;
        case WM_CLOSE: if (!busy) DestroyWindow(hwnd); return 0;
        case WM_DESTROY: KillTimer(hwnd,1); PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(hwnd,message,wp,lp);
}
static BOOL render(const WCHAR *path) {
    dpi=96; fonts(); position();
    BITMAPINFO info={0}; info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER); info.bmiHeader.biWidth=WIDTH; info.bmiHeader.biHeight=-HEIGHT;
    info.bmiHeader.biPlanes=1; info.bmiHeader.biBitCount=32; info.bmiHeader.biCompression=BI_RGB;
    void *pixels=NULL; HDC dc=CreateCompatibleDC(NULL); HBITMAP image=CreateDIBSection(dc,&info,DIB_RGB_COLORS,&pixels,NULL,0);
    if (!image || !pixels) { if (image) DeleteObject(image); DeleteDC(dc); return FALSE; }
    HGDIOBJ old=SelectObject(dc,image); surface(dc,WIDTH,HEIGHT,TRUE);
    HANDLE file=CreateFileW(path,GENERIC_WRITE,0,NULL,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,NULL);
    BOOL result=FALSE;
    if (file!=INVALID_HANDLE_VALUE) {
        BITMAPFILEHEADER header={0}; DWORD got=0;
        header.bfType=0x4D42; header.bfOffBits=sizeof(header)+sizeof(BITMAPINFOHEADER); header.bfSize=header.bfOffBits+WIDTH*HEIGHT*4;
        result=WriteFile(file,&header,sizeof(header),&got,NULL) && got==sizeof(header)
            && WriteFile(file,&info.bmiHeader,sizeof(BITMAPINFOHEADER),&got,NULL) && got==sizeof(BITMAPINFOHEADER)
            && WriteFile(file,pixels,WIDTH*HEIGHT*4,&got,NULL) && got==WIDTH*HEIGHT*4;
        CloseHandle(file);
    }
    SelectObject(dc,old); DeleteObject(image); DeleteDC(dc); DeleteObject(body_font); DeleteObject(title_font);
    return result;
}
static double luminance(COLORREF color) {
    double values[3]={GetRValue(color)/255.0,GetGValue(color)/255.0,GetBValue(color)/255.0};
    for (int i=0;i<3;i++) values[i]=values[i]<=0.04045 ? values[i]/12.92 : pow((values[i]+0.055)/1.055,2.4);
    return 0.2126*values[0]+0.7152*values[1]+0.0722*values[2];
}
static BOOL option_test_size(void) {
    HDC dc=CreateCompatibleDC(NULL);
    BITMAPINFO info={0}; info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth=px(540); info.bmiHeader.biHeight=-px(27); info.bmiHeader.biPlanes=1; info.bmiHeader.biBitCount=32;
    void *pixels=NULL; HBITMAP image=CreateDIBSection(dc,&info,DIB_RGB_COLORS,&pixels,NULL,0);
    if (!dc || !image || !pixels) { if (image) DeleteObject(image); if (dc) DeleteDC(dc); return FALSE; }
    HGDIOBJ previous=SelectObject(dc,image); BOOL valid=TRUE;
    for (int system=0;system<2;system++) {
        colors(system);
        for (int enabled=0;enabled<2;enabled++) for (int checked=0;checked<2;checked++) {
            EnableWindow(open_app,enabled); SendMessageW(open_app,BM_SETCHECK,checked ? BST_CHECKED : BST_UNCHECKED,0);
            SendMessageW(open_app,WM_MOUSEMOVE,0,MAKELPARAM(px(8),px(8)));
            SendMessageW(open_app,BM_SETSTATE,checked,0);
            unsigned before=option_paints; SendMessageW(open_app,WM_PRINTCLIENT,(WPARAM)dc,PRF_CLIENT);
            COLORREF foreground=enabled ? text : muted;
            double first=luminance(foreground),second=luminance(background);
            double ratio=(max(first,second)+0.05)/(min(first,second)+0.05);
            int ink=0;
            for (int y=0;y<px(27);y++) for (int x=px(25);x<px(500);x++) if (GetPixel(dc,x,y)==foreground) ink++;
            valid=valid && option_paints==before+1 && ratio>=4.5 && ink>25
                && SendMessageW(open_app,BM_GETCHECK,0,0)==(checked ? BST_CHECKED : BST_UNCHECKED);
        }
    }
    SendMessageW(open_app,BM_SETSTATE,FALSE,0);
    EnableWindow(open_app,TRUE); SendMessageW(open_app,BM_CLICK,0,0);
    valid=valid && SendMessageW(open_app,BM_GETCHECK,0,0)==BST_UNCHECKED;
    SelectObject(dc,previous); DeleteObject(image); DeleteDC(dc); colors(FALSE); return valid;
}
static BOOL option_test(void) {
    UINT saved=dpi; BOOL valid=TRUE;
    const UINT scales[]={96,120,144,168,192};
    for (unsigned i=0;i<sizeof(scales)/sizeof(scales[0]);i++) {
        dpi=scales[i]; fonts(); position();
        valid=option_test_size() && valid;
    }
    dpi=saved; fonts(); position(); return valid;
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,PWSTR command,int show) {
    (void)previous; (void)command;
    int count=0; LPWSTR *args=CommandLineToArgvW(GetCommandLineW(),&count);
    if (!args) return 2;
    if (count==2 && !wcscmp(args[1],L"--self-test")) {
        WCHAR quote[100]=L"";
        BOOL valid=fish_quote(quote,100,L"C:\\path with space\\") && !wcscmp(quote,L"\"C:\\path with space\\\\\"");
        valid=valid && !fish_quote(quote,4,L"too long") && location_test(); LocalFree(args); return valid ? 0 : 1;
    }
    if (count==2 && !wcscmp(args[1],L"--verify-payload")) {
        WCHAR self[32768]; GetModuleFileNameW(NULL,self,32768);
        HANDLE file=CreateFileW(self,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
        Footer footer; ULONGLONG offset; BOOL valid=file!=INVALID_HANDLE_VALUE && payload(file,&footer,&offset);
        if (file!=INVALID_HANDLE_VALUE) CloseHandle(file); LocalFree(args); return valid ? 0 : 1;
    }
    BOOL preview=count==3 && !wcscmp(args[1],L"--render-preview");
    BOOL controls=count==2 && !wcscmp(args[1],L"--test-controls");
    WCHAR preview_path[32768]=L"";
    if (preview) { if (wcslen(args[2])>=32768) { LocalFree(args); return 2; } wcscpy(preview_path,args[2]); }
    if (count==5 && !wcscmp(args[1],L"--update-pid") && !wcscmp(args[3],L"--data-dir")) {
        WCHAR *end=NULL; unsigned long pid=wcstoul(args[2],&end,10);
        if (!pid || *end || !*args[4] || wcslen(args[4])>=32700) { LocalFree(args); return 2; }
        parent_pid=(DWORD)pid; wcscpy(data_directory,args[4]); updating=TRUE;
    } else if (count!=1 && !preview && !controls) { LocalFree(args); return 2; }
    LocalFree(args); CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    if (!preview && !controls) installed_location();
    else wcscpy(destination,L"%LOCALAPPDATA%\\Fish");
    if (!*destination) { CoUninitialize(); return 3; }
    WNDCLASSEXW klass={0}; klass.cbSize=sizeof(klass); klass.lpfnWndProc=procedure; klass.hInstance=instance;
    klass.lpszClassName=L"FishSetup"; klass.hCursor=LoadCursorW(NULL,IDC_ARROW); klass.hIcon=LoadIconW(instance,MAKEINTRESOURCEW(1));
    RegisterClassExW(&klass); colors(FALSE);
    DWORD style=WS_OVERLAPPED|WS_CAPTION|WS_SYSMENU|WS_MINIMIZEBOX;
    window=CreateWindowExW(0,klass.lpszClassName,L"Fish setup",style,CW_USEDEFAULT,CW_USEDEFAULT,WIDTH,HEIGHT,NULL,NULL,instance,NULL);
    if (!window) { DeleteObject(field_brush); CoUninitialize(); return 4; }
    dpi=GetDpiForWindow(window); fonts();
    RECT rect={0,0,px(WIDTH),px(HEIGHT)}; AdjustWindowRectExForDpi(&rect,style,FALSE,0,dpi);
    SetWindowPos(window,NULL,0,0,rect.right-rect.left,rect.bottom-rect.top,SWP_NOMOVE|SWP_NOZORDER);
    BOOL dark=TRUE; DwmSetWindowAttribute(window,20,&dark,sizeof(dark)); position();
    if (preview || controls) {
        BOOL valid=controls ? option_test() : render(preview_path);
        DestroyWindow(window);
        if (field_brush) DeleteObject(field_brush);
        if (controls) { DeleteObject(body_font); DeleteObject(title_font); }
        CoUninitialize(); return valid ? 0 : 1;
    }
    if (updating) {
        parent_process=OpenProcess(SYNCHRONIZE|PROCESS_QUERY_LIMITED_INFORMATION,FALSE,parent_pid);
        if (parent_process) {
            WCHAR path[32768]; DWORD size=32768;
            if (!QueryFullProcessImageNameW(parent_process,0,path,&size) || !*path) {
                MessageBoxW(window,L"Fish's process identity could not be checked. No update was installed. Close Fish and run setup again.",L"Update not started",MB_OK|MB_ICONERROR);
                CloseHandle(parent_process); DestroyWindow(window); CoUninitialize(); return 3;
            }
            else {
                const WCHAR *name=wcsrchr(path,L'\\');
                if (!name || _wcsicmp(name+1,L"Fish.exe")) { CloseHandle(parent_process); DestroyWindow(window); CoUninitialize(); return 2; }
            }
        } else if (GetLastError()!=ERROR_INVALID_PARAMETER) { DestroyWindow(window); CoUninitialize(); return 3; }
        waiting_since=GetTickCount64(); wcscpy(status,L"Waiting for Fish to close; your workspace has been saved...");
        EnableWindow(install,FALSE); EnableWindow(change,FALSE);
        if (!parent_process) start_install();
    }
    ShowWindow(window,show); UpdateWindow(window);
    MSG message;
    while (GetMessageW(&message,NULL,0,0)>0) {
        if (message.message==WM_KEYDOWN && message.wParam==VK_RETURN) {
            HWND focused=GetFocus();
            if (focused==cancel || focused==change || focused==open_app || focused==install) SendMessageW(focused,BM_CLICK,0,0);
            else if (IsWindowEnabled(install)) SendMessageW(window,WM_COMMAND,INSTALL,0);
            continue;
        }
        if (message.message==WM_KEYDOWN && message.wParam==VK_ESCAPE && !busy) { SendMessageW(window,WM_CLOSE,0,0); continue; }
        if (!IsDialogMessageW(window,&message)) { TranslateMessage(&message); DispatchMessageW(&message); }
    }
    if (parent_process) CloseHandle(parent_process);
    if (paint_image) { SelectObject(paint_dc,paint_original); DeleteObject(paint_image); }
    if (paint_dc) DeleteDC(paint_dc);
    DeleteObject(body_font); DeleteObject(title_font); DeleteObject(field_brush); CoUninitialize();
    return 0;
}
