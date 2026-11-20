/* Only Windows Installer removes installed components. Setup owns immutable
   source files outside INSTALLDIR, so rollback and future maintenance can read them. */
static void issue_at(const WCHAR *step,const WCHAR *path,DWORD code) {
    problem_step=step; problem_code=code;
    wcsncpy(problem_path,path ? path : L"",MAX_PATH-1); problem_path[MAX_PATH-1]=0;
}

#define RELEASED_PRODUCT L"{E39FAF6D-619A-403C-8F4D-2CF1644A5EC6}"
static const Footer released_msi={"",61710948,{0x37,0x76,0x64,0x50,0xa9,0x17,0xa6,0x65,0x11,0x7b,0x5c,0xa6,0xc6,0x1e,0x17,0xc6,
    0x10,0xcd,0xd6,0x6d,0xe7,0x42,0xf1,0xd9,0xed,0xef,0xe1,0x1e,0x15,0xda,0xef,0xdf}};

static BOOL safe_local_path(const WCHAR *path) {
    WCHAR copy[MAX_PATH],canonical[MAX_PATH];
    if (wcslen(path)>=MAX_PATH || wcslen(path)<4 || path[1]!=L':' || path[2]!=L'\\'
            || wcspbrk(path+3,L":/\"*?") || GetFullPathNameW(path,MAX_PATH,canonical,NULL)!=wcslen(path)
            || _wcsicmp(canonical,path)) return FALSE;
    wcscpy(copy,path);
    for (WCHAR *p=copy+3;;p++) {
        if (*p!=L'\\' && *p) continue;
        if (p==copy+3 || p[-1]==L'.' || p[-1]==L' ' || p[-1]==L'\\') return FALSE;
        WCHAR end=*p; *p=0; DWORD attr=GetFileAttributesW(copy),error=GetLastError(); *p=end;
        if (attr==INVALID_FILE_ATTRIBUTES) {
            if (error!=ERROR_FILE_NOT_FOUND && error!=ERROR_PATH_NOT_FOUND) return FALSE;
        } else if (attr&FILE_ATTRIBUTE_REPARSE_POINT) return FALSE;
        if (!end) break;
    }
    return TRUE;
}
static BOOL cache_directory(const WCHAR *path) {
    if (!safe_local_path(path) || (!CreateDirectoryW(path,NULL) && GetLastError()!=ERROR_ALREADY_EXISTS)) return FALSE;
    DWORD a=GetFileAttributesW(path);
    return a!=INVALID_FILE_ATTRIBUTES && (a&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))==FILE_ATTRIBUTE_DIRECTORY;
}
static HANDLE open_source(const WCHAR *path,const Footer *identity) {
    HANDLE f=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,
        FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_SEQUENTIAL_SCAN,NULL);
    if (f!=INVALID_HANDLE_VALUE) {
        LARGE_INTEGER size; BY_HANDLE_FILE_INFORMATION info; BYTE digest[32];
        if (GetFileInformationByHandle(f,&info) && !(info.dwFileAttributes&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT))
                && GetFileSizeEx(f,&size) && (ULONGLONG)size.QuadPart==identity->length
                && fish_hash(f,0,identity->length,digest) && !memcmp(identity->hash,digest,32)) return f;
        CloseHandle(f);
    }
    return INVALID_HANDLE_VALUE;
}
static DWORD stage_source(HANDLE self,ULONGLONG offset,const Footer *identity,const WCHAR *base,const WCHAR *name,
        WCHAR path[MAX_PATH],HANDLE *hold) {
    WCHAR dir[MAX_PATH],partial[MAX_PATH],hex[65];
    if (wcslen(base)+100>=MAX_PATH || !cache_directory(base)) return ERROR_CANNOT_MAKE;
    for (int i=0;i<32;i++) swprintf(hex+i*2,3,L"%02x",identity->hash[i]);
    swprintf(dir,MAX_PATH,L"%ls\\%ls",base,hex);
    if (!cache_directory(dir)) return ERROR_CANNOT_MAKE;
    swprintf(path,MAX_PATH,L"%ls\\%ls",dir,name);
    if (!safe_local_path(path)) return ERROR_BAD_PATHNAME;
    *hold=open_source(path,identity);
    if (*hold!=INVALID_HANDLE_VALUE) return ERROR_SUCCESS;
    /* A fixed, exclusive staging file bounds debris after a power loss. Never
       truncate a pre-existing file (it might be a hard link). */
    swprintf(partial,MAX_PATH,L"%ls\\staging.tmp",dir);
    if (!DeleteFileW(partial) && GetLastError()!=ERROR_FILE_NOT_FOUND) return GetLastError();
    HANDLE tmp=CreateFileW(partial,GENERIC_WRITE|GENERIC_READ,0,NULL,CREATE_NEW,
            FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_SEQUENTIAL_SCAN,NULL);
    if (tmp==INVALID_HANDLE_VALUE) return GetLastError();
    LARGE_INTEGER pos; pos.QuadPart=offset;
    DWORD err=SetFilePointerEx(self,pos,NULL,FILE_BEGIN) ? ERROR_SUCCESS : GetLastError();
    ULONGLONG left=identity->length; BYTE buffer[65536],actual[32]; int last=-1;
    while (!err && left) {
        DWORD got,wrote,want=(DWORD)min(left,sizeof(buffer));
        if (!ReadFile(self,buffer,want,&got,NULL) || !got) err=ERROR_CRC;
        else if (!WriteFile(tmp,buffer,got,&wrote,NULL) || wrote!=got) err=ERROR_WRITE_FAULT;
        else {
            left-=got; int percent=(int)((identity->length-left)*100/identity->length);
            if (window && percent!=last) PostMessageW(window,PREPARING,percent,0);
            last=percent;
        }
    }
    if (!err && (!FlushFileBuffers(tmp) || !fish_hash(tmp,0,identity->length,actual) || memcmp(actual,identity->hash,32))) err=ERROR_CRC;
    CloseHandle(tmp);
    if (!err && !MoveFileExW(partial,path,MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH)) err=GetLastError();
    if (err) { DeleteFileW(partial); return err; }
    *hold=open_source(path,identity);
    return *hold!=INVALID_HANDLE_VALUE ? ERROR_SUCCESS : ERROR_CRC;
}

