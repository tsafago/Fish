/* Build-time only: produce the installer's unadvanced frame, without UI or text. */
#include <windows.h>
#include <math.h>
#include <stdio.h>
#include "fish-start-size.h"
#define WIDTH FISH_START_WIDTH
static const UINT dpi=192;
static const BOOL contrast=FALSE,issue=FALSE,motion_enabled=FALSE;
static const COLORREF background=RGB(246,247,252),text=RGB(22,25,36);
static int px(int n) { return MulDiv(n,dpi,96); }
static int logical_height(void) { return FISH_START_HEIGHT; }
#include "fish-coast.h"

int wmain(int argc,wchar_t **argv) {
    if (argc!=2 || !prepare_coast(px(WIDTH),px(logical_height()),0,0) || !coast) return 1;
    DWORD bytes=(DWORD)art_w*art_h*4;
    BITMAPINFOHEADER info={.biSize=sizeof(info),.biWidth=art_w,.biHeight=-art_h,
        .biPlanes=1,.biBitCount=32,.biSizeImage=bytes};
    BITMAPFILEHEADER file={.bfType=0x4d42,.bfOffBits=sizeof(file)+sizeof(info),
        .bfSize=sizeof(file)+sizeof(info)+bytes};
    FILE *out=_wfopen(argv[1],L"wb");
    if (!out) return 1;
    BOOL ok=fwrite(&file,sizeof(file),1,out)==1 && fwrite(&info,sizeof(info),1,out)==1
        && fwrite(art_bits,bytes,1,out)==1;
    if (fclose(out)!=0) ok=FALSE;
    reset_art();
    return ok ? 0 : 1;
}
