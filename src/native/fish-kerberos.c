#define SECURITY_WIN32
#include <windows.h>
#include <security.h>
#include <jni.h>
#include <stdio.h>

#define FISH_MAX_TICKET 16384

static SECURITY_STATUS fish_ticket(PSecurityFunctionTableW api, WCHAR *principal,
                                   unsigned char *token, ULONG *length) {
    CredHandle credentials;
    CtxtHandle context;
    TimeStamp expiry;
    ULONG attributes=0;
    SecBuffer buffer={FISH_MAX_TICKET,SECBUFFER_TOKEN,token};
    SecBufferDesc output={SECBUFFER_VERSION,1,&buffer};
    SecInvalidateHandle(&credentials);
    SecInvalidateHandle(&context);
    *length=0;
    SECURITY_STATUS status=api->AcquireCredentialsHandleW(NULL,L"Kerberos",SECPKG_CRED_OUTBOUND,
            NULL,NULL,NULL,NULL,&credentials,&expiry);
    if (status==SEC_E_OK) {
        /* TN5250 transports one initial token, not an AP_REP exchange or delegated credentials. */
        status=api->InitializeSecurityContextW(&credentials,NULL,principal,ISC_REQ_CONNECTION,
                0,SECURITY_NATIVE_DREP,NULL,0,&context,&output,&attributes,&expiry);
        if (status==SEC_E_OK || status==SEC_I_CONTINUE_NEEDED) {
            if (buffer.cbBuffer==0 || buffer.cbBuffer>FISH_MAX_TICKET || buffer.pvBuffer!=token)
                status=SEC_E_INVALID_TOKEN;
            else *length=buffer.cbBuffer;
        }
    }
    if (SecIsValidHandle(&context)) api->DeleteSecurityContext(&context);
    if (SecIsValidHandle(&credentials)) api->FreeCredentialsHandle(&credentials);
    if (*length==0) SecureZeroMemory(token,FISH_MAX_TICKET);
    return status;
}

static const char *fish_error(SECURITY_STATUS status) {
    switch (status) {
        case SEC_E_NO_CREDENTIALS: case SEC_E_NO_AUTHENTICATING_AUTHORITY: case SEC_E_NO_IMPERSONATION:
            return "Windows has no usable Kerberos credentials or cannot reach the domain controller. Sign in with a domain account and check domain/VPN connectivity.";
        case SEC_E_TARGET_UNKNOWN: case SEC_E_WRONG_PRINCIPAL:
            return "The KDC could not identify the IBM i service. Check Kerberos server DNS identity and the administrator-registered krbsvr400 SPN/keytab; an IP is not an SPN.";
        case SEC_E_LOGON_DENIED:
            return "Windows/KDC refused Kerberos sign-in. Ask your administrator to check the account and service policy.";
        case SEC_E_TIME_SKEW: case SEC_E_CONTEXT_EXPIRED: case SEC_E_CERT_EXPIRED:
            return "Kerberos credentials or clock agreement expired. Check Windows/domain time and renew Windows sign-in.";
        case SEC_E_SECPKG_NOT_FOUND: case SEC_E_UNSUPPORTED_FUNCTION:
            return "The Windows Kerberos provider or required policy is unavailable. Fish does not fall back to NTLM or a password.";
        case SEC_E_BUFFER_TOO_SMALL:
            return "The Kerberos token exceeds TN5250's 16384-byte limit. Ask your administrator to review ticket/PAC size.";
        case SEC_I_COMPLETE_NEEDED: case SEC_I_COMPLETE_AND_CONTINUE:
            return "The provider requested a token-completion exchange unsupported by this Kerberos-only initial-token flow. No token was sent.";
        default:
            return "Windows could not create the IBM i Kerberos token. Check domain connectivity, DNS/SPN registration, policy and current Windows sign-in.";
    }
}

JNIEXPORT jboolean JNICALL Java_pane5250_security_Kerberos_available0(JNIEnv *env,jclass type) {
    (void)env; (void)type;
    PSecPkgInfoW info=NULL;
    SECURITY_STATUS status=QuerySecurityPackageInfoW(L"Kerberos",&info);
    if (info) FreeContextBuffer(info);
    return status==SEC_E_OK ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jbyteArray JNICALL Java_pane5250_security_Kerberos_ticket0(JNIEnv *env,jclass type,jstring target) {
    (void)type;
    unsigned char token[FISH_MAX_TICKET]={0};
    WCHAR principal[264];
    jbyteArray result=NULL;
    jsize size=(*env)->GetStringLength(env,target);
    if (size<11 || size>=264) {
        (*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),"Invalid IBM i Kerberos principal.");
        return NULL;
    }
    (*env)->GetStringRegion(env,target,0,size,(jchar *)principal);
    if ((*env)->ExceptionCheck(env)) return NULL;
    principal[size]=0;
    ULONG length=0;
    PSecurityFunctionTableW api=InitSecurityInterfaceW();
    SECURITY_STATUS status=api ? fish_ticket(api,principal,token,&length) : SEC_E_SECPKG_NOT_FOUND;
    if ((status==SEC_E_OK || status==SEC_I_CONTINUE_NEEDED) && length>0) {
        result=(*env)->NewByteArray(env,(jsize)length);
        if (result) (*env)->SetByteArrayRegion(env,result,0,(jsize)length,(jbyte *)token);
    } else {
        char message[640];
        snprintf(message,sizeof(message),"Kerberos (SSPI 0x%08lX): %s",(unsigned long)status,fish_error(status));
        (*env)->ThrowNew(env,(*env)->FindClass(env,"java/io/IOException"),message);
    }
    SecureZeroMemory(token,sizeof(token));
    SecureZeroMemory(principal,sizeof(principal));
    return result;
}
