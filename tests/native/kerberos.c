#include "fish-kerberos.c"
#include <stdlib.h>
#include <string.h>

static int checks,acquired,initialized,deleted,freed;
static SECURITY_STATUS acquire_status,initialize_status;
static ULONG token_length;
#define CHECK(x) do { ++checks; if (!(x)) { fprintf(stderr,"Kerberos native assertion at line %d\n",__LINE__); exit(1); } } while (0)

static SECURITY_STATUS SEC_ENTRY acquire(SEC_WCHAR *principal,SEC_WCHAR *package,ULONG use,void *logon,void *data,
        SEC_GET_KEY_FN key,void *argument,PCredHandle handle,PTimeStamp expiry) {
    (void)expiry;
    ++acquired;
    CHECK(!principal && wcscmp(package,L"Kerberos")==0 && use==SECPKG_CRED_OUTBOUND);
    CHECK(!logon && !data && !key && !argument);
    if (acquire_status==SEC_E_OK) { handle->dwLower=1; handle->dwUpper=2; }
    return acquire_status;
}
static SECURITY_STATUS SEC_ENTRY initialize(PCredHandle credentials,PCtxtHandle previous,SEC_WCHAR *principal,
        ULONG flags,ULONG reserved,ULONG representation,PSecBufferDesc input,ULONG reserved2,PCtxtHandle context,
        PSecBufferDesc output,ULONG *attributes,PTimeStamp expiry) {
    (void)attributes; (void)expiry;
    ++initialized;
    CHECK(credentials->dwLower==1 && !previous && wcscmp(principal,L"krbsvr400/fixture.invalid")==0);
    CHECK(flags==ISC_REQ_CONNECTION && !(flags&(ISC_REQ_DELEGATE|ISC_REQ_MUTUAL_AUTH)));
    CHECK(!reserved && !reserved2 && !input && representation==SECURITY_NATIVE_DREP);
    CHECK(output->cBuffers==1 && output->pBuffers[0].BufferType==SECBUFFER_TOKEN && output->pBuffers[0].cbBuffer==FISH_MAX_TICKET);
    context->dwLower=3; context->dwUpper=4;
    memcpy(output->pBuffers[0].pvBuffer,"\0\1\2\3\377",5);
    output->pBuffers[0].cbBuffer=token_length;
    return initialize_status;
}
static SECURITY_STATUS SEC_ENTRY delete_context(PCtxtHandle context) {
    CHECK(context->dwLower==3); ++deleted; return SEC_E_OK;
}
static SECURITY_STATUS SEC_ENTRY free_credentials(PCredHandle credentials) {
    CHECK(credentials->dwLower==1); ++freed; return SEC_E_OK;
}
int main(void) {
    SecurityFunctionTableW api={0};
    api.AcquireCredentialsHandleW=acquire; api.InitializeSecurityContextW=initialize;
    api.DeleteSecurityContext=delete_context; api.FreeCredentialsHandle=free_credentials;
    unsigned char token[FISH_MAX_TICKET];
    SECURITY_STATUS cases[]={SEC_E_OK,SEC_I_CONTINUE_NEEDED,SEC_E_TARGET_UNKNOWN,SEC_I_COMPLETE_NEEDED,SEC_E_LOGON_DENIED};
    for (unsigned int i=0;i<sizeof(cases)/sizeof(cases[0]);i++) {
        memset(token,0xaa,sizeof(token));
        acquired=initialized=deleted=freed=0;
        acquire_status=SEC_E_OK; initialize_status=cases[i]; token_length=5;
        ULONG length=99;
        SECURITY_STATUS status=fish_ticket(&api,L"krbsvr400/fixture.invalid",token,&length);
        CHECK(status==cases[i] && acquired==1 && initialized==1 && deleted==1 && freed==1);
        if (status==SEC_E_OK || status==SEC_I_CONTINUE_NEEDED)
            CHECK(length==5 && memcmp(token,"\0\1\2\3\377",5)==0);
        else {
            CHECK(length==0);
            for (int j=0;j<FISH_MAX_TICKET;j++) CHECK(token[j]==0);
        }
    }
    for (int i=0;i<2;i++) {
        initialize_status=SEC_E_OK; token_length=i ? FISH_MAX_TICKET+1 : 0;
        ULONG length=99;
        CHECK(fish_ticket(&api,L"krbsvr400/fixture.invalid",token,&length)==SEC_E_INVALID_TOKEN && length==0);
        CHECK(token[0]==0 && token[FISH_MAX_TICKET-1]==0);
    }
    acquired=initialized=deleted=freed=0; acquire_status=SEC_E_NO_CREDENTIALS;
    ULONG length=99;
    CHECK(fish_ticket(&api,L"krbsvr400/fixture.invalid",token,&length)==SEC_E_NO_CREDENTIALS);
    CHECK(acquired==1 && initialized==0 && deleted==0 && freed==0 && length==0);
    puts("PASS native Kerberos provider fixtures (no Windows credential acquisition)");
    return 0;
}
