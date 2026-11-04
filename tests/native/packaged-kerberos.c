#include <windows.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

/* Load the delivered private JVM/DLL, without a desktop window or credential acquisition. */
int wmain(int argc,wchar_t **argv) {
    if (argc!=2) { fputs("Expected complete Fish image directory.\n",stderr); return 1; }
    wchar_t image[32768],bin[32768],library[32768],jar[32768];
    DWORD length=GetFullPathNameW(argv[1],32768,image,NULL);
    if (!length || length>32000) return 1;
    swprintf(bin,32768,L"%ls\\runtime\\bin",image);
    swprintf(library,32768,L"%ls\\server\\jvm.dll",bin);
    swprintf(jar,32768,L"%ls\\app\\Fish.jar",image);
    if (!SetDllDirectoryW(bin)) { fputs("Runtime DLL search setup failed.\n",stderr); return 1; }
    HMODULE module=LoadLibraryW(library);
    if (!module) { fprintf(stderr,"Private JVM load failed: %lu\n",GetLastError()); return 1; }
    typedef jint (JNICALL *CreateVM)(JavaVM **,void **,void *);
    FARPROC symbol=GetProcAddress(module,"JNI_CreateJavaVM");
    CreateVM create=NULL;
    _Static_assert(sizeof(create)==sizeof(symbol),"Windows function pointer ABI mismatch");
    memcpy(&create,&symbol,sizeof(create));
    if (!create) return 1;
    char classpath[32790]="-Djava.class.path=";
    size_t prefix=strlen(classpath);
    if (!WideCharToMultiByte(CP_UTF8,0,jar,-1,classpath+prefix,(int)(sizeof(classpath)-prefix),NULL,NULL)) return 1;
    JavaVMOption options[2]={{classpath,NULL},{"-Djava.awt.headless=true",NULL}};
    JavaVMInitArgs args={JNI_VERSION_1_8,2,options,JNI_FALSE};
    JavaVM *vm=NULL;
    JNIEnv *env=NULL;
    if (create(&vm,(void **)&env,&args)!=JNI_OK) { fputs("Private JVM startup failed.\n",stderr); return 1; }
    int result=1;
    jclass type=(*env)->FindClass(env,"pane5250/security/Kerberos");
    jmethodID method=type ? (*env)->GetStaticMethodID(env,type,"available","()Z") : NULL;
    if (method && (*env)->CallStaticBooleanMethod(env,type,method)==JNI_TRUE && !(*env)->ExceptionCheck(env)) {
        puts("PASS packaged private JVM and shipped native Kerberos capability; no tickets requested");
        result=0;
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if ((*vm)->DestroyJavaVM(vm)!=JNI_OK) result=1;
    return result;
}
