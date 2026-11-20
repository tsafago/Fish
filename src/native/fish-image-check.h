#ifndef FISH_IMAGE_CHECK_H
#define FISH_IMAGE_CHECK_H
#include "fish-setup-common.h"
#include "fish-image.generated.h"

static BOOL startup_intact(const WCHAR *root) {
    WCHAR path[32768];
    if (!startup_files[0].path || wcslen(root)>32600) return FALSE;
    for (unsigned i=0;startup_files[i].path;i++) {
        swprintf(path,32768,L"%ls\\%ls",root,startup_files[i].path);
        HANDLE f=CreateFileW(path,GENERIC_READ,FILE_SHARE_READ,NULL,OPEN_EXISTING,
                FILE_FLAG_OPEN_REPARSE_POINT|FILE_FLAG_SEQUENTIAL_SCAN,NULL);
        if (f==INVALID_HANDLE_VALUE) return FALSE;
        BY_HANDLE_FILE_INFORMATION info; LARGE_INTEGER size; BYTE hash[32];
        BOOL ok=GetFileInformationByHandle(f,&info) && !(info.dwFileAttributes&(FILE_ATTRIBUTE_REPARSE_POINT|FILE_ATTRIBUTE_DIRECTORY))
            && GetFileSizeEx(f,&size) && fish_hash(f,0,size.QuadPart,hash) && !memcmp(hash,startup_files[i].digest,32);
        CloseHandle(f);
        if (!ok) return FALSE;
    }
    return TRUE;
}
#endif
