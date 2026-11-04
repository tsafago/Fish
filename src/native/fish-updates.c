#define UNICODE
#define _UNICODE
#include <windows.h>
#include <jni.h>
#include <shellapi.h>
#include <wintrust.h>
#include <softpub.h>
#include <objbase.h>
#include <stdio.h>
#include "fish-setup-common.h"

static void failure(JNIEnv *env,const char *message) {
    jclass type=(*env)->FindClass(env,"java/io/IOException");
    if (type) (*env)->ThrowNew(env,type,message);
}
static WCHAR *copy_string(JNIEnv *env,jstring value) {
    if (!value) { failure(env,"Missing Windows update path or hash."); return NULL; }
    jsize size=(*env)->GetStringLength(env,value);
    if (size<1 || size>32700) { failure(env,"Windows update path or hash is too long or empty."); return NULL; }
    WCHAR *result=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,((size_t)size+1)*sizeof(WCHAR));
    if (!result) { failure(env,"Windows updater is out of memory."); return NULL; }
    (*env)->GetStringRegion(env,value,0,size,(jchar *)result);
    if ((*env)->ExceptionCheck(env)) { HeapFree(GetProcessHeap(),0,result); return NULL; }
    for (jsize i=0;i<size;i++) if (!result[i]) {
        HeapFree(GetProcessHeap(),0,result); failure(env,"Invalid Windows update path or hash."); return NULL;
    }
    return result;
}
static BOOL signature(HANDLE file,const WCHAR *path) {
    WINTRUST_FILE_INFO info={0};
    WINTRUST_DATA data={0};
    GUID action=WINTRUST_ACTION_GENERIC_VERIFY_V2;
    info.cbStruct=sizeof(info); info.pcwszFilePath=path; info.hFile=file;
    data.cbStruct=sizeof(data); data.dwUIChoice=WTD_UI_NONE;
    data.fdwRevocationChecks=WTD_REVOKE_WHOLECHAIN;
    data.dwUnionChoice=WTD_CHOICE_FILE; data.pFile=&info;
    data.dwStateAction=WTD_STATEACTION_VERIFY;
    data.dwProvFlags=WTD_CACHE_ONLY_URL_RETRIEVAL;
    LONG status=WinVerifyTrust(NULL,&action,&data);
    data.dwStateAction=WTD_STATEACTION_CLOSE;
    WinVerifyTrust(NULL,&action,&data);
    return status==ERROR_SUCCESS || status==TRUST_E_NOSIGNATURE;
}
static HANDLE open_installer(const WCHAR *path) {
    HANDLE file=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,NULL);
    if (file==INVALID_HANDLE_VALUE) return file;
    BY_HANDLE_FILE_INFORMATION info;
    LARGE_INTEGER size;
    IMAGE_DOS_HEADER dos; IMAGE_NT_HEADERS64 nt; DWORD got;
    if (!GetFileInformationByHandle(file,&info) || (info.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT)
            || !GetFileSizeEx(file,&size) || size.QuadPart<1024 || size.QuadPart>128LL*1024*1024
            || !ReadFile(file,&dos,sizeof(dos),&got,NULL) || got!=sizeof(dos) || dos.e_magic!=IMAGE_DOS_SIGNATURE
            || dos.e_lfanew<(LONG)sizeof(dos) || dos.e_lfanew>size.QuadPart-(LONGLONG)sizeof(nt)
            || SetFilePointer(file,dos.e_lfanew,NULL,FILE_BEGIN)==INVALID_SET_FILE_POINTER
            || !ReadFile(file,&nt,sizeof(nt),&got,NULL) || got!=sizeof(nt) || nt.Signature!=IMAGE_NT_SIGNATURE
            || nt.FileHeader.Machine!=IMAGE_FILE_MACHINE_AMD64 || nt.OptionalHeader.Magic!=IMAGE_NT_OPTIONAL_HDR64_MAGIC) {
        CloseHandle(file); return INVALID_HANDLE_VALUE;
    }
    return file;
}
JNIEXPORT void JNICALL Java_pane5250_update_UpdateSupport_inspect(JNIEnv *env,jclass type,jstring name) {
    (void)type;
    WCHAR *path=copy_string(env,name);
    if (!path) return;
    HANDLE file=open_installer(path);
    if (file==INVALID_HANDLE_VALUE || !signature(file,path)) {
        if (file!=INVALID_HANDLE_VALUE) CloseHandle(file);
        HeapFree(GetProcessHeap(),0,path);
        failure(env,"Installer is invalid, or its Authenticode signature could not be validated. Nothing was run.");
        return;
    }
    size_t count=wcslen(path)+32;
    WCHAR *zone=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,count*sizeof(WCHAR));
    BOOL marked=FALSE;
    if (zone) {
        swprintf(zone,count,L"%ls:Zone.Identifier",path);
        HANDLE stream=CreateFileW(zone,GENERIC_WRITE,0,NULL,CREATE_ALWAYS,FILE_ATTRIBUTE_NORMAL,NULL);
        if (stream!=INVALID_HANDLE_VALUE) {
            const char metadata[]="[ZoneTransfer]\r\nZoneId=3\r\nHostUrl=https://github.com/tsafago/Fish/releases\r\n";
            DWORD wrote=0;
            marked=WriteFile(stream,metadata,sizeof(metadata)-1,&wrote,NULL) && wrote==sizeof(metadata)-1;
            CloseHandle(stream);
        }
        HeapFree(GetProcessHeap(),0,zone);
    }
    CloseHandle(file); HeapFree(GetProcessHeap(),0,path);
    if (!marked) failure(env,"Windows could not mark this download as Internet-origin. Use an NTFS temporary directory; no security policy was bypassed.");
}
JNIEXPORT void JNICALL Java_pane5250_update_UpdateSupport_launchNative(JNIEnv *env,jclass type,jstring name,jstring expected,jlong pid,jstring directory) {
    (void)type;
    WCHAR *path=copy_string(env,name);
    WCHAR *hash=path ? copy_string(env,expected) : NULL;
    WCHAR *data=hash ? copy_string(env,directory) : NULL;
    if (!path || !hash || !data) goto release;
    WCHAR executable[32768];
    DWORD length=GetModuleFileNameW(NULL,executable,32768);
    const WCHAR *base=length && length<32768 ? wcsrchr(executable,L'\\') : NULL;
    if (!length || length>=32768 || !base || _wcsicmp(base+1,L"Fish.exe") || pid!=(jlong)GetCurrentProcessId()) {
        failure(env,"Run the packaged Fish.exe to use self-update. Source/java launches can download and install Fish manually."); goto release;
    }
    HANDLE file=open_installer(path);
    if (file==INVALID_HANDLE_VALUE) { failure(env,"Verified installer is unavailable. Nothing was run."); goto release; }
    BYTE actual[32];
    LARGE_INTEGER size; BOOL valid=GetFileSizeEx(file,&size) && wcslen(hash)==64 && fish_hash(file,0,(ULONGLONG)size.QuadPart,actual);
    for (int i=0;valid && i<32;i++) {
        WCHAR pair[3]={hash[i*2],hash[i*2+1],0}; WCHAR *end;
        unsigned long value=wcstoul(pair,&end,16);
        if (*end || value!=actual[i]) valid=FALSE;
    }
    if (!valid || !signature(file,path)) {
        CloseHandle(file); failure(env,"Installer changed or its signature is invalid. Nothing was run."); goto release;
    }
    WCHAR *parameters=HeapAlloc(GetProcessHeap(),HEAP_ZERO_MEMORY,32768*sizeof(WCHAR));
    BOOL launched=FALSE;
    HRESULT apartment=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    if (parameters) {
        swprintf(parameters,32768,L"--update-pid %lu --data-dir ",(unsigned long)pid);
        if (fish_quote(parameters,32768,data)) {
            SHELLEXECUTEINFOW action={0};
            action.cbSize=sizeof(action); action.fMask=SEE_MASK_NOCLOSEPROCESS|SEE_MASK_NOASYNC;
            action.lpVerb=L"open"; action.lpFile=path; action.lpParameters=parameters; action.nShow=SW_SHOWNORMAL;
            launched=ShellExecuteExW(&action);
            if (action.hProcess) CloseHandle(action.hProcess);
        }
        HeapFree(GetProcessHeap(),0,parameters);
    }
    if (SUCCEEDED(apartment)) CoUninitialize();
    CloseHandle(file);
    if (!launched) failure(env,"Windows did not start setup (cancelled, blocked by policy, or unavailable). Fish is still open; nothing was reported installed.");
release:
    if (data) HeapFree(GetProcessHeap(),0,data);
    if (hash) HeapFree(GetProcessHeap(),0,hash);
    if (path) HeapFree(GetProcessHeap(),0,path);
}
