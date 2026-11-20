/* This executable never calls msiexec or looks at installation registrations.
   The runner boundary is injected; staging / hashing / image checks are real. */
#include "../../src/native/fish-setup.c"
#define REQUIRE(test) do { if (!(test)) { fwprintf(stderr,L"Repair fixture failed at line %d (Win32 %lu)\n",__LINE__,GetLastError()); return 1; } } while (0)
static int calls;
static DWORD sequence[3];
static WCHAR args_seen[3][64];
static DWORD fixture_run(const WCHAR *path,const WCHAR *args) {
    if (!*path || calls>=3 || wcslen(args)>=64) return ERROR_INVALID_PARAMETER;
    wcscpy(args_seen[calls],args); return sequence[calls++];
}
int wmain(int argc,WCHAR **argv) {
    REQUIRE(argc==5);
    WCHAR *setup=argv[1],*fixture=argv[2],*image=argv[3],msi[MAX_PATH],legacy[MAX_PATH],product[39];
    HANDLE input=CreateFileW(setup,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,0,NULL),hold=INVALID_HANDLE_VALUE;
    REQUIRE(input!=INVALID_HANDLE_VALUE);
    Footer id; ULONGLONG pos;
    REQUIRE(payload(input,&id,&pos));
    REQUIRE(!stage_source(input,pos,&id,fixture,L"Fish-" FISH_VERSION L".msi",msi,&hold));
    REQUIRE(!package_product(msi,product) && _wcsicmp(product,RELEASED_PRODUCT));
    CloseHandle(hold);
    /* Simulate an interrupted write and a truncated previous source. */
    WCHAR scratch[MAX_PATH]; wcscpy(scratch,msi); wcscpy(wcsrchr(scratch,L'\\')+1,L"staging.tmp");
    HANDLE interrupted=CreateFileW(scratch,GENERIC_WRITE,0,NULL,CREATE_NEW,0,NULL);
    REQUIRE(interrupted!=INVALID_HANDLE_VALUE); CloseHandle(interrupted);
    HANDLE damaged=CreateFileW(msi,GENERIC_WRITE,0,NULL,TRUNCATE_EXISTING,0,NULL); REQUIRE(damaged!=INVALID_HANDLE_VALUE); CloseHandle(damaged);
    REQUIRE(!stage_source(input,pos,&id,fixture,L"Fish-" FISH_VERSION L".msi",msi,&hold)); CloseHandle(hold);
    REQUIRE(GetFileAttributesW(scratch)==INVALID_FILE_ATTRIBUTES);
    REQUIRE(pos>=released_msi.length && !stage_source(input,pos-released_msi.length,&released_msi,fixture,L"Fish-0.10.4.msi",legacy,&hold));
    REQUIRE(!package_product(legacy,product) && !_wcsicmp(product,RELEASED_PRODUCT));
    CloseHandle(hold); CloseHandle(input);
    REQUIRE(startup_intact(image));
    WCHAR cfg[MAX_PATH],backup[MAX_PATH]; swprintf(cfg,MAX_PATH,L"%ls\\app\\Fish.cfg",image);
    swprintf(backup,MAX_PATH,L"%ls\\config.backup",fixture);
    REQUIRE(MoveFileW(cfg,backup) && !startup_intact(image));
    REQUIRE(transact(fixture_run,msi,TRUE,L"",FALSE)==0 && calls==1 && wcsstr(args_seen[0],L"vamus"));
    REQUIRE(MoveFileW(backup,cfg) && startup_intact(image));
    calls=0; sequence[0]=ERROR_INSTALL_SOURCE_ABSENT; sequence[1]=0; sequence[2]=0;
    REQUIRE(!transact(fixture_run,msi,FALSE,legacy,FALSE) && calls==3 && !*args_seen[0] && wcsstr(args_seen[1],L"vomus") && !*args_seen[2]);
    calls=0; sequence[0]=ERROR_INSTALL_SOURCE_ABSENT;
    REQUIRE(transact(fixture_run,msi,FALSE,legacy,TRUE)==ERROR_INSTALL_SOURCE_ABSENT && calls==1 && wcsstr(args_seen[0],L"vomus"));
    calls=0; sequence[0]=ERROR_SUCCESS_REBOOT_REQUIRED;
    REQUIRE(transact(fixture_run,msi,FALSE,legacy,TRUE)==ERROR_FAIL_REBOOT_REQUIRED && calls==1);
    calls=0; sequence[0]=ERROR_INSTALL_ALREADY_RUNNING;
    REQUIRE(transact(fixture_run,msi,FALSE,legacy,FALSE)==ERROR_INSTALL_ALREADY_RUNNING && calls==1 && startup_intact(image));
    WCHAR tree[32768],foreign[MAX_PATH]; wcscpy(tree,image);
    REQUIRE(directory_owned(tree,wcslen(tree),LEGACY) && !directory_owned(tree,wcslen(tree),FRESH));
    swprintf(foreign,MAX_PATH,L"%ls\\app\\personal.txt",image);
    HANDLE f=CreateFileW(foreign,GENERIC_WRITE,0,NULL,CREATE_NEW,0,NULL);
    REQUIRE(f!=INVALID_HANDLE_VALUE); CloseHandle(f);
    REQUIRE(!directory_owned(tree,wcslen(tree),LEGACY) && directory_owned(tree,wcslen(tree),CURRENT) && GetFileAttributesW(foreign)!=INVALID_FILE_ATTRIBUTES);
    WCHAR original[MAX_PATH],saved[MAX_PATH]=L"";
    swprintf(original,MAX_PATH,L"%ls\\Fish",fixture);
    REQUIRE(!partial_fish(original) && !partial_fish(image));
    REQUIRE(MoveFileW(image,original));
    swprintf(cfg,MAX_PATH,L"%ls\\app\\Fish.cfg",original);
    REQUIRE(DeleteFileW(cfg) && partial_fish(original));
    HANDLE locked=CreateFileW(original,GENERIC_READ,FILE_SHARE_READ|FILE_SHARE_WRITE,NULL,OPEN_EXISTING,
        FILE_FLAG_BACKUP_SEMANTICS,NULL);
    REQUIRE(locked!=INVALID_HANDLE_VALUE);
    DWORD lock_error=quarantine_folder(original,saved);
    CloseHandle(locked);
    REQUIRE((lock_error==ERROR_SHARING_VIOLATION || lock_error==ERROR_ACCESS_DENIED) && !*saved && partial_fish(original));
    WCHAR link[MAX_PATH]; swprintf(link,MAX_PATH,L"%ls\\app\\linked",original);
    REQUIRE(MoveFileW(argv[4],link));
    wcscpy(tree,original);
    BOOL accepted=directory_owned(tree,wcslen(tree),QUARANTINE);
    REQUIRE(RemoveDirectoryW(link));
    REQUIRE(!accepted && problem_code==ERROR_REPARSE_POINT_ENCOUNTERED && !wcscmp(problem_path,link));
    REQUIRE(!quarantine_folder(original,saved) && *saved && GetFileAttributesW(original)==INVALID_FILE_ATTRIBUTES);
    REQUIRE(CreateDirectoryW(original,NULL));
    swprintf(foreign,MAX_PATH,L"%ls\\foreign.txt",original);
    f=CreateFileW(foreign,GENERIC_WRITE,0,NULL,CREATE_NEW,0,NULL);
    REQUIRE(f!=INVALID_HANDLE_VALUE); CloseHandle(f);
    REQUIRE(restore_quarantine(original,saved)==ERROR_DIR_NOT_EMPTY && *saved);
    REQUIRE(DeleteFileW(foreign) && !restore_quarantine(original,saved) && !*saved);
    swprintf(foreign,MAX_PATH,L"%ls\\app\\personal.txt",original);
    REQUIRE(GetFileAttributesW(foreign)!=INVALID_FILE_ATTRIBUTES && partial_fish(original));
    REQUIRE(!quarantine_folder(original,saved) && CreateDirectoryW(original,NULL) && !restore_quarantine(original,saved));
    REQUIRE(GetFileAttributesW(foreign)!=INVALID_FILE_ATTRIBUTES);
    wprintf(L"Verified MSI identities and source recovery, missing config, locked and linked folders, quarantine, rollback and preservation of foreign files.\n");
    return 0;
}