static BOOL image_entry(const WCHAR *relative) {
    for (unsigned i=0;image_entries[i];i++) if (!_wcsicmp(relative,image_entries[i])) return TRUE;
    return FALSE;
}
/* The released RemoveFolderEx reads this separate registry value. A stale
   path must not allow it to walk a different tree than the one we inspected. */
static BOOL released_root_matches(const WCHAR *dest) {
    const HKEY hives[]={HKEY_CURRENT_USER,HKEY_LOCAL_MACHINE};
    for (unsigned i=0;i<2;i++) {
        WCHAR stored[MAX_PATH]; DWORD bytes=sizeof(stored);
        LONG result=RegGetValueW(hives[i],L"Software\\Fish\\Fish\\0.10.4",L"RM_RF48431AECBD69377EA800D62616352F20",
                RRF_RT_REG_SZ|RRF_SUBKEY_WOW6464KEY,NULL,stored,&bytes);
        if (result==ERROR_FILE_NOT_FOUND || result==ERROR_PATH_NOT_FOUND) continue;
        if (result!=ERROR_SUCCESS) return FALSE;
        trim_trailing_slash(stored);
        if (_wcsicmp(stored,dest)) return FALSE;
    }
    return TRUE;
}
/* Older jpackage MSIs recursively removed the install directory. Refuse an
   upgrade if it could remove files outside the packaged inventory. */
