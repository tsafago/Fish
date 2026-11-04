#include <windows.h>
#include <jni.h>
#include <stdio.h>
#include <string.h>

/* Load the delivered private JVM/DLL, without a desktop window or credential acquisition. */
int wmain(int argc,wchar_t **argv) {
    BOOL updates=argc==4 && !wcscmp(argv[2],L"--updates");
    if (argc!=2 && !updates) { fputs("Expected complete Fish image directory [--updates TEST_CLASSES].\n",stderr); return 1; }
    wchar_t image[32768],bin[32768],library[32768],jar[32768];
    DWORD length=GetFullPathNameW(argv[1],32768,image,NULL);
    if (!length || length>32000) return 1;
    swprintf(bin,32768,L"%ls\\runtime\\bin",image);
    swprintf(library,32768,L"%ls\\server\\jvm.dll",bin);
    swprintf(jar,32768,L"%ls\\app\\Fish.jar",image);
    if (updates) {
        wchar_t classes[32768];
        DWORD size=GetFullPathNameW(argv[3],32768,classes,NULL);
        if (!size || size>32000 || wcslen(jar)+size+2>=32768) return 1;
        wcscat(jar,L";"); wcscat(jar,classes);
    }
    if (!SetDllDirectoryW(bin)) { fputs("Runtime DLL search setup failed.\n",stderr); return 1; }
    HMODULE module=LoadLibraryW(library);
    if (!module) { fprintf(stderr,"Private JVM load failed: %lu\n",GetLastError()); return 1; }
    typedef jint (JNICALL *CreateVM)(JavaVM **,void **,void *);
    FARPROC symbol=GetProcAddress(module,"JNI_CreateJavaVM");
    CreateVM create=NULL;
    _Static_assert(sizeof(create)==sizeof(symbol),"Windows function pointer ABI mismatch");
    memcpy(&create,&symbol,sizeof(create));
    if (!create) return 1;
    char classpath[65536]="-Djava.class.path=";
    size_t prefix=strlen(classpath);
    if (!WideCharToMultiByte(CP_UTF8,0,jar,-1,classpath+prefix,(int)(sizeof(classpath)-prefix),NULL,NULL)) return 1;
    JavaVMOption options[2]={{classpath,NULL},{"-Djava.awt.headless=true",NULL}};
    JavaVMInitArgs args={JNI_VERSION_1_8,2,options,JNI_FALSE};
    JavaVM *vm=NULL;
    JNIEnv *env=NULL;
    if (create(&vm,(void **)&env,&args)!=JNI_OK) { fputs("Private JVM startup failed.\n",stderr); return 1; }
    int result=1;
    if (updates) {
        jclass type=(*env)->FindClass(env,"pane5250/update/PackagedUpdateSmoke");
        jmethodID method=type ? (*env)->GetStaticMethodID(env,type,"main","([Ljava/lang/String;)V") : NULL;
        if (method) {
            jobjectArray empty=(*env)->NewObjectArray(env,0,(*env)->FindClass(env,"java/lang/String"),NULL);
            (*env)->CallStaticVoidMethod(env,type,method,empty);
            if (!(*env)->ExceptionCheck(env)) result=0;
        }
    } else {
        jclass type=(*env)->FindClass(env,"pane5250/security/Kerberos");
        jmethodID method=type ? (*env)->GetStaticMethodID(env,type,"available","()Z") : NULL;
        if (method && (*env)->CallStaticBooleanMethod(env,type,method)==JNI_TRUE && !(*env)->ExceptionCheck(env)) {
            puts("PASS packaged private JVM and shipped native Kerberos capability; no tickets requested");
            result=0;
        }
    }
    if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionDescribe(env); (*env)->ExceptionClear(env); }
    if ((*vm)->DestroyJavaVM(vm)!=JNI_OK) result=1;
    return result;
}
