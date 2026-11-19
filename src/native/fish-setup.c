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
#include <objidl.h>
#include <msi.h>
#include <tlhelp32.h>
#include <dwmapi.h>
#include <commctrl.h>
#include <oleacc.h>
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include "fish-setup-common.h"
#include "fish-setup-version.h"

/* Restrained Fish chrome; supports per-user (no admin) by default and optional all-users (UAC) install. */
#define WIDTH 540
#define HEIGHT 336
#define RESULT (WM_APP+1)
#define PHASE (WM_APP+2)
#define PREPARING (WM_APP+3)
#define INSTALL 101
#define CANCEL 102
#define CHANGE 103
#define OPEN_APP 104
#define ALL_USERS 105
#define OPTIONS 106
#define MINIMIZE 107
#define FISH_UPGRADE L"{55B0674D-E226-46CB-852F-6BEDDD35A74B}"
static COLORREF background=RGB(17,21,25),panel=RGB(25,31,37),text=RGB(229,234,240),
    muted=RGB(154,167,180),accent=RGB(123,188,203),divider=RGB(53,66,79);
static unsigned option_paints=0;
typedef struct { char magic[16]; ULONGLONG length; BYTE hash[32]; } Footer;
_Static_assert(sizeof(Footer)==56,"Payload footer layout");
static HWND window,folder,change,install,cancel,open_app,all_users,options,minimize,description,progress_bar;
static HFONT body_font,title_font,medium_font;
static HBRUSH field_brush;
static IAccPropServices *accessible_properties;
static UINT dpi=96;
static BOOL busy=FALSE,completed=FALSE,reboot=FALSE,updating=FALSE,registered_installation=FALSE;
static BOOL system_wide_mode=FALSE,custom_folder_chosen=FALSE,elevated_autostart=FALSE,elevated_open_app=TRUE;
static WCHAR destination[32768],user_default_dest[32768],system_default_dest[32768],data_directory[32768];
static WCHAR status[512]=L"Ready to install for your Windows account.";
static DWORD parent_pid=0;
static HANDLE parent_process=NULL;
static HANDLE elevated_process=NULL;
static BOOL demo=FALSE,demo_error=FALSE,expanded=FALSE,issue=FALSE,fatal=FALSE,light=FALSE,contrast=FALSE;
static int theme_override=0,progress_value=-1;
static HDC paint_dc=NULL;
static HBITMAP paint_image=NULL;
static HGDIOBJ paint_original=NULL;
static int paint_width=0,paint_height=0;
static ULONGLONG waiting_since;
static unsigned pulse=0;
static int px(int value) { return MulDiv(value,(int)dpi,96); }