enum ImageOwner { FRESH, LEGACY, CURRENT, QUARANTINE };
static BOOL directory_owned(WCHAR path[32768],size_t base,enum ImageOwner owner) {
    size_t len=wcslen(path);
    if (len>32490) return FALSE;
    wcscat(path,L"\\*");
    WIN32_FIND_DATAW entry; HANDLE h=FindFirstFileW(path,&entry);
    path[len]=0;
    if (h==INVALID_HANDLE_VALUE) {
        DWORD err=GetLastError();
        if (err==ERROR_FILE_NOT_FOUND || err==ERROR_PATH_NOT_FOUND) return TRUE;
        issue_at(L"Read installation folder",path,err); return FALSE;
    }
    BOOL ok=TRUE;
    do {
        if (!wcscmp(entry.cFileName,L".") || !wcscmp(entry.cFileName,L"..")) continue;
        swprintf(path+len,32768-len,L"\\%ls",entry.cFileName);
        BOOL known=image_entry(path+base+1);
        ok=owner==QUARANTINE ? !(entry.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT)
            : owner!=FRESH && (known ? !(entry.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT) : owner==CURRENT);
        if (!ok) issue_at(L"Check folder contents",path,(entry.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT)
            ? ERROR_REPARSE_POINT_ENCOUNTERED : ERROR_DIR_NOT_EMPTY);
        if (ok && (known || owner==QUARANTINE) && entry.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY) ok=directory_owned(path,base,owner);
        path[len]=0;
    } while (ok && FindNextFileW(h,&entry));
    if (ok && GetLastError()!=ERROR_NO_MORE_FILES) {
        issue_at(L"List installation folder",path,GetLastError()); ok=FALSE;
    }
    FindClose(h); return ok;
}
/* Quarantine is only for an unregistered copy at the ordinary per-user
   destination. Confirm identity before offering to move it. */
