#include <windows.h>
#include <msiquery.h>
#include <stdio.h>
#include "fish-image-check.h"

__declspec(dllexport) UINT WINAPI CheckFishImage(MSIHANDLE session) {
    WCHAR root[32768]; DWORD count=32768;
    return MsiGetPropertyW(session,L"CustomActionData",root,&count)==ERROR_SUCCESS
        && count && startup_intact(root) ? ERROR_SUCCESS : ERROR_INSTALL_FAILURE;
}