static void reset_art(void);
static void colors(BOOL force_system) {
    HIGHCONTRASTW high={0}; high.cbSize=sizeof(high);
    BOOL system=force_system || (SystemParametersInfoW(SPI_GETHIGHCONTRAST,sizeof(high),&high,0) && (high.dwFlags&HCF_HIGHCONTRASTON));
    DWORD apps_light=1,bytes=sizeof(apps_light);
    RegGetValueW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
        L"AppsUseLightTheme",RRF_RT_REG_DWORD,NULL,&apps_light,&bytes);
    light=theme_override ? theme_override>0 : apps_light!=0; contrast=system;
    background=system ? GetSysColor(COLOR_WINDOW) : light ? RGB(246,247,252) : RGB(31,33,38);
    panel=system ? background : light ? RGB(251,251,255) : RGB(43,46,54);
    text=system ? GetSysColor(COLOR_WINDOWTEXT) : light ? RGB(22,25,36) : RGB(248,249,252);
    muted=system ? text : light ? RGB(69,75,92) : RGB(196,202,214);
    accent=system ? GetSysColor(COLOR_HIGHLIGHT) : light ? RGB(79,67,161) : RGB(166,183,233);
    divider=system ? text : light ? RGB(155,162,183) : RGB(103,112,132);
    if (field_brush) DeleteObject(field_brush);
    field_brush=CreateSolidBrush(panel);
    reset_art();
}
static void control_ground(HWND,HDC);
static void paint_option(HWND control,HDC dc) {
    RECT rect; GetClientRect(control,&rect);
    BOOL enabled=IsWindowEnabled(control),checked=SendMessageW(control,BM_GETCHECK,0,0)==BST_CHECKED;
    UINT state=(UINT)SendMessageW(control,BM_GETSTATE,0,0);
    control_ground(control,dc);
    RECT box={px(1),max(1,(rect.bottom-px(15))/2),px(16),max(1,(rect.bottom-px(15))/2)+px(15)};
    HBRUSH brush=CreateSolidBrush(checked ? accent : panel); FillRect(dc,&box,brush); DeleteObject(brush);
    brush=CreateSolidBrush(enabled ? (checked ? accent : divider) : muted); FrameRect(dc,&box,brush); DeleteObject(brush);
    if (checked) {
        HPEN pen=CreatePen(PS_SOLID,max(1,px(2)),contrast ? GetSysColor(COLOR_HIGHLIGHTTEXT) : light ? RGB(255,255,255) : background);
        HGDIOBJ old=SelectObject(dc,pen);
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
#include "fish-setup-ui.h"
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
        BYTE buffer[65536]; ULONGLONG remaining=footer.length; int last=-1;
        while (success && remaining) {
            DWORD got=0,wrote=0,want=(DWORD)min(remaining,sizeof(buffer));
            success=ReadFile(input,buffer,want,&got,NULL) && got && WriteFile(output,buffer,got,&wrote,NULL) && got==wrote;
            remaining-=got;
            int percent=(int)((footer.length-remaining)*100/footer.length);
            if (window && success && percent!=last) { PostMessageW(window,PREPARING,percent,0); last=percent; }
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
static void delete_tree(const WCHAR *dir) {
    if (!dir || !*dir) return;
    DWORD root_attr=GetFileAttributesW(dir);
    if (root_attr==INVALID_FILE_ATTRIBUTES) return;
    if (!(root_attr&FILE_ATTRIBUTE_DIRECTORY)) {
        if (root_attr&FILE_ATTRIBUTE_READONLY) SetFileAttributesW(dir,root_attr&~FILE_ATTRIBUTE_READONLY);
        DeleteFileW(dir); return;
    }
    if (!(root_attr&FILE_ATTRIBUTE_REPARSE_POINT)) {
        WCHAR *pattern=HeapAlloc(GetProcessHeap(),0,32768*sizeof(WCHAR));
        WCHAR *child=HeapAlloc(GetProcessHeap(),0,32768*sizeof(WCHAR));
        if (pattern && child && wcslen(dir)+6<32768) {
            swprintf(pattern,32768,L"%ls\\*",dir);
            WIN32_FIND_DATAW entry; HANDLE search=FindFirstFileW(pattern,&entry);
            if (search!=INVALID_HANDLE_VALUE) {
                do {
                    if (!wcscmp(entry.cFileName,L".") || !wcscmp(entry.cFileName,L"..")) continue;
                    if (wcslen(dir)+1+wcslen(entry.cFileName)>=32768) continue;
                    swprintf(child,32768,L"%ls\\%ls",dir,entry.cFileName);
                    if (entry.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY) delete_tree(child);
                    else {
                        if (entry.dwFileAttributes&FILE_ATTRIBUTE_READONLY)
                            SetFileAttributesW(child,entry.dwFileAttributes&~FILE_ATTRIBUTE_READONLY);
                        DeleteFileW(child);
                    }
                } while (FindNextFileW(search,&entry));
                FindClose(search);
            }
        }
        if (pattern) HeapFree(GetProcessHeap(),0,pattern);
        if (child) HeapFree(GetProcessHeap(),0,child);
    }
    if (root_attr&FILE_ATTRIBUTE_READONLY) SetFileAttributesW(dir,root_attr&~FILE_ATTRIBUTE_READONLY);
    RemoveDirectoryW(dir);
}
static void clean_fish_install_dir(const WCHAR *dir,BOOL remove_root) {
    if (!dir || !*dir) return;
    WCHAR path[MAX_PATH];
    if (wcslen(dir)+32>=MAX_PATH) return;
    swprintf(path,MAX_PATH,L"%ls\\Fish.exe",dir);
    if (GetFileAttributesW(path)==INVALID_FILE_ATTRIBUTES || running_target(path)) return;
    swprintf(path,MAX_PATH,L"%ls\\app",dir); delete_tree(path);
    swprintf(path,MAX_PATH,L"%ls\\runtime",dir); delete_tree(path);
    if (!remove_root) return;
    static const WCHAR *const owned[]={L"Fish.exe",L"Fish.ico",L"Fish-config.cmd",L"Install-Fish.ps1",L"Uninstall-Fish.ps1"};
    for (unsigned i=0;i<sizeof(owned)/sizeof(owned[0]);i++) {
        swprintf(path,MAX_PATH,L"%ls\\%ls",dir,owned[i]);
        DWORD a=GetFileAttributesW(path);
        if (a!=INVALID_FILE_ATTRIBUTES && !(a&FILE_ATTRIBUTE_DIRECTORY)) {
            if (a&FILE_ATTRIBUTE_READONLY) SetFileAttributesW(path,a&~FILE_ATTRIBUTE_READONLY);
            DeleteFileW(path);
        }
    }
    RemoveDirectoryW(dir);
}
static void trim_trailing_slash(WCHAR *s) {
    size_t n=wcslen(s);
    while (n>3 && (s[n-1]==L'\\' || s[n-1]==L'/')) s[--n]=0;
}
static void remove_conflicting_msi_products(const WCHAR *msiexec_path,const WCHAR *dest_dir,WCHAR *cmd_buf) {
    WCHAR products[16][39]; unsigned found=0;
    for (DWORD idx=0;idx<16;idx++) {
        if (MsiEnumRelatedProductsW(FISH_UPGRADE,0,idx,products[found])!=ERROR_SUCCESS) break;
        found++;
    }
    BOOL admin=IsUserAnAdmin();
    for (unsigned i=0;i<found;i++) {
        WCHAR assign[16]=L"",loc[MAX_PATH]=L"";
        DWORD assign_len=16,loc_len=MAX_PATH;
        MsiGetProductInfoW(products[i],INSTALLPROPERTY_ASSIGNMENTTYPE,assign,&assign_len);
        MsiGetProductInfoW(products[i],INSTALLPROPERTY_INSTALLLOCATION,loc,&loc_len);
        trim_trailing_slash(loc);
        BOOL machine_assigned=!wcscmp(assign,L"1");
        if (machine_assigned && !admin) continue;
        BOOL same_scope=system_wide_mode ? machine_assigned : !machine_assigned;
        BOOL diff_loc=loc[0] && _wcsicmp(loc,dest_dir)!=0;
        if (!same_scope || diff_loc) {
            cmd_buf[0]=0;
            BOOL ok=fish_quote(cmd_buf,32768,msiexec_path);
            if (ok && wcslen(cmd_buf)+180<32768) {
                wcscat(cmd_buf,L" /x "); wcscat(cmd_buf,products[i]);
                wcscat(cmd_buf,L" /qn /norestart MSIRESTARTMANAGERCONTROL=Disable UPGRADINGPRODUCTCODE=");
                wcscat(cmd_buf,FISH_UPGRADE);
                STARTUPINFOW si={0}; si.cb=sizeof(si); PROCESS_INFORMATION pi={0};
                if (CreateProcessW(msiexec_path,cmd_buf,NULL,NULL,FALSE,CREATE_NO_WINDOW,NULL,NULL,&si,&pi)) {
                    WaitForSingleObject(pi.hProcess,INFINITE);
                    CloseHandle(pi.hThread); CloseHandle(pi.hProcess);
                }
            }
            if (diff_loc) clean_fish_install_dir(loc,TRUE);
        }
    }
}
static void update_existing_shortcut(const WCHAR *lnk_path,const WCHAR *exe_path) {
    DWORD a=GetFileAttributesW(lnk_path);
    if (a==INVALID_FILE_ATTRIBUTES || (a&FILE_ATTRIBUTE_DIRECTORY)) return;
    IShellLinkW *sl=NULL;
    if (FAILED(CoCreateInstance(&CLSID_ShellLink,NULL,CLSCTX_INPROC_SERVER,&IID_IShellLinkW,(void **)&sl))) return;
    WCHAR work[MAX_PATH]=L""; wcsncpy(work,exe_path,MAX_PATH-1);
    WCHAR *slash=wcsrchr(work,L'\\'); if (slash && slash>work+2) *slash=0;
    IShellLinkW_SetPath(sl,exe_path);
    IShellLinkW_SetWorkingDirectory(sl,work);
    IShellLinkW_SetIconLocation(sl,exe_path,0);
    IShellLinkW_SetDescription(sl,L"Fish 5250 Workspace");
    IPersistFile *pf=NULL;
    if (SUCCEEDED(IShellLinkW_QueryInterface(sl,&IID_IPersistFile,(void **)&pf))) {
        if (SUCCEEDED(IPersistFile_Save(pf,lnk_path,TRUE))) {
            IPersistFile_SaveCompleted(pf,lnk_path);
            SetFileAttributesW(lnk_path,FILE_ATTRIBUTE_ARCHIVE);
            SHChangeNotify(SHCNE_UPDATEITEM,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,lnk_path,NULL);
            WCHAR parent[MAX_PATH]=L""; wcsncpy(parent,lnk_path,MAX_PATH-1);
            WCHAR *ps=wcsrchr(parent,L'\\');
            if (ps) { *ps=0; SHChangeNotify(SHCNE_UPDATEDIR,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,parent,NULL); }
        }
        IPersistFile_Release(pf);
    }
    IShellLinkW_Release(sl);
}
static void reconcile_shortcuts_in_known_folder(const GUID *folder_id,BOOL is_programs,const WCHAR *exe_path) {
    PWSTR base=NULL;
    if (FAILED(SHGetKnownFolderPath(folder_id,0,NULL,&base)) || !base) return;
    WCHAR path[MAX_PATH];
    if (wcslen(base)+32<MAX_PATH) {
        if (is_programs) {
            /* MSI installs Start Menu\Programs\Fish\Fish.lnk; remove duplicate flat Programs\Fish.lnk if present. */
            swprintf(path,MAX_PATH,L"%ls\\Fish.lnk",base);
            DWORD a=GetFileAttributesW(path);
            if (a!=INVALID_FILE_ATTRIBUTES && !(a&FILE_ATTRIBUTE_DIRECTORY)) {
                if (a&FILE_ATTRIBUTE_READONLY) SetFileAttributesW(path,a&~FILE_ATTRIBUTE_READONLY);
                if (DeleteFileW(path)) SHChangeNotify(SHCNE_DELETE,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,path,NULL);
            }
            swprintf(path,MAX_PATH,L"%ls\\Fish\\Fish.lnk",base);
            if (GetFileAttributesW(path)!=INVALID_FILE_ATTRIBUTES)
                SHChangeNotify(SHCNE_UPDATEITEM,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,path,NULL);
            swprintf(path,MAX_PATH,L"%ls\\Fish",base);
            if (GetFileAttributesW(path)!=INVALID_FILE_ATTRIBUTES)
                SHChangeNotify(SHCNE_UPDATEDIR,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,path,NULL);
            SHChangeNotify(SHCNE_UPDATEDIR,SHCNF_PATHW|SHCNF_FLUSHNOWAIT,base,NULL);
        } else {
            swprintf(path,MAX_PATH,L"%ls\\Fish.lnk",base);
            update_existing_shortcut(path,exe_path);
        }
    }
    CoTaskMemFree(base);
}
static void reconcile_post_install(const WCHAR *installed_exe,const WCHAR *dest_dir) {
    PWSTR local=NULL;
    if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_LocalAppData,0,NULL,&local)) && local) {
        WCHAR alt[MAX_PATH];
        if (wcslen(local)+24<MAX_PATH) {
            swprintf(alt,MAX_PATH,L"%ls\\Programs\\Fish",local);
            if (_wcsicmp(alt,dest_dir)!=0) clean_fish_install_dir(alt,TRUE);
            swprintf(alt,MAX_PATH,L"%ls\\Fish",local);
            if (_wcsicmp(alt,dest_dir)!=0) clean_fish_install_dir(alt,TRUE);
        }
        CoTaskMemFree(local);
    }
    /* Remove stale script-installer Uninstall key if it points elsewhere so Installed Apps has one clean entry. */
    HKEY key=NULL;
    if (RegOpenKeyExW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Fish",0,KEY_QUERY_VALUE,&key)==ERROR_SUCCESS) {
        WCHAR reg_loc[MAX_PATH]=L""; DWORD bytes=sizeof(reg_loc),type=0;
        LONG q=RegQueryValueExW(key,L"InstallLocation",NULL,&type,(BYTE *)reg_loc,&bytes);
        RegCloseKey(key);
        trim_trailing_slash(reg_loc);
        if (q!=ERROR_SUCCESS || _wcsicmp(reg_loc,dest_dir)!=0 || !system_wide_mode)
            RegDeleteTreeW(HKEY_CURRENT_USER,L"Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Fish");
    }
    HRESULT hr=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    reconcile_shortcuts_in_known_folder(&FOLDERID_Programs,TRUE,installed_exe);
    reconcile_shortcuts_in_known_folder(&FOLDERID_Desktop,FALSE,installed_exe);
    if (system_wide_mode) {
        reconcile_shortcuts_in_known_folder(&FOLDERID_CommonPrograms,TRUE,installed_exe);
        reconcile_shortcuts_in_known_folder(&FOLDERID_PublicDesktop,FALSE,installed_exe);
    }
    if (SUCCEEDED(hr)) CoUninitialize();
}
static BOOL cleanup_test(void) {
    WCHAR temp[MAX_PATH],root[MAX_PATH],app[MAX_PATH],aot[MAX_PATH],exe[MAX_PATH];
    DWORD count=GetTempPathW(MAX_PATH,temp);
    if (!count || count>MAX_PATH-80) return FALSE;
    swprintf(root,MAX_PATH,L"%lsFish-clean-%lu",temp,GetCurrentProcessId());
    delete_tree(root);
    if (!CreateDirectoryW(root,NULL)) return FALSE;
    swprintf(app,MAX_PATH,L"%ls\\app",root);
    swprintf(aot,MAX_PATH,L"%ls\\app\\Fish.aot",root);
    swprintf(exe,MAX_PATH,L"%ls\\Fish.exe",root);
    BOOL ok=CreateDirectoryW(app,NULL);
    HANDLE h1=ok ? CreateFileW(exe,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL) : INVALID_HANDLE_VALUE;
    if (h1!=INVALID_HANDLE_VALUE) CloseHandle(h1); else ok=FALSE;
    HANDLE h2=ok ? CreateFileW(aot,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_READONLY,NULL) : INVALID_HANDLE_VALUE;
    if (h2!=INVALID_HANDLE_VALUE) CloseHandle(h2); else ok=FALSE;
    clean_fish_install_dir(root,FALSE);
    ok=ok && GetFileAttributesW(app)==INVALID_FILE_ATTRIBUTES && GetFileAttributesW(exe)!=INVALID_FILE_ATTRIBUTES;
    clean_fish_install_dir(root,TRUE);
    ok=ok && GetFileAttributesW(root)==INVALID_FILE_ATTRIBUTES;
    delete_tree(root);
    return ok;
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
static DWORD run_msi(void) {
    WCHAR *memory=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,4*32768*sizeof(WCHAR));
    if (!memory) return ERROR_OUTOFMEMORY;
    WCHAR *directory=memory,*msi=memory+32768,*command=memory+65536,*target=memory+98304;
    swprintf(target,32768,L"%ls\\Fish.exe",destination);
    DWORD result=ERROR_INSTALL_FAILURE; HANDLE verified=INVALID_HANDLE_VALUE;
    if (running_target(target)) result=ERROR_INSTALL_ALREADY_RUNNING;
    else if (!directory_ok(destination)) result=ERROR_DIR_NOT_EMPTY;
    else if (extract(directory,msi,&verified)) {
        if (window) PostMessageW(window,PHASE,1,0);
        WCHAR system[MAX_PATH]; UINT count=GetSystemDirectoryW(system,MAX_PATH);
        if (count && count<MAX_PATH) {
            WCHAR program[MAX_PATH]; swprintf(program,MAX_PATH,L"%ls\\msiexec.exe",system);
            remove_conflicting_msi_products(program,destination,command);
            clean_fish_install_dir(destination,FALSE);
            command[0]=0;
            BOOL valid=fish_quote(command,32768,program);
            if (valid) wcscat(command,L" /i ");
            valid=valid && fish_quote(command,32768,msi);
            if (valid && wcslen(command)+140<32768) {
                wcscat(command,system_wide_mode
                        ? L" /qn /norestart MSIRESTARTMANAGERCONTROL=Disable ALLUSERS=1 MSIINSTALLPERUSER=\"\" INSTALLDIR="
                        : L" /qn /norestart MSIRESTARTMANAGERCONTROL=Disable MSIINSTALLPERUSER=1 ALLUSERS=2 INSTALLDIR=");
            } else valid=FALSE;
            valid=valid && fish_quote(command,32768,destination);
            STARTUPINFOW startup={0}; startup.cb=sizeof(startup); PROCESS_INFORMATION process={0};
            if (valid && CreateProcessW(program,command,NULL,NULL,FALSE,CREATE_NO_WINDOW,NULL,NULL,&startup,&process)) {
                if (WaitForSingleObject(process.hProcess,INFINITE)!=WAIT_OBJECT_0 || !GetExitCodeProcess(process.hProcess,&result))
                    result=GetLastError();
                CloseHandle(process.hThread); CloseHandle(process.hProcess);
                if (result==ERROR_SUCCESS) {
                    if (window) PostMessageW(window,PHASE,2,0);
                    if (!version_ok(target)) result=ERROR_PRODUCT_VERSION;
                }
                if (result==ERROR_SUCCESS) reconcile_post_install(target,destination);
            } else result=valid ? GetLastError() : ERROR_INVALID_PARAMETER;
        }
    }
    if (verified!=INVALID_HANDLE_VALUE) CloseHandle(verified);
    if (*msi) DeleteFileW(msi);
    if (*directory) RemoveDirectoryW(directory);
    HeapFree(GetProcessHeap(),0,memory);
    return result;
}
static DWORD WINAPI install_worker(void *unused) {
    (void)unused; PostMessageW(window,RESULT,run_msi(),0); return 0;
}
static DWORD elevate_for_all_users(void) {
    WCHAR self[32768],*params=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,32768*sizeof(WCHAR));
    if (!params) return ERROR_OUTOFMEMORY;
    DWORD len=GetModuleFileNameW(NULL,self,32768);
    if (!len || len>=32768) { HeapFree(GetProcessHeap(),0,params); return ERROR_BAD_PATHNAME; }
    wcscpy(params,L"--elevated-all-users ");
    if (!fish_quote(params,32768,destination) || wcslen(params)+20>=32768) {
        HeapFree(GetProcessHeap(),0,params); return ERROR_BAD_PATHNAME;
    }
    wcscat(params,L" --worker");
    SHELLEXECUTEINFOW sei={0};
    sei.cbSize=sizeof(sei);
    sei.hwnd=window;
    sei.lpVerb=L"runas";
    sei.lpFile=self;
    sei.lpParameters=params;
    sei.fMask=SEE_MASK_NOCLOSEPROCESS|SEE_MASK_NOASYNC;
    sei.nShow=SW_HIDE;
    DWORD error=ERROR_SUCCESS;
    if (!ShellExecuteExW(&sei)) { error=GetLastError(); if (!error) error=ERROR_ACCESS_DENIED; }
    else if (!sei.hProcess) error=ERROR_INVALID_HANDLE;
    else elevated_process=sei.hProcess;
    HeapFree(GetProcessHeap(),0,params);
    return error;
}
static void toggle_all_users(void) {
    system_wide_mode=SendMessageW(all_users,BM_GETCHECK,0,0)==BST_CHECKED;
    if (!custom_folder_chosen && !registered_installation) {
        const WCHAR *target=system_wide_mode ? system_default_dest : user_default_dest;
        if (*target) {
            wcscpy(destination,target);
            normalize_destination();
            SetWindowTextW(folder,destination);
        }
    }
    layout();
}
static void start_install(void) {
    if (busy || completed || parent_process || fatal) return;
    GetWindowTextW(folder,destination,32768);
    normalize_destination();
    system_wide_mode=all_users && SendMessageW(all_users,BM_GETCHECK,0,0)==BST_CHECKED;
    busy=TRUE; issue=FALSE; pulse=0;
    stop_frames(); motion=(FillMotion){0}; reset_art(); set_progress(-1);
    wcscpy(status,L"Verifying the installer\u2026");
    layout();
    fill_toward(.08);
    if (demo) return;
    if (system_wide_mode && !IsUserAnAdmin()) {
        DWORD error=elevate_for_all_users();
        if (error) PostMessageW(window,RESULT,error,0);
        else { wcscpy(status,L"Installing for everyone on this PC\u2026"); SetWindowTextW(description,status); fill_toward(.62); }
        return;
    }
    HANDLE worker=CreateThread(NULL,0,install_worker,NULL,0,NULL);
    if (worker) CloseHandle(worker);
    else PostMessageW(window,RESULT,GetLastError(),0);
}
/* The window that requested UAC owns completion; the elevated MSI worker never opens Fish.
   If setup itself was started elevated, require a checked, limited token for the same user. */