static BOOL partial_fish(const WCHAR *root) {
    WCHAR path[MAX_PATH];
    if (!safe_local_path(root) || wcslen(root)>MAX_PATH-55) return FALSE;
    BOOL incomplete=FALSE;
    for (unsigned i=0;startup_files[i].path;i++) {
        swprintf(path,MAX_PATH,L"%ls\\%ls",root,startup_files[i].path);
        WIN32_FILE_ATTRIBUTE_DATA attr;
        if (!GetFileAttributesExW(path,GetFileExInfoStandard,&attr)) {
            DWORD e=GetLastError();
            if (e!=ERROR_FILE_NOT_FOUND && e!=ERROR_PATH_NOT_FOUND) return FALSE;
            incomplete=TRUE;
        } else if (attr.dwFileAttributes&FILE_ATTRIBUTE_REPARSE_POINT) return FALSE;
        else if ((attr.dwFileAttributes&FILE_ATTRIBUTE_DIRECTORY) || (!attr.nFileSizeHigh && !attr.nFileSizeLow)) incomplete=TRUE;
    }
    if (!incomplete) return FALSE;
    swprintf(path,MAX_PATH,L"%ls\\Fish.exe",root);
    if (safe_local_path(path) && version_ok(path)) return TRUE;
    swprintf(path,MAX_PATH,L"%ls\\app\\Fish.cfg",root);
    if (!safe_local_path(path)) return FALSE;
    HANDLE f=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_FLAG_OPEN_REPARSE_POINT,NULL);
    if (f==INVALID_HANDLE_VALUE) return FALSE;
    char cfg[4096]; DWORD n=0;
    BOOL ok=ReadFile(f,cfg,sizeof(cfg)-1,&n,NULL);
    CloseHandle(f);
    cfg[n]=0;
    return ok && strstr(cfg,"app.mainclass=fish.Main") && strstr(cfg,"app.classpath=$APPDIR\\Fish.jar");
}
static DWORD quarantine_folder(const WCHAR *root,WCHAR backup[MAX_PATH]) {
    GUID id; WCHAR guid[39],to[MAX_PATH];
    if (wcslen(root)>MAX_PATH-55 || !safe_local_path(root) || FAILED(CoCreateGuid(&id))) return ERROR_BAD_PATHNAME;
    StringFromGUID2(&id,guid,39);
    swprintf(to,MAX_PATH,L"%ls.backup-%ls",root,guid);
    if (!MoveFileExW(root,to,MOVEFILE_WRITE_THROUGH)) return GetLastError();
    wcscpy(backup,to); return ERROR_SUCCESS;
}
static DWORD restore_quarantine(const WCHAR *root,WCHAR backup[MAX_PATH]) {
    if (!*backup || !safe_local_path(backup) || !safe_local_path(root)) return ERROR_BAD_PATHNAME;
    /* Never delete files from an unsuccessful transaction, even if they look owned. */
    if (!RemoveDirectoryW(root)) {
        DWORD err=GetLastError();
        if (err!=ERROR_FILE_NOT_FOUND && err!=ERROR_PATH_NOT_FOUND) return err;
    }
    if (!MoveFileExW(backup,root,MOVEFILE_WRITE_THROUGH)) return GetLastError();
    *backup=0; return ERROR_SUCCESS;
}
static DWORD package_product(const WCHAR *msi,WCHAR code[39]) {
    MSIHANDLE db=0,view=0,rec=0; DWORD size=39;
    DWORD err=MsiOpenDatabaseW(msi,MSIDBOPEN_READONLY,&db);
    if (!err) err=MsiDatabaseOpenViewW(db,L"SELECT `Value` FROM `Property` WHERE `Property`='ProductCode'",&view);
    if (!err) err=MsiViewExecute(view,0);
    if (!err) err=MsiViewFetch(view,&rec);
    if (!err) err=MsiRecordGetStringW(rec,1,code,&size);
    if (!err && size!=38) err=ERROR_BAD_CONFIGURATION;
    if (rec) MsiCloseHandle(rec);
    if (view) MsiCloseHandle(view);
    if (db) MsiCloseHandle(db);
    return err;
}
static DWORD check_registered(const WCHAR *dest,const WCHAR *current,WCHAR previous[39]) {
    WCHAR product[39],loc[MAX_PATH],assignment[4];
    for (DWORD i=0;;i++) {
        DWORD e=MsiEnumRelatedProductsW(FISH_UPGRADE,0,i,product);
        if (e==ERROR_NO_MORE_ITEMS) break;
        if (e) return e;
        DWORD l=MAX_PATH,a=4;
        if (MsiGetProductInfoW(product,INSTALLPROPERTY_INSTALLLOCATION,loc,&l) || !l ||
            MsiGetProductInfoW(product,INSTALLPROPERTY_ASSIGNMENTTYPE,assignment,&a)) return ERROR_BAD_CONFIGURATION;
        trim_trailing_slash(loc);
        if ((assignment[0]==L'1')!=system_wide_mode) {
            if (!_wcsicmp(loc,dest)) return ERROR_BAD_CONFIGURATION;
            continue;
        }
        if (*previous || _wcsicmp(loc,dest)) return ERROR_BAD_CONFIGURATION;
        wcscpy(previous,product);
    }
    /* A half-removed registration may no longer be enumerated as an upgrade. */
    if (!*previous && (MsiQueryProductStateW(current)==INSTALLSTATE_DEFAULT ||
            MsiQueryProductStateW(RELEASED_PRODUCT)==INSTALLSTATE_DEFAULT)) return ERROR_BAD_CONFIGURATION;
    return ERROR_SUCCESS;
}
static DWORD run_package(const WCHAR *path,const WCHAR *options) {
    issue_at(*options ? L"Repair Windows Installer files from the verified MSI" : L"Install the verified MSI",path,0);
    WCHAR program[MAX_PATH],command[2048]=L"";
    UINT len=GetSystemDirectoryW(program,MAX_PATH);
    if (!len || len>=MAX_PATH-14) return ERROR_BAD_PATHNAME;
    wcscat(program,L"\\msiexec.exe");
    if (!fish_quote(command,2048,program)) return ERROR_INVALID_PARAMETER;
    wcscat(command,L" /i ");
    if (!fish_quote(command,2048,path)) return ERROR_INVALID_PARAMETER;
    wcscat(command,L" /qn /norestart MSIRESTARTMANAGERCONTROL=Disable ");
    wcscat(command,options);
    wcscat(command,system_wide_mode ? L" ALLUSERS=1 MSIINSTALLPERUSER=\"\" INSTALLDIR=" : L" ALLUSERS=2 MSIINSTALLPERUSER=1 INSTALLDIR=");
    if (!fish_quote(command,2048,destination)) return ERROR_INVALID_PARAMETER;
    STARTUPINFOW si={.cb=sizeof(si)}; PROCESS_INFORMATION pi;
    if (!CreateProcessW(program,command,NULL,NULL,FALSE,CREATE_NO_WINDOW,NULL,NULL,&si,&pi)) return GetLastError();
    CloseHandle(pi.hThread);
    DWORD wait=WaitForSingleObject(pi.hProcess,5*60*1000),result=ERROR_TIMEOUT;
    if (wait==WAIT_OBJECT_0 && !GetExitCodeProcess(pi.hProcess,&result)) result=GetLastError();
    if (wait==WAIT_FAILED) result=GetLastError();
    CloseHandle(pi.hProcess); return result;
}
typedef DWORD (*PackageRunner)(const WCHAR *,const WCHAR *);
static DWORD transact(PackageRunner run,const WCHAR *source,BOOL same,const WCHAR *legacy,BOOL recache_first) {
    /* 'a' also replaces a corrupt launcher configuration with the same version
       or a newer timestamp; 'v' recaches from this verified MSI, not old TEMP. */
    DWORD result=recache_first ? ERROR_INSTALL_SOURCE_ABSENT : run(source,same ? L"REINSTALL=ALL REINSTALLMODE=vamus" : L"");
    if (result==ERROR_INSTALL_SOURCE_ABSENT && legacy && *legacy && !same) {
        result=run(legacy,L"REINSTALL=ALL REINSTALLMODE=vomus");
        if (!result) result=run(source,L"");
        else if (result==ERROR_SUCCESS_REBOOT_REQUIRED) result=ERROR_FAIL_REBOOT_REQUIRED;
    }
    return result;
}
static BOOL missing_local_package(const WCHAR *product) {
    WCHAR local[MAX_PATH],code[39]; DWORD size=MAX_PATH;
    return MsiGetProductInfoW(product,INSTALLPROPERTY_LOCALPACKAGE,local,&size)!=ERROR_SUCCESS
        || !size || package_product(local,code)!=ERROR_SUCCESS || _wcsicmp(product,code)!=0;
}
static BOOL shortcut_available(const GUID *folder_id,const WCHAR *relative,const WCHAR *target) {
    PWSTR folder=NULL; WCHAR file[MAX_PATH],value[MAX_PATH];
    if (FAILED(SHGetKnownFolderPath(folder_id,0,NULL,&folder))) return FALSE;
    BOOL valid=wcslen(folder)+wcslen(relative)<MAX_PATH;
    if (valid) swprintf(file,MAX_PATH,L"%ls%ls",folder,relative);
    CoTaskMemFree(folder);
    if (!valid) return FALSE;
    DWORD attr=GetFileAttributesW(file);
    if (attr==INVALID_FILE_ATTRIBUTES) {
        DWORD err=GetLastError();
        if (err==ERROR_FILE_NOT_FOUND || err==ERROR_PATH_NOT_FOUND) return TRUE;
        issue_at(L"Read existing shortcut",file,err); return FALSE;
    }
    if (attr&(FILE_ATTRIBUTE_DIRECTORY|FILE_ATTRIBUTE_REPARSE_POINT)) {
        issue_at(L"Check existing shortcut",file,ERROR_ALREADY_EXISTS); return FALSE;
    }
    IShellLinkW *link=NULL; IPersistFile *persist=NULL;
    valid=SUCCEEDED(CoCreateInstance(&CLSID_ShellLink,NULL,CLSCTX_INPROC_SERVER,&IID_IShellLinkW,(void **)&link))
        && SUCCEEDED(IShellLinkW_QueryInterface(link,&IID_IPersistFile,(void **)&persist))
        && SUCCEEDED(IPersistFile_Load(persist,file,STGM_READ))
        && IShellLinkW_GetPath(link,value,MAX_PATH,NULL,SLGP_RAWPATH)==S_OK && !_wcsicmp(value,target)
        && SUCCEEDED(IShellLinkW_GetArguments(link,value,MAX_PATH)) && !*value;
    if (persist) IPersistFile_Release(persist);
    if (link) IShellLinkW_Release(link);
    if (!valid) issue_at(L"Check existing shortcut",file,ERROR_ALREADY_EXISTS);
    return valid;
}
static DWORD run_msi(void) {
    WCHAR self[MAX_PATH],cache[MAX_PATH],source[MAX_PATH],legacy[MAX_PATH]=L"",cur[39],prev[39]=L"",target[MAX_PATH];
    WCHAR *walk=HeapAlloc(GetProcessHeap(),0,32768*sizeof(WCHAR));
    if (!walk) return ERROR_OUTOFMEMORY;
    HANDLE input=INVALID_HANDLE_VALUE,hold=INVALID_HANDLE_VALUE,old=INVALID_HANDLE_VALUE;
    PWSTR base=NULL; DWORD result=ERROR_BAD_PATHNAME; BOOL recache_first=FALSE;
    issue_at(L"Validate install destination",destination,0);
    if (!safe_local_path(destination) || wcslen(destination)>MAX_PATH-30) goto done;
    if (FAILED(SHGetKnownFolderPath(system_wide_mode ? &FOLDERID_ProgramFiles : &FOLDERID_LocalAppData,
            0,NULL,&base)) || wcslen(base)>MAX_PATH-135) goto done;
    if (system_wide_mode && (_wcsnicmp(destination,base,wcslen(base)) || destination[wcslen(base)]!=L'\\')) goto done;
    swprintf(target,MAX_PATH,L"%ls\\Fish.exe",destination);
    if (running_target(target)) { result=ERROR_SHARING_VIOLATION; issue_at(L"Check running Fish",target,0); goto done; }
    HRESULT com=CoInitializeEx(NULL,COINIT_APARTMENTTHREADED);
    BOOL links=shortcut_available(system_wide_mode ? &FOLDERID_PublicDesktop : &FOLDERID_Desktop,L"\\Fish.lnk",target)
        && shortcut_available(system_wide_mode ? &FOLDERID_CommonPrograms : &FOLDERID_Programs,L"\\Fish\\Fish.lnk",target);
    if (SUCCEEDED(com)) CoUninitialize();
    if (!links) { result=problem_code ? problem_code : ERROR_ALREADY_EXISTS; goto done; }
    swprintf(cache,MAX_PATH,L"%ls\\Fish Setup Sources",base);
    issue_at(L"Read embedded verified MSI",NULL,0);
    if (!GetModuleFileNameW(NULL,self,MAX_PATH)) goto done;
    input=CreateFileW(self,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,FILE_ATTRIBUTE_NORMAL,NULL);
    Footer current; ULONGLONG offset;
    if (input==INVALID_HANDLE_VALUE || !payload(input,&current,&offset)) { result=ERROR_CRC; goto done; }
    issue_at(L"Cache the verified MSI",cache,0);
    result=stage_source(input,offset,&current,cache,L"Fish-" FISH_VERSION L".msi",source,&hold);
    if (result) goto done;
    issue_at(L"Read package identity",source,0);
    if ((result=package_product(source,cur))) goto done;
    issue_at(L"Match Windows Installer registration",destination,0);
    if ((result=check_registered(destination,cur,prev))) goto done;
    if (!_wcsicmp(prev,RELEASED_PRODUCT) && !released_root_matches(destination)) { result=ERROR_BAD_CONFIGURATION; goto done; }
    wcscpy(walk,destination);
    if (!directory_owned(walk,wcslen(walk),!*prev ? FRESH : !_wcsicmp(prev,cur) ? CURRENT : LEGACY)) {
        result=problem_code ? problem_code : ERROR_DIR_NOT_EMPTY;
        if (*prev || system_wide_mode || _wcsicmp(destination,user_default_dest) || result!=ERROR_DIR_NOT_EMPTY ||
                !partial_fish(destination)) goto done;
        /* Verify existing children too: refuse links and any unidentified path.
           A known image can contain user files, which the rename does not touch. */
        wcscpy(walk,destination);
        if (!directory_owned(walk,wcslen(walk),QUARANTINE)) { result=problem_code; goto done; }
        if (!allow_quarantine) { result=FISH_PARTIAL_FOLDER; goto done; }
        issue_at(L"Move the incomplete Fish folder aside",destination,0);
        if ((result=quarantine_folder(destination,quarantine_path))) goto done;
    }
    if (!_wcsicmp(prev,RELEASED_PRODUCT) && offset>=released_msi.length) {
        BYTE hash[32];
        if (fish_hash(input,offset-released_msi.length,released_msi.length,hash) && !memcmp(hash,released_msi.hash,32)) {
            issue_at(L"Cache the original Fish 0.10.4 MSI",cache,0);
            result=stage_source(input,offset-released_msi.length,&released_msi,cache,L"Fish-0.10.4.msi",legacy,&old);
            if (result) goto done;
            WCHAR dir[MAX_PATH]; wcscpy(dir,legacy); *wcsrchr(dir,L'\\')=0;
            result=MsiSourceListAddSourceExW(prev,NULL,system_wide_mode ? MSIINSTALLCONTEXT_MACHINE : MSIINSTALLCONTEXT_USERUNMANAGED,
                    MSICODE_PRODUCT|MSISOURCETYPE_NETWORK,dir,1);
            /* A missing local package may prevent editing the source list; the
               one permitted recovery then explicitly recaches the matching MSI. */
            recache_first=result!=ERROR_SUCCESS;
        }
    }
    if (window) PostMessageW(window,PHASE,1,0);
    result=transact(run_package,source,!_wcsicmp(prev,cur),legacy,
        !_wcsicmp(prev,RELEASED_PRODUCT) && (recache_first || missing_local_package(prev)));
    if (result==ERROR_SUCCESS) {
        if (window) PostMessageW(window,PHASE,2,0);
        if (!startup_intact(destination)) {
            issue_at(L"Verify the installed launcher, config and runtime",destination,0);
            result=ERROR_PRODUCT_VERSION;
        }
    }
done:
    if (*quarantine_path && result && result!=ERROR_TIMEOUT && result!=ERROR_SUCCESS_REBOOT_REQUIRED
            && result!=ERROR_PRODUCT_VERSION && MsiQueryProductStateW(cur)!=INSTALLSTATE_DEFAULT) {
        DWORD restore=restore_quarantine(destination,quarantine_path);
        if (restore) issue_at(L"Restore the original folder; both copies were kept",quarantine_path,restore);
    }
    if (base) CoTaskMemFree(base);
    if (old!=INVALID_HANDLE_VALUE) CloseHandle(old);
    if (hold!=INVALID_HANDLE_VALUE) CloseHandle(hold);
    if (input!=INVALID_HANDLE_VALUE) CloseHandle(input);
    HeapFree(GetProcessHeap(),0,walk); return result;
}
