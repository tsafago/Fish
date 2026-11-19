/* Interactive Windows desktop, no MSI or elevation. Both processes are this test binary. */
#ifndef UNICODE
#define UNICODE 1
#endif
#define _UNICODE
#define COBJMACROS
#include <windows.h>
#include <shlobj.h>
#include <stdio.h>
#include "fish-user-launch.h"

static BOOL same_user(HANDLE other) {
    HANDLE a=NULL,b=NULL;
    BYTE user_a[sizeof(TOKEN_USER)+SECURITY_MAX_SID_SIZE],user_b[sizeof(TOKEN_USER)+SECURITY_MAX_SID_SIZE];
    DWORD size; BOOL elevated=TRUE;
    BOOL ok=fish_elevation(GetCurrentProcess(),&elevated) && !elevated &&
        OpenProcessToken(GetCurrentProcess(),TOKEN_QUERY,&a) && OpenProcessToken(other,TOKEN_QUERY,&b) &&
        GetTokenInformation(a,TokenUser,user_a,sizeof(user_a),&size) && GetTokenInformation(b,TokenUser,user_b,sizeof(user_b),&size) &&
        EqualSid(((TOKEN_USER *)user_a)->User.Sid,((TOKEN_USER *)user_b)->User.Sid);
    if (a) CloseHandle(a);
    if (b) CloseHandle(b);
    return ok;
}
int wmain(int argc,WCHAR **argv) {
    if (argc==4 && !wcscmp(argv[1],L"--report")) {
        HANDLE parent=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,wcstoul(argv[2],NULL,10));
        HANDLE signal=OpenEventW(EVENT_MODIFY_STATE,FALSE,argv[3]);
        BOOL ok=parent && signal && same_user(parent) && SetEvent(signal);
        if (parent) CloseHandle(parent);
        if (signal) CloseHandle(signal);
        return ok ? 0 : 1;
    }
    if (argc!=1 || !same_user(GetCurrentProcess())) return 2;
    WCHAR name[120],arguments[192],path[32768],cwd[32768]; GUID id;
    if (FAILED(CoInitializeEx(NULL,COINIT_APARTMENTTHREADED)) || FAILED(CoCreateGuid(&id))) return 3;
    wcscpy(name,L"Local\\FishLaunchFixture-");
    StringFromGUID2(&id,name+wcslen(name),64);
    HANDLE signal=CreateEventW(NULL,TRUE,FALSE,name);
    swprintf(arguments,192,L"--report %lu \"%ls\"",(unsigned long)GetCurrentProcessId(),name);
    if (!signal || !GetModuleFileNameW(NULL,path,32768) || !GetCurrentDirectoryW(32768,cwd)) return 4;
    HRESULT hr=fish_open_from_desktop(path,arguments,cwd);
    BOOL ok=hr==S_OK && WaitForSingleObject(signal,10000)==WAIT_OBJECT_0;
    CloseHandle(signal); CoUninitialize();
    wprintf(L"%ls: Explorer handoff to a non-elevated child with the initiating SID (0x%08lX).\n",
        ok ? L"PASS" : L"FAIL",(unsigned long)hr);
    return ok ? 0 : 5;
}