static BOOL start_as_user(const WCHAR *path,WCHAR *command,STARTUPINFOW *startup,PROCESS_INFORMATION *process) {
    HANDLE token=NULL; TOKEN_ELEVATION elevation; DWORD bytes=0;
    if (!OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&token)) return FALSE;
    BOOL ok=GetTokenInformation(token,TokenElevation,&elevation,sizeof(elevation),&bytes);
    if (ok && !elevation.TokenIsElevated)
        ok=CreateProcessW(path,command,NULL,NULL,FALSE,0,NULL,destination,startup,process);
    else if (ok) {
        TOKEN_LINKED_TOKEN limited;
        ok=GetTokenInformation(token,TokenLinkedToken,&limited,sizeof(limited),&bytes);
        if (ok) {
            ok=GetTokenInformation(limited.LinkedToken,TokenElevation,&elevation,sizeof(elevation),&bytes)
                && !elevation.TokenIsElevated
                && CreateProcessWithTokenW(limited.LinkedToken,0,path,command,0,NULL,destination,startup,process);
            CloseHandle(limited.LinkedToken);
        }
    }
    CloseHandle(token); return ok;
}
static BOOL launch_app(void) {
    if (demo) return TRUE;
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
    if (valid && start_as_user(path,command,&startup,&process)) {
        CloseHandle(process.hThread); CloseHandle(process.hProcess);
        HeapFree(GetProcessHeap(),0,command); HeapFree(GetProcessHeap(),0,path); return TRUE;
    }
failed:
    if (command) HeapFree(GetProcessHeap(),0,command);
    if (path) HeapFree(GetProcessHeap(),0,path);
    wcscpy(status,L"Fish is installed, but could not open as a standard user. Try again or use the Start menu.");
    return FALSE;
}
static BOOL finish_launch(DWORD result,BOOL update,BOOL requested,BOOL (*launch)(void)) {
    return result==ERROR_SUCCESS && ((!update && !requested) || launch());
}
static int completion_attempts;
static BOOL completion_success;
static BOOL completion_launch(void) { completion_attempts++; return completion_success; }
static BOOL completion_test(void) {
    completion_attempts=0; completion_success=TRUE;
    DWORD failures[]={ERROR_INSTALL_FAILURE,ERROR_INSTALL_USEREXIT,ERROR_SUCCESS_REBOOT_REQUIRED,ERROR_INSTALL_ALREADY_RUNNING,ERROR_PRODUCT_VERSION};
    for (int i=0;i<5;i++) if (finish_launch(failures[i],TRUE,TRUE,completion_launch)) return FALSE;
    if (completion_attempts || !finish_launch(ERROR_SUCCESS,FALSE,FALSE,completion_launch) || completion_attempts) return FALSE;
    if (!finish_launch(ERROR_SUCCESS,FALSE,TRUE,completion_launch) || completion_attempts!=1) return FALSE;
    if (!finish_launch(ERROR_SUCCESS,TRUE,FALSE,completion_launch) || completion_attempts!=2) return FALSE;
    completion_success=FALSE;
    if (finish_launch(ERROR_SUCCESS,TRUE,TRUE,completion_launch) || completion_attempts!=3) return FALSE;
    completion_success=TRUE;
    return finish_launch(ERROR_SUCCESS,TRUE,TRUE,completion_launch) && completion_attempts==4;
}
static void browse(void) {
    if (demo) {
        /* Don't even create a shell picker (it can persist its last visited folder). */
        wcscpy(destination,L"C:\\Example\\Fish"); SetWindowTextW(folder,destination);
        custom_folder_chosen=TRUE; return;
    }
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
                        custom_folder_chosen=TRUE;
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
    PWSTR local=NULL,prog=NULL;
    if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_LocalAppData,0,NULL,&local))) {
        swprintf(user_default_dest,32768,L"%ls\\Fish",local);
        if (!elevated_autostart) wcscpy(destination,user_default_dest);
        CoTaskMemFree(local);
    }
    if (SUCCEEDED(SHGetKnownFolderPath(&FOLDERID_ProgramFiles,0,NULL,&prog))) {
        swprintf(system_default_dest,32768,L"%ls\\Fish",prog);
        CoTaskMemFree(prog);
    } else wcscpy(system_default_dest,L"C:\\Program Files\\Fish");
    if (elevated_autostart) {
        normalize_destination();
        return;
    }
    WCHAR product[39]; DWORD index=0;
    while (MsiEnumRelatedProductsW(L"{55B0674D-E226-46CB-852F-6BEDDD35A74B}",0,index++,product)==ERROR_SUCCESS) {
        DWORD length=32767; WCHAR location[32768];
        if (MsiGetProductInfoW(product,INSTALLPROPERTY_INSTALLLOCATION,location,&length)==ERROR_SUCCESS && *location) {
            wcscpy(destination,location); registered_installation=TRUE;
            WCHAR per_machine[16]=L""; DWORD pm_len=15;
            if (MsiGetProductInfoW(product,INSTALLPROPERTY_ASSIGNMENTTYPE,per_machine,&pm_len)==ERROR_SUCCESS && per_machine[0]==L'1')
                system_wide_mode=TRUE;
            break;
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
            #define BUTTON(id,name,kind) CreateWindowW(L"BUTTON",name,WS_CHILD|WS_TABSTOP|kind,0,0,0,0,hwnd,(HMENU)id,NULL,NULL)
            minimize=BUTTON(MINIMIZE,L"Minimize",BS_PUSHBUTTON);
            cancel=BUTTON(CANCEL,L"Close",BS_PUSHBUTTON);
            options=BUTTON(OPTIONS,L"Installation options",BS_PUSHBUTTON);
            folder=CreateWindowExW(0,L"EDIT",destination,WS_CHILD|WS_TABSTOP|ES_AUTOHSCROLL|ES_READONLY,0,0,0,0,hwnd,NULL,NULL,NULL);
            change=BUTTON(CHANGE,L"Change\u2026",BS_PUSHBUTTON);
            all_users=BUTTON(ALL_USERS,L"Install for all users (requires administrator)",BS_AUTOCHECKBOX);
            open_app=BUTTON(OPEN_APP,L"Open Fish when finished",BS_AUTOCHECKBOX);
            install=BUTTON(INSTALL,L"Install",BS_PUSHBUTTON);
            #undef BUTTON
            description=CreateWindowW(L"STATIC",L"",WS_CHILD|SS_OWNERDRAW,0,0,0,0,hwnd,NULL,NULL,NULL);
            progress_bar=CreateWindowW(PROGRESS_CLASSW,L"Installation progress",WS_CHILD|PBS_MARQUEE,0,0,0,0,hwnd,NULL,NULL,NULL);
            HWND buttons[]={change,options,install,cancel,minimize};
            for (unsigned i=0;i<sizeof(buttons)/sizeof(buttons[0]);i++)
                if (!buttons[i] || !SetWindowSubclass(buttons[i],skin_proc,2,0)) return -1;
            if (!folder || !description || !progress_bar || !SetWindowSubclass(description,skin_proc,3,0)
                    || !SetWindowSubclass(progress_bar,skin_proc,3,0)) return -1;
            if (!all_users || !open_app || !SetWindowSubclass(all_users,option_procedure,1,0)
                    || !SetWindowSubclass(open_app,option_procedure,1,0)) return -1;
            SendMessageW(all_users,BM_SETCHECK,system_wide_mode ? BST_CHECKED : BST_UNCHECKED,0);
            SendMessageW(open_app,BM_SETCHECK,elevated_open_app ? BST_CHECKED : BST_UNCHECKED,0);
            SetTimer(hwnd,1,90,NULL); return 0;
        }
        case WM_CTLCOLORSTATIC:
            if ((HWND)lp!=folder) { SetBkMode((HDC)wp,TRANSPARENT); return (LRESULT)GetStockObject(HOLLOW_BRUSH); }
            /* The read-only edit receives WM_CTLCOLORSTATIC, too. */
            SetTextColor((HDC)wp,text); SetBkColor((HDC)wp,panel); return (LRESULT)field_brush;
        case WM_CTLCOLOREDIT: SetTextColor((HDC)wp,text); SetBkColor((HDC)wp,panel); return (LRESULT)field_brush;
        case WM_DRAWITEM: {
            DRAWITEMSTRUCT *item=(DRAWITEMSTRUCT *)lp;
            if (item->hwndItem==description && window) { paint_readout(description,item->hDC); return TRUE; }
            break;
        }
        case WM_SETTINGCHANGE:
        case WM_THEMECHANGED:
            motion_preference(); colors(FALSE); RedrawWindow(hwnd,NULL,NULL,RDW_INVALIDATE|RDW_ALLCHILDREN); return 0;
        case WM_NCHITTEST: {
            POINT p={(short)LOWORD(lp),(short)HIWORD(lp)}; ScreenToClient(hwnd,&p);
            if (p.y>=0 && p.y<px(40) && p.x>=0 && p.x<px(WIDTH-76)) return HTCAPTION;
            break;
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
            if (!window) break;
            const RECT *suggested=(RECT *)lp;
            MONITORINFO mi={.cbSize=sizeof(mi)};
            if (!GetMonitorInfoW(MonitorFromRect(suggested,MONITOR_DEFAULTTONEAREST),&mi)) break;
            dpi=layout_dpi(&mi.rcWork,HIWORD(wp)); fonts();
            int width=px(WIDTH),height=px(logical_height());
            SetWindowPos(hwnd,NULL,max(mi.rcWork.left,min(suggested->left,mi.rcWork.right-width)),
                max(mi.rcWork.top,min(suggested->top,mi.rcWork.bottom-height)),width,height,SWP_NOZORDER|SWP_NOACTIVATE);
            layout(); return 0;
        }
        case WM_TIMER:
            if (wp==FRAME_TIMER) {
                if (frame_timer) advance_frame(motion_enabled && !IsIconic(hwnd));
                return 0;
            }
            if (parent_process && !issue) {
                DWORD wait=WaitForSingleObject(parent_process,0);
                if (wait==WAIT_OBJECT_0) {
                    CloseHandle(parent_process); parent_process=NULL; start_install();
                } else if (wait==WAIT_FAILED || GetTickCount64()-waiting_since>60000) {
                    issue=TRUE; SetWindowTextW(install,L"Retry");
                    wcscpy(status,L"Fish did not close. Close Fish, then retry. No files were changed."); layout();
                }
            }
            if (elevated_process && WaitForSingleObject(elevated_process,0)!=WAIT_TIMEOUT) {
                DWORD result;
                if (!GetExitCodeProcess(elevated_process,&result)) result=GetLastError();
                CloseHandle(elevated_process); elevated_process=NULL; PostMessageW(hwnd,RESULT,result,0);
            }
            if (busy && demo) {
                pulse++;
                if (pulse<=25) PostMessageW(hwnd,PREPARING,pulse*4,0);
                if (pulse==26) PostMessageW(hwnd,PHASE,1,0);
                if (pulse==45 && !demo_error) PostMessageW(hwnd,PHASE,2,0);
                if (pulse==50) {
                    PostMessageW(hwnd,RESULT,demo_error ? ERROR_INSTALL_FAILURE : ERROR_SUCCESS,0);
                    demo_error=FALSE;
                }
            }
            return 0;
        case PHASE:
            set_progress(-1);
            wcscpy(status,wp==2 ? L"Checking the installed version\u2026" : updating ? L"Applying the update\u2026" : L"Installing Fish\u2026");
            SetWindowTextW(description,status); fill_toward(wp==2 ? .92 : .72); return 0;
        case PREPARING:
            if (progress_value<0) SetWindowTextW(description,L"Preparing files\u2026");
            if (wp>=100) SetWindowTextW(description,L"Verifying files\u2026");
            set_progress(wp>=100 ? -1 : (int)wp);
            fill_toward(.10+.38*unit(wp/100.)); return 0;
        case RESULT:
            if (!busy || completed) return 0;
            busy=FALSE; stop_frames();
            if (wp==ERROR_SUCCESS || wp==ERROR_SUCCESS_REBOOT_REQUIRED) {
                completed=TRUE; reboot=wp==ERROR_SUCCESS_REBOOT_REQUIRED;
                wcscpy(status,reboot ? L"Installed. Restart Windows before opening Fish." : L"Fish " FISH_VERSION L" is installed.");
                SetWindowTextW(install,L"Open Fish");
                if (finish_launch((DWORD)wp,updating,SendMessageW(open_app,BM_GETCHECK,0,0)==BST_CHECKED,launch_app)) {
                    DestroyWindow(hwnd); return 0;
                }
            } else {
                if (wp==ERROR_CANCELLED || wp==ERROR_ACCESS_DENIED) wcscpy(status,L"Administrator access was not granted. Retry with permission to install for everyone.");
                else if (wp==ERROR_INSTALL_ALREADY_RUNNING) wcscpy(status,L"Fish may still be running. Close it, then retry.");
                else if (wp==ERROR_DIR_NOT_EMPTY) wcscpy(status,L"Choose an empty local folder or an existing Fish installation.");
                else if (wp==ERROR_PRODUCT_VERSION) wcscpy(status,L"The installed version could not be verified. Fish was not opened.");
                else swprintf(status,512,L"Installation failed (Windows code %lu). Close any other installer, then retry.",(unsigned long)wp);
                SetWindowTextW(install,L"Retry");
            }
            issue=TRUE; layout(); SetFocus(reboot ? cancel : install); return 0;
        case WM_COMMAND:
            if (HIWORD(wp)!=BN_CLICKED) return 0;
            switch (LOWORD(wp)) {
                case INSTALL:
                    if (busy || !IsWindowVisible(install)) break;
                    if (completed) {
                        if (finish_launch(reboot ? ERROR_SUCCESS_REBOOT_REQUIRED : ERROR_SUCCESS,updating,TRUE,launch_app))
                            DestroyWindow(hwnd);
                    } else if (parent_process) {
                        waiting_since=GetTickCount64(); issue=FALSE;
                        wcscpy(status,L"Waiting for Fish to close\u2026"); layout();
                    } else start_install();
                    break;
                case CANCEL: SendMessageW(hwnd,WM_CLOSE,0,0); break;
                case MINIMIZE: ShowWindow(hwnd,SW_MINIMIZE); break;
                case OPTIONS:
                    if (!compact_view()) { expanded=!expanded; layout(); }
                    break;
                case CHANGE: if (!compact_view() && expanded) browse(); break;
                case ALL_USERS: if (!compact_view() && expanded) toggle_all_users(); break;
            }
            return 0;
        case WM_CLOSE: if (!busy) DestroyWindow(hwnd); return 0;
        case WM_DESTROY:
            stop_frames(); KillTimer(hwnd,1);
            if (accessible_properties) {
                IAccPropServices_ClearHwndProps(accessible_properties,progress_bar,OBJID_CLIENT,CHILDID_SELF,&PROPID_ACC_VALUE,1);
                IAccPropServices_Release(accessible_properties); accessible_properties=NULL;
            }
            PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(hwnd,message,wp,lp);
}
static BOOL render(const WCHAR *path) {
    dpi=96; fonts(); position();
    int height=logical_height(); DWORD image_bytes=(DWORD)(WIDTH*height*4);
    BITMAPINFO info={0}; info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER); info.bmiHeader.biWidth=WIDTH; info.bmiHeader.biHeight=-height;
    info.bmiHeader.biPlanes=1; info.bmiHeader.biBitCount=32; info.bmiHeader.biCompression=BI_RGB;
    void *pixels=NULL; HDC dc=CreateCompatibleDC(NULL); HBITMAP image=CreateDIBSection(dc,&info,DIB_RGB_COLORS,&pixels,NULL,0);
    if (!image || !pixels) { if (image) DeleteObject(image); DeleteDC(dc); return FALSE; }
    HGDIOBJ old=SelectObject(dc,image); surface(dc,WIDTH,height,TRUE); GdiFlush();
    HANDLE file=CreateFileW(path,GENERIC_WRITE,0,NULL,CREATE_NEW,FILE_ATTRIBUTE_NORMAL,NULL);
    BOOL result=FALSE;
    if (file!=INVALID_HANDLE_VALUE) {
        BITMAPFILEHEADER header={0}; DWORD got=0;
        header.bfType=0x4D42; header.bfOffBits=sizeof(header)+sizeof(BITMAPINFOHEADER); header.bfSize=header.bfOffBits+image_bytes;
        result=WriteFile(file,&header,sizeof(header),&got,NULL) && got==sizeof(header)
            && WriteFile(file,&info.bmiHeader,sizeof(BITMAPINFOHEADER),&got,NULL) && got==sizeof(BITMAPINFOHEADER)
            && WriteFile(file,pixels,image_bytes,&got,NULL) && got==image_bytes;
        CloseHandle(file);
    }
    SelectObject(dc,old); DeleteObject(image); DeleteDC(dc);
    return result;
}
static double luminance(COLORREF color) {
    double values[3]={GetRValue(color)/255.0,GetGValue(color)/255.0,GetBValue(color)/255.0};
    for (int i=0;i<3;i++) values[i]=values[i]<=0.04045 ? values[i]/12.92 : pow((values[i]+0.055)/1.055,2.4);
    return 0.2126*values[0]+0.7152*values[1]+0.0722*values[2];
}
static BOOL option_test_control(HWND target) {
    HDC dc=CreateCompatibleDC(NULL);
    BITMAPINFO info={0}; info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth=px(540); info.bmiHeader.biHeight=-px(27); info.bmiHeader.biPlanes=1; info.bmiHeader.biBitCount=32;
    void *pixels=NULL; HBITMAP image=CreateDIBSection(dc,&info,DIB_RGB_COLORS,&pixels,NULL,0);
    if (!dc || !image || !pixels) { if (image) DeleteObject(image); if (dc) DeleteDC(dc); return FALSE; }
    HGDIOBJ previous=SelectObject(dc,image); BOOL valid=TRUE;
    for (int system=0;system<2;system++) {
        colors(system);
        for (int enabled=0;enabled<2;enabled++) for (int checked=0;checked<2;checked++) {
            EnableWindow(target,enabled); SendMessageW(target,BM_SETCHECK,checked ? BST_CHECKED : BST_UNCHECKED,0);
            SendMessageW(target,WM_MOUSEMOVE,0,MAKELPARAM(px(8),px(8)));
            SendMessageW(target,BM_SETSTATE,checked,0);
            unsigned before=option_paints; SendMessageW(target,WM_PRINTCLIENT,(WPARAM)dc,PRF_CLIENT);
            COLORREF foreground=enabled ? text : muted;
            double first=luminance(foreground),second=luminance(background);
            double ratio=(max(first,second)+0.05)/(min(first,second)+0.05);
            int ink=0; RECT client; GetClientRect(target,&client); GdiFlush();
            DWORD rgb=RGB(GetBValue(foreground),GetGValue(foreground),GetRValue(foreground));
            for (int y=0;y<px(27);y++) for (int x=px(25);x<min(px(500),client.right);x++)
                if ((((DWORD *)pixels)[y*px(540)+x]&0xffffff)==rgb) ink++;
            valid=valid && option_paints==before+1 && ratio>=4.5 && ink>25
                && SendMessageW(target,BM_GETCHECK,0,0)==(checked ? BST_CHECKED : BST_UNCHECKED);
        }
    }
    SendMessageW(target,BM_SETSTATE,FALSE,0);
    EnableWindow(target,TRUE); SendMessageW(target,BM_CLICK,0,0);
    valid=valid && SendMessageW(target,BM_GETCHECK,0,0)==BST_UNCHECKED;
    SelectObject(dc,previous); DeleteObject(image); DeleteDC(dc); colors(FALSE); return valid;
}
static BOOL option_test(void) {
    UINT saved=dpi; BOOL valid=TRUE; expanded=TRUE;
    const UINT scales[]={96,120,144,168,192};
    for (unsigned i=0;i<sizeof(scales)/sizeof(scales[0]);i++) {
        dpi=scales[i]; fonts(); position();
        valid=option_test_control(open_app) && option_test_control(all_users) && valid;
    }
    /* Verify toggling all_users switches default target between per-user and system-wide folders. */
    wcscpy(user_default_dest,L"C:\\Users\\Test\\AppData\\Local\\Fish");
    wcscpy(system_default_dest,L"C:\\Program Files\\Fish");
    custom_folder_chosen=FALSE; registered_installation=FALSE;
    SendMessageW(all_users,BM_SETCHECK,BST_UNCHECKED,0);
    SendMessageW(all_users,BM_CLICK,0,0);
    WCHAR actual[32768]=L""; GetWindowTextW(folder,actual,32768);
    valid=valid && SendMessageW(all_users,BM_GETCHECK,0,0)==BST_CHECKED && !wcscmp(actual,L"C:\\Program Files\\Fish");
    SendMessageW(all_users,BM_CLICK,0,0);
    GetWindowTextW(folder,actual,32768);
    valid=valid && SendMessageW(all_users,BM_GETCHECK,0,0)==BST_UNCHECKED && !wcscmp(actual,L"C:\\Users\\Test\\AppData\\Local\\Fish");
    dpi=saved; fonts(); position(); return valid;
}
/* Drive the same UI-thread transition as the timer, without changing system settings. */
static BOOL motion_test(void) {
    updating=busy=TRUE; issue=FALSE; reset_art(); position();
    BOOL valid=ensure_art(px(WIDTH),px(logical_height())) && fish_pixels;
    /* Sample a real point on the nose as the soft edge passes. It must move
       through intermediate colors without ever flashing back to the base. */
    int sx=px(10),sy=px(88);
    FishPixel sample=valid ? fish_pixels[sy*fish_w+sx] : (FishPixel){0,0};
    int last_ink=0; BOOL blended=FALSE;
    valid=valid && sample.base!=sample.filled;
    FillMotion m={.goal=.48,.last=1000};
    for (unsigned tick=1;valid && tick<=125;tick++) {
        double before=m.shown;
        if (tick==20) m.goal=.72;
        motion_step(&m,1000+tick*16,TRUE);
        valid=m.shown>=before && m.shown<=m.goal && m.shown<1 && (tick!=1 || (m.shown>0 && m.shown<m.goal));
        compose_fill(m.shown);
        DWORD pixel=art_bits[(size_t)(px(FISH_Y)+sy)*art_w+px(FISH_X)+sx];
        int ink=abs((int)GetRValue(pixel)-GetRValue(sample.base))+abs((int)GetGValue(pixel)-GetGValue(sample.base))
            +abs((int)GetBValue(pixel)-GetBValue(sample.base));
        if (pixel!=sample.base && pixel!=sample.filled) blended=TRUE;
        valid=valid && ink>=last_ink; last_ink=ink;
    }
    valid=valid && m.shown==m.goal && blended && last_ink>0;
    motion=m; motion_enabled=FALSE;
    set_progress(47); fill_toward(.92);
    valid=valid && !frame_timer && motion.shown==.92 && SendMessageW(progress_bar,PBM_GETPOS,0,0)==47;
    fill_toward(.48); /* A late/duplicate report must never pull the fill backwards. */
    valid=valid && motion.shown==.92 && frame_fill==motion.shown;
    set_progress(-1); valid=valid && (GetWindowLongPtrW(progress_bar,GWL_STYLE)&PBS_MARQUEE);
    return valid;
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE previous,PWSTR command,int show) {
    (void)previous; (void)command;
    int count=0; LPWSTR *args=CommandLineToArgvW(GetCommandLineW(),&count);
    if (!args) return 2;
    if (count==2 && !wcscmp(args[1],L"--self-test")) {
        WCHAR quote[100]=L"";
        BOOL valid=fish_quote(quote,100,L"C:\\path with space\\") && !wcscmp(quote,L"\"C:\\path with space\\\\\"");
        valid=valid && !fish_quote(quote,4,L"too long") && location_test() && cleanup_test() && completion_test(); LocalFree(args); return valid ? 0 : 1;
    }
    if (count==2 && !wcscmp(args[1],L"--verify-payload")) {
        WCHAR self[32768]; GetModuleFileNameW(NULL,self,32768);
        HANDLE file=CreateFileW(self,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
        Footer footer; ULONGLONG offset; BOOL valid=file!=INVALID_HANDLE_VALUE && payload(file,&footer,&offset);
        if (file!=INVALID_HANDLE_VALUE) CloseHandle(file); LocalFree(args); return valid ? 0 : 1;
    }
    BOOL preview=count>=3 && count<=5 && !wcscmp(args[1],L"--render-preview");
    BOOL controls=count==2 && !wcscmp(args[1],L"--test-controls");
    BOOL interactive=count>=2 && count<=3 && (!wcscmp(args[1],L"--preview-installer") ||
        !wcscmp(args[1],L"--preview-update") || !wcscmp(args[1],L"--preview-update-error"));
    WCHAR preview_path[32768]=L"";
    if (preview || interactive || controls) {
        demo=TRUE;
        const WCHAR *theme=preview && count==5 ? args[4] : interactive && count==3 ? args[2] : NULL;
        if (theme) {
            if (!wcscmp(theme,L"light")) theme_override=1;
            else if (!wcscmp(theme,L"dark")) theme_override=-1;
            else { LocalFree(args); return 2; }
        }
        if (preview) {
            if (wcslen(args[2])>=32768) { LocalFree(args); return 2; } wcscpy(preview_path,args[2]);
            if (count>=4) {
                if (!wcscmp(args[3],L"update")) { updating=TRUE; busy=TRUE; motion.shown=motion.goal=.72; wcscpy(status,L"Applying the update\u2026"); }
                else if (!wcscmp(args[3],L"update-start")) { updating=TRUE; busy=TRUE; progress_value=0; wcscpy(status,L"Verifying the installer\u2026"); }
                else if (!wcscmp(args[3],L"options")) expanded=TRUE;
                else if (wcscmp(args[3],L"installer")) { LocalFree(args); return 2; }
            }
        } else if (interactive) {
            updating=wcscmp(args[1],L"--preview-installer")!=0;
            demo_error=!wcscmp(args[1],L"--preview-update-error");
        }
    }
    if (count==5 && !wcscmp(args[1],L"--update-pid") && !wcscmp(args[3],L"--data-dir")) {
        WCHAR *end=NULL; unsigned long pid=wcstoul(args[2],&end,10);
        if (!pid || *end || !*args[4] || wcslen(args[4])>=32700) { LocalFree(args); return 2; }
        parent_pid=(DWORD)pid; wcscpy(data_directory,args[4]); updating=TRUE;
    } else if ((count==3 || count==4) && !wcscmp(args[1],L"--elevated-all-users")) {
        if (!*args[2] || wcslen(args[2])>=32700) { LocalFree(args); return 2; }
        wcscpy(destination,args[2]);
        system_wide_mode=TRUE; custom_folder_chosen=TRUE; elevated_autostart=TRUE;
        if (count==4) {
            if (!wcscmp(args[3],L"--worker")) {
                LocalFree(args); normalize_destination();
                return IsUserAnAdmin() ? (int)run_msi() : ERROR_ELEVATION_REQUIRED;
            }
            if (!wcscmp(args[3],L"--open-app")) elevated_open_app=TRUE;
            else if (!wcscmp(args[3],L"--no-open-app")) elevated_open_app=FALSE;
            else { LocalFree(args); return 2; }
        }
    } else if (count!=1 && !demo) { LocalFree(args); return 2; }
    LocalFree(args); CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    if (!demo) installed_location();
    else {
        wcscpy(user_default_dest,L"%LOCALAPPDATA%\\Fish");
        wcscpy(system_default_dest,L"C:\\Program Files\\Fish");
        wcscpy(destination,user_default_dest);
    }
    if (!*destination) { fatal=issue=TRUE; wcscpy(status,L"Setup could not resolve a safe installation folder. No files were changed."); }
    INITCOMMONCONTROLSEX init={sizeof(init),ICC_PROGRESS_CLASS|ICC_STANDARD_CLASSES}; InitCommonControlsEx(&init);
    WNDCLASSEXW klass={0}; klass.cbSize=sizeof(klass); klass.lpfnWndProc=procedure; klass.hInstance=instance;
    klass.lpszClassName=L"FishSetup"; klass.hCursor=LoadCursorW(NULL,IDC_ARROW);
    klass.hIcon=LoadImageW(instance,MAKEINTRESOURCEW(1),IMAGE_ICON,32,32,LR_SHARED);
    klass.hIconSm=LoadImageW(instance,MAKEINTRESOURCEW(1),IMAGE_ICON,16,16,LR_SHARED);
    RegisterClassExW(&klass); colors(FALSE);
    DWORD style=WS_POPUP|WS_SYSMENU|WS_MINIMIZEBOX|WS_CLIPCHILDREN;
    POINT launch_point={0,0}; GetCursorPos(&launch_point);
    HMONITOR monitor=MonitorFromPoint(launch_point,MONITOR_DEFAULTTONEAREST);
    HWND foreground=GetForegroundWindow(); DWORD owner_pid=0;
    if (foreground && updating && parent_pid && GetWindowThreadProcessId(foreground,&owner_pid) && owner_pid==parent_pid)
        monitor=MonitorFromWindow(foreground,MONITOR_DEFAULTTONEAREST);
    MONITORINFO launch={.cbSize=sizeof(launch)};
    if (!GetMonitorInfoW(monitor,&launch)) { CoUninitialize(); return 4; }
    RECT work=launch.rcWork;
    /* A hidden HWND on the launch display gives us its effective DPI before we
       center or show anything. No 96-DPI primary-screen rectangle to correct. */
    window=CreateWindowExW(WS_EX_APPWINDOW,klass.lpszClassName,updating ? L"Updating Fish" : L"Install Fish",
        style,work.left+(work.right-work.left)/2,work.top+(work.bottom-work.top)/2,1,1,NULL,NULL,instance,NULL);
    if (!window) { DeleteObject(field_brush); CoUninitialize(); return 4; }
    dpi=layout_dpi(&work,GetDpiForWindow(window)); fonts();
    int start_w=px(WIDTH),start_h=px(logical_height());
    SetWindowPos(window,NULL,work.left+(work.right-work.left-start_w)/2,work.top+(work.bottom-work.top-start_h)/2,
        start_w,start_h,SWP_NOZORDER|SWP_NOACTIVATE);
    BOOL dark=!light; DwmSetWindowAttribute(window,20,&dark,sizeof(dark));
    DWORD corner=2; DwmSetWindowAttribute(window,33,&corner,sizeof(corner));
    CoCreateInstance(&CLSID_AccPropServices,NULL,CLSCTX_INPROC_SERVER,&IID_IAccPropServices,(void **)&accessible_properties);
    motion_preference(); set_progress(progress_value); layout();
    if (preview || controls) {
        BOOL valid=controls ? option_test() && motion_test() : render(preview_path);
        DestroyWindow(window);
        if (field_brush) DeleteObject(field_brush);
        reset_art(); DeleteObject(body_font); DeleteObject(title_font); DeleteObject(medium_font);
        CoUninitialize(); return valid ? 0 : 1;
    }
    if (updating && !demo && !fatal) {
        parent_process=OpenProcess(SYNCHRONIZE|PROCESS_QUERY_LIMITED_INFORMATION,FALSE,parent_pid);
        if (parent_process) {
            WCHAR path[32768]; DWORD size=32768;
            if (!QueryFullProcessImageNameW(parent_process,0,path,&size) || !*path) {
                fatal=TRUE;
            }
            else {
                const WCHAR *name=wcsrchr(path,L'\\');
                if (!name || _wcsicmp(name+1,L"Fish.exe")) fatal=TRUE;
            }
        } else if (GetLastError()!=ERROR_INVALID_PARAMETER) fatal=TRUE;
        waiting_since=GetTickCount64();
        if (fatal) { issue=TRUE; wcscpy(status,L"Fish's process identity could not be checked. No update was installed."); }
        else wcscpy(status,L"Waiting for Fish to close\u2026");
        layout();
    }
    if (updating && demo) { wcscpy(status,L"Verifying the installer\u2026"); layout(); }
    ShowWindow(window,show); UpdateWindow(window);
    if (elevated_autostart || (updating && !parent_process && !fatal)) start_install();
    if (!compact_view()) SetFocus(install);
    MSG message;
    while (GetMessageW(&message,NULL,0,0)>0) {
        if (message.message==WM_KEYDOWN && message.wParam==VK_RETURN) {
            HWND focused=GetFocus();
            if (focused && IsWindowVisible(focused) && IsWindowEnabled(focused) &&
                (focused==cancel || focused==change || focused==open_app || focused==all_users || focused==install || focused==options || focused==minimize))
                SendMessageW(focused,BM_CLICK,0,0);
            continue;
        }
        if (message.message==WM_KEYDOWN && message.wParam==VK_ESCAPE && !busy) { SendMessageW(window,WM_CLOSE,0,0); continue; }
        if (!IsDialogMessageW(window,&message)) { TranslateMessage(&message); DispatchMessageW(&message); }
    }
    if (parent_process) CloseHandle(parent_process);
    if (paint_image) { SelectObject(paint_dc,paint_original); DeleteObject(paint_image); }
    if (paint_dc) DeleteDC(paint_dc);
    reset_art(); DeleteObject(body_font); DeleteObject(title_font); DeleteObject(medium_font); DeleteObject(field_brush); CoUninitialize();
    return 0;
}
