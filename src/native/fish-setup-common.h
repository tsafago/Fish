#ifndef FISH_SETUP_COMMON_H
#define FISH_SETUP_COMMON_H
#include <windows.h>
#include <bcrypt.h>
#include <wchar.h>
#include <stdint.h>

static BOOL fish_hash(HANDLE file,ULONGLONG offset,ULONGLONG length,BYTE result[32]) {
    BCRYPT_ALG_HANDLE algorithm=NULL;
    BCRYPT_HASH_HANDLE hash=NULL;
    LARGE_INTEGER position;
    BYTE buffer[65536];
    BOOL success=FALSE;
    position.QuadPart=(LONGLONG)offset;
    if (!SetFilePointerEx(file,position,NULL,FILE_BEGIN)) return FALSE;
    if (BCryptOpenAlgorithmProvider(&algorithm,BCRYPT_SHA256_ALGORITHM,NULL,0)!=0) goto done;
    if (BCryptCreateHash(algorithm,&hash,NULL,0,NULL,0,0)!=0) goto done;
    while (length) {
        DWORD got=0,want=(DWORD)(length<sizeof(buffer) ? length : sizeof(buffer));
        if (!ReadFile(file,buffer,want,&got,NULL) || !got) goto done;
        if (BCryptHashData(hash,buffer,got,0)!=0) goto done;
        length-=got;
    }
    success=BCryptFinishHash(hash,result,32,0)==0;
done:
    if (hash) BCryptDestroyHash(hash);
    if (algorithm) BCryptCloseAlgorithmProvider(algorithm,0);
    return success;
}

static BOOL fish_quote(WCHAR *output,size_t capacity,const WCHAR *argument) {
    size_t index=wcslen(output),slashes=0;
    if (index+3>=capacity) return FALSE;
    output[index++]=L'"';
    for (const WCHAR *p=argument;;p++) {
        if (*p==L'\\') { slashes++; continue; }
        size_t count=(*p==L'"' || !*p) ? slashes*2 : slashes;
        if (*p==L'"') count++;
        if (index+count+3>=capacity) return FALSE;
        while (count--) output[index++]=L'\\';
        slashes=0;
        if (!*p) break;
        output[index++]=*p;
    }
    output[index++]=L'"'; output[index]=0;
    return TRUE;
}
#endif
